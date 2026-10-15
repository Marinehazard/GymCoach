package com.gymcoach.app.network

import com.gymcoach.app.network.claude.HttpErrorMapper
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.repo.BackendError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject

/** Status × body → BackendError for every §6.10 row, spend-cap detection and retry-after parsing. */
class HttpErrorMapperTest {
    private fun body(type: String, message: String, errorCode: String? = null): String {
        val details = if (errorCode != null) ",\"details\":{\"error_code\":\"$errorCode\"}" else ""
        return "{\"type\":\"error\",\"error\":{\"type\":\"$type\",\"message\":\"$message\"$details},\"request_id\":\"req_1\"}"
    }

    @Test fun status400_invalidRequest_withMessage() {
        assertEquals(
            BackendError.InvalidRequest(400, "You have reached your specified API usage limits"),
            HttpErrorMapper.map(400, body("invalid_request_error", "You have reached your specified API usage limits"), null),
        )
    }

    @Test fun status401_authentication() = assertEquals(BackendError.Authentication, HttpErrorMapper.map(401, body("authentication_error", "invalid x-api-key"), null))

    @Test fun status402_billing() = assertEquals(BackendError.Billing, HttpErrorMapper.map(402, body("billing_error", "credit balance too low"), null))

    @Test fun status403_permission() =
        assertEquals(BackendError.Permission("no access to model"), HttpErrorMapper.map(403, body("permission_error", "no access to model"), null))

    @Test fun status404_invalidRequest404() =
        assertEquals(BackendError.InvalidRequest(404, "model: claude-x"), HttpErrorMapper.map(404, body("not_found_error", "model: claude-x"), null))

    @Test fun status408_serverError408() =
        assertEquals(BackendError.ServerError(408, "Request timeout"), HttpErrorMapper.map(408, body("timeout_error", "Request timeout"), null))

    @Test fun status409_serverError409() =
        assertEquals(BackendError.ServerError(409, "conflict"), HttpErrorMapper.map(409, body("conflict_error", "conflict"), null))

    @Test fun status413_invalidRequest413() =
        assertEquals(BackendError.InvalidRequest(413, "Request exceeds the maximum size"), HttpErrorMapper.map(413, body("request_too_large", "Request exceeds the maximum size"), null))

    @Test fun status429_withRetryAfter_rateLimited() =
        assertEquals(BackendError.RateLimited(12), HttpErrorMapper.map(429, body("rate_limit_error", "slow down"), "12"))

    @Test fun status429_withoutRetryAfter_rateLimitedNull() =
        assertEquals(BackendError.RateLimited(null), HttpErrorMapper.map(429, body("rate_limit_error", "slow down"), null))

    @Test fun status429_spendCap_detectedFromErrorCode_evenWithRetryAfter() {
        assertEquals(BackendError.SpendCapReached, HttpErrorMapper.map(429, body("rate_limit_error", "spend limit", "enforced_spend_limit_reached"), null))
        assertEquals(BackendError.SpendCapReached, HttpErrorMapper.map(429, body("rate_limit_error", "spend limit", "enforced_spend_limit_reached"), "30"))
    }

    @Test fun status500_and504_serverError() {
        assertEquals(BackendError.ServerError(500, "Internal server error"), HttpErrorMapper.map(500, body("api_error", "Internal server error"), null))
        assertEquals(BackendError.ServerError(504, "timeout"), HttpErrorMapper.map(504, body("timeout_error", "timeout"), null))
    }

    @Test fun status529_overloaded_notMidStream() =
        assertEquals(BackendError.Overloaded(midStream = false), HttpErrorMapper.map(529, body("overloaded_error", "Overloaded"), null))

    @Test fun otherStatuses_fallBackByClass() {
        assertEquals(BackendError.ServerError(502, "HTTP 502"), HttpErrorMapper.map(502, "<html>bad gateway</html>", null))
        assertEquals(BackendError.InvalidRequest(422, "HTTP 422"), HttpErrorMapper.map(422, null, null))
        assertEquals(BackendError.Unknown("HTTP 302: HTTP 302"), HttpErrorMapper.map(302, "", null))
    }

    @Test fun malformedOrEmptyBody_usesStatusText() {
        assertEquals(BackendError.InvalidRequest(400, "HTTP 400"), HttpErrorMapper.map(400, "not json", null))
        assertEquals(BackendError.Permission("HTTP 403"), HttpErrorMapper.map(403, "", null))
        assertEquals(BackendError.Permission("permission_error"), HttpErrorMapper.map(403, "{\"type\":\"error\",\"error\":{\"type\":\"permission_error\"}}", null))
    }

    @Test fun retryAfter_parsing() {
        assertEquals(5, HttpErrorMapper.parseRetryAfter("5"))
        assertEquals(5, HttpErrorMapper.parseRetryAfter(" 5 "))
        assertEquals(2, HttpErrorMapper.parseRetryAfter("1.2"))
        assertEquals(0, HttpErrorMapper.parseRetryAfter("0"))
        assertNull(HttpErrorMapper.parseRetryAfter(null))
        assertNull(HttpErrorMapper.parseRetryAfter(""))
        assertNull(HttpErrorMapper.parseRetryAfter("-3"))
        assertNull(HttpErrorMapper.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test fun streamErrorEvents() {
        val overloaded = CoreJson.wire.parseToJsonElement("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}") as JsonObject
        assertEquals(BackendError.Overloaded(midStream = true), HttpErrorMapper.fromStreamEvent(overloaded))
        val other = CoreJson.wire.parseToJsonElement("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}") as JsonObject
        assertEquals(BackendError.StreamError("api_error", "boom"), HttpErrorMapper.fromStreamEvent(other))
    }

    @Test fun parseErrorBody_readsDetails() {
        val e = HttpErrorMapper.parseErrorBody(body("rate_limit_error", "m", "enforced_spend_limit_reached"))
        assertEquals("rate_limit_error", e.type)
        assertEquals("m", e.message)
        assertEquals("enforced_spend_limit_reached", e.errorCode)
    }
}
