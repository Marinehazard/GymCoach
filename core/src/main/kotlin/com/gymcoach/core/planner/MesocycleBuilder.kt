package com.gymcoach.core.planner

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.time.Weeks
import java.time.LocalDate
import java.time.ZoneOffset

/** One week of the skeleton (Stage 1). `travel` weeks pause the mesocycle counter (TRV-01) and keep the paused index. */
data class WeekSkeleton(val index: Int, val startDate: LocalDate, val mode: WeekMode, val rirOffset: Int) {
    val travel: Boolean get() = mode == WeekMode.TRAVEL_MAINTENANCE
}

data class Skeleton(
    val anchor: LocalDate,
    val accumulationWeeks: Int,
    val deloadWeekIndex: Int?,
    val weeks: List<WeekSkeleton>,
    val currentWeekStart: LocalDate,
    val currentWeekIndex: Int,
    val explanations: List<Explanation>,
) {
    val currentWeek: WeekSkeleton? get() = weeks.firstOrNull { it.startDate == currentWeekStart }
    fun weekAt(date: LocalDate): WeekSkeleton? = weeks.firstOrNull { !date.isBefore(it.startDate) && date.isBefore(it.startDate.plusDays(7)) }
}

/**
 * Stage 1 (§4.4): accumulation weeks and scheduled deload by training age (DLD-01 — NOVICE first mesocycle 12 weeks without
 * a scheduled deload, later NOVICE and INTERMEDIATE 6 + deload week 7, ADVANCED 5 + deload week 6), week modes and RIR
 * offsets (RIR-04: +1 in week 1, 0 in the middle, `rir04LastWeekOffset` in the last accumulation week; DELOAD fixed RIR),
 * REENTRY after a deload (DLD-04), TRAVEL_MAINTENANCE weeks inserted with the counter paused (TRV-01) and an accepted
 * reactive deload (ScheduleDeload override) that ends the mesocycle.
 */
class MesocycleBuilder(private val c: PlannerConstants) {

    fun accumulationWeeks(trainingAge: TrainingAge, firstMesocycle: Boolean): Pair<Int, Int?> = when (trainingAge) {
        TrainingAge.NOVICE -> if (firstMesocycle) c.plat.dld01NoviceWeeks to null else NON_NOVICE_ACCUMULATION to NON_NOVICE_ACCUMULATION + 1
        TrainingAge.INTERMEDIATE -> c.plat.dld01IntermediateWeeks to c.plat.dld01IntermediateWeeks + 1
        TrainingAge.ADVANCED -> c.plat.dld01AdvancedWeeks to c.plat.dld01AdvancedWeeks + 1
    }

