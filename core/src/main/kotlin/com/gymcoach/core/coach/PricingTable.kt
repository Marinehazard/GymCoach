// core/src/main/kotlin/com/gymcoach/core/coach/PricingTable.kt
package com.gymcoach.core.coach

import com.gymcoach.core.model.TokenUsage

/**
 * §6.11 list prices per million tokens (R1 §2.3, read 2026-09-04). Cache writes are priced at the 5-minute rate
 * (every breakpoint GymCoach sets is `ephemeral` without a ttl). Thinking tokens are inside `output_tokens`.
 * An unknown model id is priced as Opus 5 (the conservative, more expensive row).
 */
object PricingTable {
    data class Prices(val inputPerMTok: Double, val cacheWrite5mPerMTok: Double, val cacheWrite1hPerMTok: Double, val cacheReadPerMTok: Double, val outputPerMTok: Double)

    val OPUS_5: Prices = Prices(5.0, 6.25, 10.0, 0.50, 25.0)
    val SONNET_5: Prices = Prices(2.0, 2.50, 4.0, 0.20, 10.0)

    fun pricesFor(model: String): Prices = when {
        model.startsWith("claude-sonnet-5") -> SONNET_5
        model.startsWith("claude-opus-5") -> OPUS_5
        else -> OPUS_5
    }

    fun costUsd(model: String, usage: TokenUsage): Double {
        val p = pricesFor(model)
        return (usage.inputTokens * p.inputPerMTok +
            usage.cacheCreationInputTokens * p.cacheWrite5mPerMTok +
            usage.cacheReadInputTokens * p.cacheReadPerMTok +
            usage.outputTokens * p.outputPerMTok) / 1_000_000.0
    }
}
