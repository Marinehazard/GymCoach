package com.gymcoach.app.network.claude

import com.gymcoach.core.coach.wire.RequestBuilder
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.repo.ApiKeyStore
import com.gymcoach.core.repo.BackendError
import com.gymcoach.core.repo.BackendException
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.StreamFrame
import com.gymcoach.core.repo.TestResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource

/**
 * Direct Anthropic Messages API backend (§6.1/§6.2): `POST https://api.anthropic.com/v1/messages`
 * with `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json` and (streaming)
 * `accept: text/event-stream`; no `anthropic-beta`, no workspace header. The request body is sent
 * exactly as RequestBuilder produced it. The key is read from the Keystore-backed [ApiKeyStore] per
 * request, is placed only in the `x-api-key` header and is never logged or put into an error.
 *
 * Retries follow [RetryPolicy] (§6.10) and never happen after output reached the collector. The
 * `request-id` response header is attached to every [StreamFrame] and [BackendException].
 *
 * [settings] is optional so the Phase-0 AppContainer call `DirectApiBackend(okHttp, apiKeyStore)`
 * compiles; [test] then builds the ping request for the default model. AppContainer should pass
 * `settingsRepository` so Test uses the selected model.
 */
class DirectApiBackend(
    private val client: OkHttpClient,
    private val keys: ApiKeyStore,
    private val settings: SettingsRepository? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val baseUrl: String = "https://api.anthropic.com",
) : ClaudeBackend {

    override val mode: BackendMode = BackendMode.DIRECT_API

    override fun stream(body: JsonObject): Flow<StreamFrame> = callbackFlow {
        val activeCall = AtomicReference<Call?>(null)
        val worker = launch(Dispatchers.IO) {
            try {
                val key = keys.read() ?: throw BackendException(BackendError.NotConfigured)
                val payload = encodeBody(body)
                var retries = 0
                var emitted = false
                while (true) {
                    var requestId: String? = null
                    try {
                        val call = client.newCall(buildRequest(key, payload, streaming = true))
                        activeCall.set(call)
                        call.execute().use { response ->
                            requestId = response.header(HEADER_REQUEST_ID)
                            if (!response.isSuccessful) {
                                throw BackendException(
                                    HttpErrorMapper.map(response.code, response.bodyTextOrNull(), response.header(HEADER_RETRY_AFTER)),
                                    requestId,
                                )
                            }
                            val source = response.bodySourceOrNull()
                                ?: throw BackendException(BackendError.Transport("empty response body", partialOutput = false), requestId)
                            val rid = requestId
                            val terminal = readSseFrames(source) { event ->
                                emitted = true
                                trySendBlocking(StreamFrame(event, rid)).getOrThrow()
                            }
                            if (!terminal) {
                                throw BackendException(BackendError.Transport("stream ended before message_stop", partialOutput = emitted), requestId)
                            }
                        }
                        break
                    } catch (e: BackendException) {
                        val decision = retryPolicy.decide(e.error, retries, emitted)
                        if (decision is RetryPolicy.Decision.Retry) {
                            delay(decision.delayMs)
                            retries++
                        } else {
                            throw e
                        }
                    } catch (e: IOException) {
                        if (!isActive) throw CancellationException("stream cancelled")
                        val error = BackendError.Transport(transportMessage(e), partialOutput = emitted)
                        val decision = retryPolicy.decide(error, retries, emitted)
                        if (decision is RetryPolicy.Decision.Retry) {
                            delay(decision.delayMs)
                            retries++
                        } else {
                            throw BackendException(error, requestId)
                        }
                    }
                }
                channel.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                channel.close(e)
            }
        }
        awaitClose {
            activeCall.get()?.cancel()
            worker.cancel()
        }
    }

    override suspend fun complete(body: JsonObject): StreamFrame = withContext(Dispatchers.IO) {
        val key = keys.read() ?: throw BackendException(BackendError.NotConfigured)
        val payload = encodeBody(body)
        var retries = 0
        var result: StreamFrame? = null
        while (result == null) {
            var requestId: String? = null
            try {
                val response = client.newCall(buildRequest(key, payload, streaming = false)).await()
                response.use {
                    requestId = it.header(HEADER_REQUEST_ID)
                    val text = it.bodyTextOrNull()
                    if (!it.isSuccessful) {
                        throw BackendException(HttpErrorMapper.map(it.code, text, it.header(HEADER_RETRY_AFTER)), requestId)
                    }
                    val obj = parseJsonObject(text)
                        ?: throw BackendException(BackendError.Unknown("response is not a JSON object"), requestId)
                    result = StreamFrame(obj, requestId)
                }
            } catch (e: BackendException) {
                val decision = retryPolicy.decide(e.error, retries, outputReceived = false)
                if (decision is RetryPolicy.Decision.Retry) {
                    delay(decision.delayMs)
                    retries++
                } else {
                    throw e
                }
            } catch (e: IOException) {
                val error = BackendError.Transport(transportMessage(e), partialOutput = false)
                val decision = retryPolicy.decide(error, retries, outputReceived = false)
                if (decision is RetryPolicy.Decision.Retry) {
                    delay(decision.delayMs)
                    retries++
                } else {
                    throw BackendException(error, requestId)
                }
            }
        }
        checkNotNull(result)
    }

    override suspend fun test(): TestResult = try {
        val s = settings?.get() ?: AppSettings()
        val frame = complete(RequestBuilder.buildTest(s))
        TestResult(error = null, requestId = frame.requestId)
    } catch (e: BackendException) {
        TestResult(error = e.error, requestId = e.requestId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TestResult(error = BackendError.Unknown(e.javaClass.simpleName), requestId = null)
    }

    private fun buildRequest(key: String, payload: ByteArray, streaming: Boolean): Request =
        Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("accept", if (streaming) "text/event-stream" else "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private companion object {
        const val ANTHROPIC_VERSION: String = "2023-06-01"
    }
}

// ---- shared OkHttp / SSE helpers (also used by BridgeBackend) ---------------------------------------

internal const val HEADER_REQUEST_ID: String = "request-id"
internal const val HEADER_RETRY_AFTER: String = "retry-after"

/** `application/json` without a charset parameter (ByteArray bodies do not get one appended). */
internal val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()

internal fun encodeBody(body: JsonObject): ByteArray =
    CoreJson.wire.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { cancel() }
}

