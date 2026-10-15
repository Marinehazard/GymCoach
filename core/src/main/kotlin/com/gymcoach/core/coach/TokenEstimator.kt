// core/src/main/kotlin/com/gymcoach/core/coach/TokenEstimator.kt
package com.gymcoach.core.coach

/** §6.11: ≈ 4 characters per token (rounded up). Real counts come from `usage` after the first call. */
object TokenEstimator {
    fun estimate(text: String): Int = (text.length + 3) / 4
}
