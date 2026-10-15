// core/src/main/kotlin/com/gymcoach/core/coach/SpendMeter.kt
package com.gymcoach.core.coach

import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.SpendRecord
import com.gymcoach.core.model.TokenUsage
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.util.IdGen
import java.time.Instant

/**
 * §6.11: one [SpendRecord] per API response, priced with [PricingTable] ("estimated from token counts and list
 * prices"). HOME_BRIDGE responses are recorded with cost 0 — they are not billed per token (§6.1).
 */
class SpendMeter(private val coach: CoachRepository, private val clock: Clock) {

    suspend fun monthToDate(): Double {
        val today = clock.today()
        val start = today.withDayOfMonth(1).atStartOfDay(clock.zone()).toInstant()
        return coach.spendSince(start).sumOf { it.costUsd }
    }

    suspend fun record(model: String, usage: TokenUsage, backend: BackendMode, conversationId: String): SpendRecord {
        val cost = if (backend == BackendMode.HOME_BRIDGE) 0.0 else PricingTable.costUsd(model, usage)
        val record = SpendRecord(IdGen.newId(), clock.now(), model, usage, cost, backend, conversationId)
        coach.addSpend(record)
        return record
    }

    /** cache_read / total input over the records since [since]; null when nothing was sent. */
    suspend fun cacheHitRate(since: Instant): Double? {
        val records = coach.spendSince(since)
        val total = records.sumOf { it.usage.totalInput.toLong() }
        if (total <= 0L) return null
        return records.sumOf { it.usage.cacheReadInputTokens.toLong() }.toDouble() / total.toDouble()
    }
}
