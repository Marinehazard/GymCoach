package com.gymcoach.app.network.claude

import com.gymcoach.core.repo.BackendError

/**
 * The single normative retry rule set (§6.10 table; supersedes R1 §3.9 where they differ). Pure;
 * JVM-tested row by row (RetryPolicyTest).
 *
 * - Never retries once the request has produced output (a stream frame reached the caller).
 * - Transport (no output), 408, 409, 500/504 (and any other 5xx ServerError), 529 / overloaded:
 *   at most [maxRetries] (2) retries with exponential backoff 1 s, 4 s.
 * - 429 with `retry-after`: wait `retry-after` seconds once, only when ≤ [maxRetryAfterSec] (30 s).
 * - Never: 400, 401, 402, 403, 404, 413, spend-cap 429, 429 without `retry-after`, stream errors,
 *   NotConfigured, Offline, BridgeUnavailable, Unknown.
 */
class RetryPolicy(
    private val maxRetries: Int = 2,
    private val backoffMs: List<Long> = listOf(1_000L, 4_000L),
    private val maxRetryAfterSec: Int = 30,
) {
    sealed class Decision {
        data class Retry(val delayMs: Long) : Decision()
        object GiveUp : Decision()
    }

    /**
     * @param error the failure of the attempt that just ended
     * @param retriesDone how many retries were already made for this request (0 after the first attempt)
     * @param outputReceived true when any output of this request already reached the caller
     */
    fun decide(error: BackendError, retriesDone: Int, outputReceived: Boolean): Decision {
        if (outputReceived) return Decision.GiveUp
        return when (error) {
            is BackendError.Transport -> if (error.partialOutput) Decision.GiveUp else backoff(retriesDone)
            is BackendError.ServerError -> backoff(retriesDone)                   // 408 / 409 / 500 / 504 / other 5xx
            is BackendError.Overloaded -> backoff(retriesDone)                    // 529 or mid-stream overloaded before any output
            is BackendError.RateLimited -> {
                val sec = error.retryAfterSec
                if (retriesDone == 0 && sec != null && sec <= maxRetryAfterSec) Decision.Retry(sec * 1_000L) else Decision.GiveUp
            }
            BackendError.SpendCapReached,
            BackendError.Authentication,
            BackendError.Billing,
            BackendError.NotConfigured,
            BackendError.Offline,
            is BackendError.Permission,
            is BackendError.InvalidRequest,                                        // 400 / 404 / 413 (the service's 413 halving is not a transport retry)
            is BackendError.StreamError,
            is BackendError.BridgeUnavailable,
            is BackendError.Unknown -> Decision.GiveUp
        }
    }

    private fun backoff(retriesDone: Int): Decision =
        if (retriesDone < maxRetries) Decision.Retry(backoffMs.getOrElse(retriesDone) { backoffMs.last() }) else Decision.GiveUp
}
