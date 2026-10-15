package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.time.Weeks
import java.time.LocalDate
import java.time.ZoneOffset

data class LayoffModifier(val row: Int, val loadFactor: Double, val setFactor: Double?, val setDelta: Int, val rirOffset: Int, val noviceRestart: Boolean, val explanation: Explanation)

/**
 * §4.7 step 2 (LAY-01): a layoff is a break beyond the plan's own cadence — `excess = daysSince(lastTrained(pattern)) −
 * scheduledGapDays(pattern)`. Nothing applies below lay01MinExcessDays; the excess selects the row (5–13 / 14–28 / 29–56 /
 * > 56 or no record after ≥ 1 completed mesocycle); age ≥ lay01ConservativeAge is one row more conservative; a brand-new
 * user gets no modifier (find-load handles the start). Per-exercise rule: a pattern on cadence but an exercise whose own
 * excess is ≥ lay01ExerciseGapDays gets row 1 only [weak].
 */
class LayoffPolicy(private val kb: KnowledgeBase, private val c: PlannerConstants) {

    fun forBlock(pattern: MovementPattern, exerciseId: String, today: LocalDate, mesocycle: Mesocycle, inputs: PlanInputs, states: List<ProgressionState>, age: Int, trainingAge: TrainingAge): LayoffModifier? {
        val week = mesocycle.weeks.firstOrNull { !today.isBefore(it.startDate) && today.isBefore(it.startDate.plusDays(7)) }
        val gap = if (week != null && week.mode != WeekMode.TRAVEL_MAINTENANCE) scheduledGapDays(pattern, week) else scheduledGapDays(pattern, mesocycle)
        return forBlockWithGap(pattern, exerciseId, today, gap, inputs, states, age, trainingAge)
    }

    /** The planned interval between consecutive appearances of the pattern (7 for once weekly; the larger gap for twice weekly). */
    fun scheduledGapDays(pattern: MovementPattern, mesocycle: Mesocycle): Int {
        val week = mesocycle.weeks.firstOrNull { w -> w.mode != WeekMode.TRAVEL_MAINTENANCE && w.sessions.any { s -> s.slots.any { it.pattern == pattern && it.sets > 0 } } }
            ?: return DAYS_PER_WEEK
        return scheduledGapDays(pattern, week)
    }

    fun scheduledGapDays(pattern: MovementPattern, week: MesoWeek): Int {
        val days = week.sessions.filter { s -> s.slots.any { it.pattern == pattern && it.sets > 0 } }.map { it.weekday }.distinct().sorted()
        if (days.size <= 1) return DAYS_PER_WEEK
        var maxGap = DAYS_PER_WEEK - days.last() + days.first()
        for (i in 1 until days.size) maxGap = maxOf(maxGap, days[i] - days[i - 1])
        return maxGap
    }

    fun forBlockWithGap(pattern: MovementPattern, exerciseId: String, today: LocalDate, gapDays: Int, inputs: PlanInputs, states: List<ProgressionState>, age: Int, trainingAge: TrainingAge): LayoffModifier? {
        val last = lastTrained(pattern, inputs)
        val l = c.lay
        if (last == null) {
            if (inputs.mesocyclesCompleted == 0) return null
            return row(4, age, trainingAge, "no record of ${patternName(pattern)}", Int.MAX_VALUE)
        }
        val excess = (Weeks.daysBetween(last, today) - gapDays).toInt()
        if (excess >= l.lay01MinExcessDays) {
            val r = when {
                excess <= l.lay01Row1Days.hi -> 1
                excess <= l.lay01Row2Days.hi -> 2
                excess <= l.lay01Row3Days.hi -> 3
                else -> 4
            }
            return row(r, age, trainingAge, "${patternName(pattern)} last trained ${Weeks.daysBetween(last, today)} days ago ($excess days beyond the plan's cadence)", excess)
        }
        // Per-exercise rule [weak]: pattern on cadence, this exercise untouched for ≥ lay01ExerciseGapDays beyond its own interval.
        val own = states.filter { it.key.exerciseId == exerciseId }.mapNotNull { it.lastSessionDate }.maxOrNull() ?: return null
        val ownExcess = (Weeks.daysBetween(own, today) - gapDays).toInt()
        if (ownExcess >= l.lay01ExerciseGapDays) {
            val e = kb.exercise(exerciseId)
            return row(1, 0, trainingAge, "${e?.name ?: exerciseId} not done for ${Weeks.daysBetween(own, today)} days (pattern on cadence)", ownExcess, exerciseOnly = true)
        }
        return null
    }