    /** [accumulationOverride] (§3.4.1 personal split row `accumulation_weeks`): that many accumulation weeks, then the closing (test) week. */
    fun build(inputs: PlanInputs, n: Normalized, today: LocalDate, accumulationOverride: Int? = null): Skeleton {
        val monday = inputs.weekStartsOnMonday
        val todayWeek = Weeks.weekStart(today, monday)
        val notes = ArrayList<Explanation>()
        var (accumulation, deload) = if (accumulationOverride != null && accumulationOverride >= 1) accumulationOverride to accumulationOverride + 1
            else accumulationWeeks(n.trainingAge, n.firstMesocycle)
        var anchor = inputs.mesocycleAnchor?.let { Weeks.weekStart(it, monday) } ?: todayWeek
        if (anchor.isAfter(todayWeek)) anchor = todayWeek
        var travel = travelStarts(inputs, anchor, monday)

        // A reactive deload accepted by the user ends the mesocycle (§4.11).
        val reactive = n.activeOverrides.lastOrNull { it.mutation is PlanMutation.ScheduleDeload }?.let { o ->
            val m = o.mutation as PlanMutation.ScheduleDeload
            val created = Weeks.weekStart(o.createdAt.atZone(ZoneOffset.UTC).toLocalDate(), monday)
            if (m.startThisWeek) created else created.plusDays(7)
        }
        var total = deload ?: accumulation
        if (indexOf(todayWeek, anchor, travel) > total && (reactive == null || reactive.isBefore(anchor))) {
            // The stored anchor is stale: the next mesocycle starts this week (the app moves the anchor when it stores the snapshot).
            notes.add(Explanations.of(RuleIds.DLD_01, "A new mesocycle starts this week.", "previous_anchor" to anchor.toString(), confidence = Confidence.WEAK))
            anchor = todayWeek
            travel = travelStarts(inputs, anchor, monday)
        }
        var reactiveIndex: Int? = null
        if (reactive != null && !reactive.isBefore(anchor)) {
            val idx = indexOf(reactive, anchor, travel)
            if (idx >= 1) {
                reactiveIndex = idx
                deload = idx
                accumulation = maxOf(1, idx - 1)
                total = idx
                notes.add(Explanations.of(listOf(RuleIds.DLD_03, RuleIds.OVERRIDE), "Deload in week $idx (accepted); the mesocycle ends with it.", mapOf("week" to idx.toString(), "week_start" to reactive.toString()), Confidence.WEAK))
            }
        }
        if (deload == null) notes.add(Explanations.of(RuleIds.DLD_01, "First mesocycle as a novice: $accumulation weeks, no scheduled deload (reactive only).", "weeks" to accumulation.toString(), confidence = Confidence.MODERATE))
        else if (reactiveIndex == null && accumulationOverride != null && accumulationOverride >= 1) notes.add(Explanations.of(listOf(RuleIds.DLD_01, RuleIds.OVERRIDE), "$accumulation-week cycle (your split), then a test week (week $deload) with deload-dosed sessions.", mapOf("weeks" to accumulation.toString(), "test_week" to deload.toString()), Confidence.WEAK))
        else if (reactiveIndex == null) notes.add(Explanations.of(RuleIds.DLD_01, "$accumulation accumulation weeks, deload in week $deload.", "weeks" to accumulation.toString(), "deload_week" to deload.toString(), confidence = Confidence.MODERATE))

        val reentry = inputs.lastDeloadWeekStart?.let { Weeks.weekStart(it, monday) == anchor.minusDays(7) } ?: false
        val weeks = ArrayList<WeekSkeleton>()
        var date = anchor
        var idx = 1
        var guard = 0
        while (idx <= total && guard < MAX_WEEKS) {
            guard++
            if (date in travel) {
                weeks.add(WeekSkeleton(idx, date, WeekMode.TRAVEL_MAINTENANCE, 0))
                date = date.plusDays(7)
                continue
            }
            val mode = when {
                deload != null && idx == deload -> WeekMode.DELOAD
                idx == 1 && reentry -> WeekMode.REENTRY
                else -> WeekMode.NORMAL
            }
            val offset = when {
                mode == WeekMode.DELOAD -> 0
                idx == 1 -> c.rir.rir04Week1Offset
                idx == accumulation -> c.rir.rir04LastWeekOffset
                else -> 0
            }
            weeks.add(WeekSkeleton(idx, date, mode, offset))
            idx++
            date = date.plusDays(7)
        }
        if (reentry) notes.add(Explanations.of(listOf(RuleIds.DLD_04, RuleIds.RIR_04), "Week 1 after a deload: re-entry at 95 % loads, lower-bound sets, RIR +1 once.", mapOf("factor" to c.plat.dld04LoadFactor.toString()), Confidence.WEAK))
        val current = weeks.firstOrNull { it.startDate == todayWeek }
        val currentIndex = current?.index ?: indexOf(todayWeek, anchor, travel).coerceIn(1, total)
        return Skeleton(anchor, accumulation, deload, weeks, todayWeek, currentIndex, notes)
    }

    /** §4.4: floor(days(anchor, weekStart) / 7) + 1 − travel weeks strictly before that week. */
    fun indexOf(weekStart: LocalDate, anchor: LocalDate, travel: Set<LocalDate>): Int {
        val raw = Math.floorDiv(Weeks.daysBetween(anchor, weekStart), 7L).toInt() + 1
        return raw - travel.count { it.isBefore(weekStart) && !it.isBefore(anchor) }
    }

    private fun travelStarts(inputs: PlanInputs, anchor: LocalDate, monday: Boolean): Set<LocalDate> =
        inputs.travelWeeks.map { Weeks.weekStart(it, monday) }.filter { !it.isBefore(anchor) }.toSortedSet()

    companion object {
        /** DLD-01 for a NOVICE after the first mesocycle (the 12-week exemption is once): 6 accumulation weeks + deload. */
        const val NON_NOVICE_ACCUMULATION: Int = 6
        private const val MAX_WEEKS: Int = 64
    }
}
