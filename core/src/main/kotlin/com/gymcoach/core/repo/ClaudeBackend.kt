// core/src/main/kotlin/com/gymcoach/core/repo/ClaudeBackend.kt
package com.gymcoach.core.repo

import com.gymcoach.core.model.BackendMode
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Backend-agnostic and deliberately untyped at the boundary (R1 §3.12): `body` is the exact
 * Messages-API request JSON; each emitted element is one Messages-API stream event as parsed JSON
 * (the `data:` payload of an SSE event, or the bridge's equivalent). Transport failures and HTTP
 * errors surface as [BackendException]. Typed parsing happens in core.coach.wire.
 */
interface ClaudeBackend {
    val mode: BackendMode
    /** Streaming request (`stream: true`). Cancelling the collector cancels the call. */
    fun stream(body: JsonObject): Flow<StreamFrame>
    /**
     * Non-streamed request (`stream: false`): one POST, the full Message JSON returned as
     * [StreamFrame.event] plus the request-id header. Used by TranscriptCompactor (§6.9) and by
     * the Backend screen's Test (§8.18). Throws [BackendException] on any HTTP/transport error.
     */
    suspend fun complete(body: JsonObject): StreamFrame
    /** Sends RequestBuilder.buildTest(settings) via [complete]; never throws. */
    suspend fun test(): TestResult
}

/** One stream event (or a whole non-streamed Message) plus the response headers captured once (request-id, rate-limit headers). */
data class StreamFrame(val event: JsonObject, val requestId: String? = null)

/** error == null means the test request succeeded; requestId is shown on the Backend screen either way (never the key). */
data class TestResult(val error: BackendError?, val requestId: String?)

class BackendException(val error: BackendError, val requestId: String? = null) : Exception(error.toString())