    /** Newest of patternLastTrained and any logged set of the pattern in the recent window. */
    fun lastTrained(pattern: MovementPattern, inputs: PlanInputs): LocalDate? {
        var last: LocalDate? = inputs.patternLastTrained[pattern]
        val dates = inputs.recentSessions.associate { it.id to it.date }
        for (s in inputs.recentSets) {
            if (s.supersededById != null) continue
            val e = kb.exercise(s.exerciseId) ?: continue
            if (e.pattern != pattern) continue
            val d = dates[s.sessionId] ?: s.loggedAt.atZone(ZoneOffset.UTC).toLocalDate()
            if (last == null || d.isAfter(last)) last = d
        }
        return last
    }

    private fun row(base: Int, age: Int, trainingAge: TrainingAge, why: String, excess: Int, exerciseOnly: Boolean = false): LayoffModifier {
        val l = c.lay
        val r = if (!exerciseOnly && age >= l.lay01ConservativeAge) minOf(4, base + 1) else base
        val ruleIds = listOf(RuleIds.LAY_01) + (if (r == 4 && trainingAge == TrainingAge.NOVICE) listOf(RuleIds.PROG_05) else emptyList())
        val inputs = mapOf("row" to r.toString(), "excess_days" to (if (excess == Int.MAX_VALUE) "none" else excess.toString()), "age" to age.toString())
        return when (r) {
            1 -> LayoffModifier(1, l.lay01Row1Load, null, l.lay01Row1SetDelta, l.lay01Row1Rir, false,
                Explanations.of(ruleIds + RuleIds.LAY_03, "Layoff row 1: $why → loads × ${l.lay01Row1Load}, −1 set, RIR +${l.lay01Row1Rir}. Short breaks do not cost long-term progress.", inputs, Confidence.WEAK))
            2 -> LayoffModifier(2, l.lay01Row2Load, l.lay01Row2SetFactor, 0, l.lay01Row2Rir, false,
                Explanations.of(ruleIds, "Layoff row 2: $why → loads × ${l.lay01Row2Load}, sets × ${l.lay01Row2SetFactor}, RIR +${l.lay01Row2Rir}.", inputs, Confidence.WEAK))
            3 -> LayoffModifier(3, l.lay01Row3Load, l.lay01Row3SetFactor, 0, l.lay01Row3Rir, false,
                Explanations.of(ruleIds, "Layoff row 3: $why → loads × ${l.lay01Row3Load}, sets × ${l.lay01Row3SetFactor}, RIR +${l.lay01Row3Rir}.", inputs, Confidence.WEAK))
            else -> LayoffModifier(4, l.lay01Row4Load, null, 0, l.lay01Row4Rir.lo, trainingAge == TrainingAge.NOVICE,
                Explanations.of(ruleIds, "Layoff row 4: $why → loads × ${l.lay01Row4Load}, lower-bound sets, RIR ${l.lay01Row4Rir.lo}–${l.lay01Row4Rir.hi}.", inputs, Confidence.WEAK))
        }
    }

    private fun patternName(p: MovementPattern): String = p.name.lowercase().replace('_', ' ')

    companion object {
        const val DAYS_PER_WEEK: Int = 7
    }
}
