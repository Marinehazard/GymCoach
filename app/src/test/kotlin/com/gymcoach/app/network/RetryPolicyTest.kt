package com.gymcoach.app.network

import com.gymcoach.app.network.claude.RetryPolicy
import com.gymcoach.core.repo.BackendError
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every row of the §6.10 table. `retriesDone` = retries already made; delays 1 s then 4 s; never after output. */
class RetryPolicyTest {
    private val policy = RetryPolicy()
    private val giveUp = RetryPolicy.Decision.GiveUp
    private fun retry(ms: Long) = RetryPolicy.Decision.Retry(ms)

    private fun assertBackoffTwice(error: BackendError) {
        assertEquals(retry(1_000), policy.decide(error, 0, outputReceived = false))
        assertEquals(retry(4_000), policy.decide(error, 1, outputReceived = false))
        assertEquals(giveUp, policy.decide(error, 2, outputReceived = false))
        assertEquals(giveUp, policy.decide(error, 0, outputReceived = true))
    }

    @Test fun notConfigured_noRetry() = assertEquals(giveUp, policy.decide(BackendError.NotConfigured, 0, false))

    @Test fun offline_noRetry() = assertEquals(giveUp, policy.decide(BackendError.Offline, 0, false))

    @Test fun transport_noOutput_backoffTwice() = assertBackoffTwice(BackendError.Transport("timeout", partialOutput = false))

    @Test fun transport_partialOutput_never() {
        assertEquals(giveUp, policy.decide(BackendError.Transport("reset", partialOutput = true), 0, outputReceived = false))
        assertEquals(giveUp, policy.decide(BackendError.Transport("reset", partialOutput = true), 0, outputReceived = true))
    }

    @Test fun status400_never() = assertEquals(giveUp, policy.decide(BackendError.InvalidRequest(400, "bad"), 0, false))

    @Test fun status401_never() = assertEquals(giveUp, policy.decide(BackendError.Authentication, 0, false))

    @Test fun status402_never() = assertEquals(giveUp, policy.decide(BackendError.Billing, 0, false))

    @Test fun status403_never() = assertEquals(giveUp, policy.decide(BackendError.Permission("no"), 0, false))

    @Test fun status404_never() = assertEquals(giveUp, policy.decide(BackendError.InvalidRequest(404, "model"), 0, false))

    @Test fun status408_backoffTwice_onlyWithoutOutput() = assertBackoffTwice(BackendError.ServerError(408, "timeout"))

    @Test fun status409_backoffTwice_onlyWithoutOutput() = assertBackoffTwice(BackendError.ServerError(409, "conflict"))

    @Test fun status413_never() = assertEquals(giveUp, policy.decide(BackendError.InvalidRequest(413, "too large"), 0, false))

    @Test fun status429_retryAfter_waitOnce() {
        assertEquals(retry(12_000), policy.decide(BackendError.RateLimited(12), 0, false))
        assertEquals(giveUp, policy.decide(BackendError.RateLimited(12), 1, false))
        assertEquals(retry(30_000), policy.decide(BackendError.RateLimited(30), 0, false))
        assertEquals(retry(0), policy.decide(BackendError.RateLimited(0), 0, false))
    }

    @Test fun status429_retryAfterAbove30s_orMissing_never() {
        assertEquals(giveUp, policy.decide(BackendError.RateLimited(31), 0, false))
        assertEquals(giveUp, policy.decide(BackendError.RateLimited(null), 0, false))
        assertEquals(giveUp, policy.decide(BackendError.RateLimited(5), 0, outputReceived = true))
    }

    @Test fun spendCap_never() = assertEquals(giveUp, policy.decide(BackendError.SpendCapReached, 0, false))

    @Test fun status500_504_backoffTwice() {
        assertBackoffTwice(BackendError.ServerError(500, "api_error"))
        assertBackoffTwice(BackendError.ServerError(504, "timeout_error"))
    }

    @Test fun overloaded_529_andMidStream_backoffOnlyWithoutOutput() {
        assertBackoffTwice(BackendError.Overloaded(midStream = false))
        assertBackoffTwice(BackendError.Overloaded(midStream = true))
    }

    @Test fun otherStreamError_never() = assertEquals(giveUp, policy.decide(BackendError.StreamError("api_error", "boom"), 0, false))

    @Test fun bridgeUnavailable_never() = assertEquals(giveUp, policy.decide(BackendError.BridgeUnavailable("PC offline"), 0, false))

    @Test fun unknown_never() = assertEquals(giveUp, policy.decide(BackendError.Unknown("?"), 0, false))
}
