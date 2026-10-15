package com.gymcoach.app.network.bridge

import com.gymcoach.app.network.claude.HEADER_REQUEST_ID
import com.gymcoach.app.network.claude.HEADER_RETRY_AFTER
import com.gymcoach.app.network.claude.HttpErrorMapper
import com.gymcoach.app.network.claude.JSON_MEDIA_TYPE
import com.gymcoach.app.network.claude.await
import com.gymcoach.app.network.claude.bodySourceOrNull
import com.gymcoach.app.network.claude.bodyTextOrNull
import com.gymcoach.app.network.claude.encodeBody
import com.gymcoach.app.network.claude.eventType
import com.gymcoach.app.network.claude.parseJsonObject
import com.gymcoach.app.network.claude.readSseFrames
import com.gymcoach.app.network.claude.transportMessage
import com.gymcoach.core.coach.wire.RequestBuilder
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Home-bridge backend (§6.13, docs/bridge/PROTOCOL.md — reduced protocol, experimental, off by
 * default): `POST {bridgeUrl}/v1/messages` with `Authorization: Bearer <bridge secret>` (secret held
 * by the Keystore-backed [ApiKeyStore]) and the same JSON body the direct backend would send, minus
 * every `tool_result` block (the bridge cannot receive tool_result echoes; outcomes travel in the
 * `<delta>` text). The answer is the same Messages-API SSE stream, parsed by the same SseParser.
 * Failures map to [BackendError.BridgeUnavailable]; no automatic retries (§6.10).
 */
class BridgeBackend(
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val keys: ApiKeyStore,
) : ClaudeBackend {

    override val mode: BackendMode = BackendMode.HOME_BRIDGE

    override fun stream(body: JsonObject): Flow<StreamFrame> = callbackFlow {
        val activeCall = AtomicReference<Call?>(null)
        val worker = launch(Dispatchers.IO) {
            var emitted = false
            var requestId: String? = null
            try {
                val (url, secret) = endpoint()
                val call = client.newCall(buildRequest(url, secret, encodeBody(reduceForBridge(body)), streaming = true))
                activeCall.set(call)
                call.execute().use { response ->
                    requestId = response.header(HEADER_REQUEST_ID)
                    if (!response.isSuccessful) throw BackendException(mapHttp(response), requestId)
                    val source = response.bodySourceOrNull()
                        ?: throw BackendException(BackendError.BridgeUnavailable("empty response from the bridge"), requestId)
                    val rid = requestId
                    val terminal = readSseFrames(source, interceptError = { bridgeErrorOrNull(it) }) { event ->
                        emitted = true
                        trySendBlocking(StreamFrame(event, rid)).getOrThrow()
                    }
                    if (!terminal) {
                        throw BackendException(BackendError.Transport("bridge stream ended before message_stop", partialOutput = emitted), requestId)
                    }
                }
                channel.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!isActive) throw CancellationException("stream cancelled")
                val error = if (emitted) BackendError.Transport(transportMessage(e), partialOutput = true) else BackendError.BridgeUnavailable(UNREACHABLE)
                channel.close(BackendException(error, requestId))
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
        val (url, secret) = endpoint()
        val response = try {
            client.newCall(buildRequest(url, secret, encodeBody(reduceForBridge(body)), streaming = false)).await()
        } catch (e: IOException) {
            throw BackendException(BackendError.BridgeUnavailable(UNREACHABLE))
        }
        response.use {
            val requestId = it.header(HEADER_REQUEST_ID)
            if (!it.isSuccessful) throw BackendException(mapHttp(it), requestId)
            val obj = parseJsonObject(it.bodyTextOrNull())
                ?: throw BackendException(BackendError.BridgeUnavailable("bridge answered with no JSON message"), requestId)
            bridgeErrorOrNull(obj)?.let { err -> throw BackendException(err, requestId) }
            StreamFrame(obj, requestId)
        }
    }

    override suspend fun test(): TestResult = try {
        val frame = complete(RequestBuilder.buildTest(settings.get()))
        TestResult(error = null, requestId = frame.requestId)
    } catch (e: BackendException) {
        TestResult(error = e.error, requestId = e.requestId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TestResult(error = BackendError.Unknown(e.javaClass.simpleName), requestId = null)
    }

    // ---- internals -----------------------------------------------------------------------------------

    /** (messages URL, bridge secret); NotConfigured while either is missing. */
    private suspend fun endpoint(): Pair<String, String> {
        val base = settings.get().bridgeUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw BackendException(BackendError.NotConfigured)
        val secret = keys.readBridgeSecret() ?: throw BackendException(BackendError.NotConfigured)
        return "$base/v1/messages" to secret
    }

    private fun buildRequest(url: String, secret: String, payload: ByteArray, streaming: Boolean): Request =
        Request.Builder()
            .url(url)
            .header("authorization", "Bearer $secret")
            .header("accept", if (streaming) "text/event-stream" else "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun mapHttp(response: Response): BackendError {
        val text = response.bodyTextOrNull()
        val err = HttpErrorMapper.parseErrorBody(text)
        return when {
            err.type == BRIDGE_UNAVAILABLE -> BackendError.BridgeUnavailable(err.message ?: "bridge unavailable")
            response.code == 401 -> BackendError.BridgeUnavailable("bridge secret wrong")
            response.code == 503 -> BackendError.BridgeUnavailable(err.message ?: "bridge unavailable")
            else -> HttpErrorMapper.map(response.code, text, response.header(HEADER_RETRY_AFTER))
        }
    }

    private companion object {
        const val BRIDGE_UNAVAILABLE: String = "bridge_unavailable"
        const val UNREACHABLE: String = "PC offline or unreachable"

        /** Mid-stream or whole-body `{"type":"error","error":{"type":"bridge_unavailable","message":…}}` → BridgeUnavailable; other errors pass through. */
        fun bridgeErrorOrNull(event: JsonObject): BackendError? {
            if (event.eventType() != "error") return null
            val error = event["error"] as? JsonObject ?: return null
            val type = (error["type"] as? JsonPrimitive)?.content
            if (type != BRIDGE_UNAVAILABLE) return null
            val message = (error["message"] as? JsonPrimitive)?.content ?: "bridge unavailable"
            return BackendError.BridgeUnavailable(message)
        }
    }
}

/**
 * The reduced HOME_BRIDGE body (§6.13): every `tool_result` content block is dropped from user
 * messages, and a user message left without content is dropped. Everything else is sent unchanged.
 * Idempotent (RequestBuilder may already have removed them).
 */
fun reduceForBridge(body: JsonObject): JsonObject {
    val messages = body["messages"] as? JsonArray ?: return body
    val reduced = ArrayList<JsonElement>(messages.size)
    for (m in messages) {
        val msg = m as? JsonObject
        if (msg == null) {
            reduced.add(m)
            continue
        }
        val role = (msg["role"] as? JsonPrimitive)?.content
        val content = msg["content"] as? JsonArray
        if (role != "user" || content == null) {
            reduced.add(msg)
            continue
        }
        val kept = content.filter { block -> !((block as? JsonObject)?.eventType() == "tool_result") }
        if (kept.isEmpty()) continue
        reduced.add(if (kept.size == content.size) msg else JsonObject(msg + ("content" to JsonArray(kept))))
    }
    return JsonObject(body + ("messages" to JsonArray(reduced)))
}
