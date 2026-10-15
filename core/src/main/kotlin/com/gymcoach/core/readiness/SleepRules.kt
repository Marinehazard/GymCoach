// core/src/main/kotlin/com/gymcoach/core/readiness/SleepRules.kt
package com.gymcoach.core.readiness

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.planner.PlannerConstants
import java.time.temporal.ChronoUnit

data class SleepModifiers(val atLeastAmber: Boolean, val noMaxStrength: Boolean, val setFactor: Double, val heavyRirOffset: Int, val explanations: List<Explanation>)

/**
 * SLP-03 (Craven 2022): last night < slp03NightHours → at least Amber; that and the night before both < 6 h → no
 * MAX_STRENGTH sets; 7-day average < slp03WeeklyAvgHours → weekly sets × slp03SetFactor and RIR +slp03RirOffset on
 * heavy compounds. The 7-day average uses today's entry plus `last7` (one entry per date, the newest wins).
 */
class SleepRules(private val c: PlannerConstants) {

    fun evaluate(today: ReadinessCheckin?, last7: List<ReadinessCheckin>): SleepModifiers {
        val byDay = (last7 + listOfNotNull(today)).groupBy { it.date }
            .map { (_, v) -> v.maxByOrNull { it.loggedAt } ?: v.last() }
            .let { list -> if (today != null) list.filter { ChronoUnit.DAYS.between(it.date, today.date) in 0L..6L } else list }
            .sortedBy { it.date }
        val explanations = ArrayList<Explanation>()
        val night = c.rdy.slp03NightHours
        val lastNight = today?.sleepHours
        val atLeastAmber = lastNight != null && lastNight < night
        if (atLeastAmber) {
            explanations += Explanations.of(listOf(RuleIds.SLP_03), "Slept ${lastNight} h (< ${night} h): at least Amber today; morning training preferred",
                mapOf("sleep_hours" to lastNight.toString()), Confidence.MODERATE)
        }
        var noMax = false
        if (today != null && atLeastAmber) {
            val yesterday = byDay.firstOrNull { it.date == today.date.minusDays(1) }?.sleepHours
            if (yesterday != null && yesterday < night) {
                noMax = true
                explanations += Explanations.of(listOf(RuleIds.SLP_03), "Two nights in a row under ${night} h: no max-strength sets today",
                    mapOf("last_night" to lastNight.toString(), "night_before" to yesterday.toString()), Confidence.MODERATE)
            }
        }
        val hours = byDay.mapNotNull { it.sleepHours }
        val avg = if (hours.isEmpty()) null else hours.sum() / hours.size
        var factor = 1.0
        var rir = 0
        if (avg != null && avg < c.rdy.slp03WeeklyAvgHours) {
            factor = c.rdy.slp03SetFactor
            rir = c.rdy.slp03RirOffset
            explanations += Explanations.of(
                listOf(RuleIds.SLP_03),
                "7-day sleep average ${String.format(java.util.Locale.ROOT, "%.1f", avg)} h (< ${c.rdy.slp03WeeklyAvgHours} h): sets × ${c.rdy.slp03SetFactor}, RIR +${c.rdy.slp03RirOffset} on heavy compounds (Craven 2022)",
                mapOf("avg_sleep_hours" to String.format(java.util.Locale.ROOT, "%.2f", avg), "nights" to hours.size.toString()),
                Confidence.MODERATE,
            )
        }
        return SleepModifiers(atLeastAmber, noMax, factor, rir, explanations)
    }
}