/** Response body as text, or null (no body / read failure). Error bodies are small. */
internal fun Response.bodyTextOrNull(): String? = try {
    val b: ResponseBody? = body
    b?.string()
} catch (e: IOException) {
    null
}

internal fun Response.bodySourceOrNull(): BufferedSource? {
    val b: ResponseBody? = body
    return b?.source()
}

internal fun parseJsonObject(text: String?): JsonObject? {
    if (text.isNullOrBlank()) return null
    return try {
        CoreJson.wire.parseToJsonElement(text) as? JsonObject
    } catch (e: IllegalArgumentException) {   // SerializationException included
        null
    }
}

internal fun JsonObject.eventType(): String? = (this["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Exception text for a transport failure: the exception class and its message (OkHttp messages never contain headers). */
internal fun transportMessage(e: IOException): String = e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName

/**
 * Reads one SSE body and hands every `data:` payload that is a JSON object to [onFrame] (the SSE
 * event name is ignored; consumers dispatch on the payload's `type`, §6.3). `error` events are
 * forwarded like any other frame — StreamAccumulator turns them into Overloaded/StreamError — unless
 * [interceptError] maps them to a [BackendError] to throw instead. Returns true when the stream
 * ended properly (`message_stop` or a forwarded `error` event was seen).
 */
internal fun readSseFrames(
    source: BufferedSource,
    interceptError: (JsonObject) -> BackendError? = { null },
    onFrame: (JsonObject) -> Unit,
): Boolean {
    var terminal = false
    SseBodyReader().read(source) { sse ->
        val obj = parseJsonObject(sse.data) ?: return@read
        when (obj.eventType()) {
            "message_stop" -> terminal = true
            "error" -> {
                val mapped = interceptError(obj)
                if (mapped != null) throw BackendException(mapped)
                terminal = true
            }
        }
        onFrame(obj)
    }
    return terminal
}
