package com.gymcoach.app.network.claude

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.repo.BackendError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * HTTP status × error body → [BackendError] (§6.10 table, R1 §3.9). Pure; JVM-tested
 * (HttpErrorMapperTest). Error bodies have the shape
 * `{"type":"error","error":{"type":"…","message":"…","details":{"error_code":"…"}},"request_id":"…"}`.
 * Only the server's own message text is carried — never request headers, never the key.
 */
object HttpErrorMapper {
    const val SPEND_CAP_ERROR_CODE: String = "enforced_spend_limit_reached"
    const val OVERLOADED_ERROR: String = "overloaded_error"

    /** Parsed `error` object of an API error body; every field null when the body is not the documented JSON. */
    data class ApiError(val type: String?, val message: String?, val errorCode: String?)

    fun parseErrorBody(body: String?): ApiError {
        if (body.isNullOrBlank()) return ApiError(null, null, null)
        val root = try {
            CoreJson.wire.parseToJsonElement(body)
        } catch (e: IllegalArgumentException) {   // SerializationException included
            return ApiError(null, null, null)
        }
        val error = (root as? JsonObject)?.get("error") as? JsonObject ?: return ApiError(null, null, null)
        val details = error["details"] as? JsonObject
        return ApiError(
            type = error.string("type"),
            message = error.string("message"),
            errorCode = details?.string("error_code"),
        )
    }

    /**
     * `retry-after` header → whole seconds (rounded up), or null when absent/unparseable/negative.
     * The API sends delta-seconds; a fractional value is tolerated.
     */
    fun parseRetryAfter(value: String?): Int? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toIntOrNull()?.let { return if (it >= 0) it else null }
        val d = v.toDoubleOrNull() ?: return null
        if (d.isNaN() || d < 0.0) return null
        return kotlin.math.ceil(d).toInt()
    }

    /** Maps a non-2xx response. [body] is the response body text (may be null/empty), [retryAfterHeader] the raw `retry-after` header. */
    fun map(status: Int, body: String?, retryAfterHeader: String?): BackendError {
        val err = parseErrorBody(body)
        val message = err.message ?: err.type ?: "HTTP $status"
        return when (status) {
            400 -> BackendError.InvalidRequest(400, message)
            401 -> BackendError.Authentication
            402 -> BackendError.Billing
            403 -> BackendError.Permission(message)
            404 -> BackendError.InvalidRequest(404, message)
            408 -> BackendError.ServerError(408, message)
            409 -> BackendError.ServerError(409, message)
            413 -> BackendError.InvalidRequest(413, message)
            429 -> if (err.errorCode == SPEND_CAP_ERROR_CODE) BackendError.SpendCapReached else BackendError.RateLimited(parseRetryAfter(retryAfterHeader))
            529 -> BackendError.Overloaded(midStream = false)
            else -> when {
                status in 500..599 -> if (err.type == OVERLOADED_ERROR) BackendError.Overloaded(midStream = false) else BackendError.ServerError(status, message)
                status in 400..499 -> BackendError.InvalidRequest(status, message)
                else -> BackendError.Unknown("HTTP $status: $message")
            }
        }
    }

    /** A Messages-API `event: error` payload received mid-stream (`{"type":"error","error":{…}}`). */
    fun fromStreamEvent(event: JsonObject): BackendError {
        val error = event["error"] as? JsonObject
        val type = error?.string("type") ?: "unknown_error"
        val message = error?.string("message") ?: type
        return if (type == OVERLOADED_ERROR) BackendError.Overloaded(midStream = true) else BackendError.StreamError(type, message)
    }

    private fun JsonObject.string(key: String): String? {
        val e: JsonElement = this[key] ?: return null
        val p = e as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }
}
