// core/src/main/kotlin/com/gymcoach/core/repo/BackendError.kt
package com.gymcoach.core.repo

/** Every failure class the UI must handle (R1 §3.8/§3.9); copy and retry verdicts in §6.10. */
sealed class BackendError {
    object NotConfigured : BackendError()
    object Offline : BackendError()
    data class Transport(val message: String, val partialOutput: Boolean) : BackendError()
    object Authentication : BackendError()                                   // 401
    object Billing : BackendError()                                          // 402
    data class Permission(val message: String) : BackendError()              // 403
    data class InvalidRequest(val status: Int, val message: String) : BackendError()   // 400 / 404 / 413
    data class RateLimited(val retryAfterSec: Int?) : BackendError()         // 429 with retry-after
    object SpendCapReached : BackendError()                                  // 429 enforced_spend_limit_reached
    data class ServerError(val status: Int, val message: String) : BackendError()   // 500 / 504
    data class Overloaded(val midStream: Boolean) : BackendError()           // 529 or event: error overloaded_error
    data class StreamError(val type: String, val message: String) : BackendError()   // other event: error
    data class BridgeUnavailable(val reason: String) : BackendError()
    data class Unknown(val message: String) : BackendError()
}
