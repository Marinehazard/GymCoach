// core/src/main/kotlin/com/gymcoach/core/progression/DeloadPolicy.kt
package com.gymcoach.core.progression

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.CheckinAnswer
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionSummary
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.planner.PlannerConstants
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One DLD-03 trigger with its onset (the day it became true) and resolution (first later day it was false); §4.11. */
data class DeloadTrigger(val ruleId: String, val kind: String, val onset: LocalDate, val resolved: LocalDate?, val detail: String)

data class DeloadSignals(val triggers: List<DeloadTrigger>) {
    /** Unresolved (as of `today`) and onset ≤ today − minDays. */
    fun active(today: LocalDate, minDays: Int): List<DeloadTrigger> =
        triggers.filter { t ->
            (t.resolved == null || t.resolved.isAfter(today)) && !t.onset.isAfter(today.minusDays(minDays.toLong()))
        }
}

/**
 * DLD-02/03/04 (§4.11). `signals` turns the inputs into dated triggers; `reactive` decides whether the Today card shows.
 * Never called by the planner (WP-03); PlanCoordinator (§7.6) is the single composition point.
 */
class DeloadPolicy(private val kb: KnowledgeBase, private val c: PlannerConstants) {

    fun signals(inputs: PlanInputs, states: List<ProgressionState>, today: LocalDate): DeloadSignals {
        val readiness = latestPerDay(inputs.readinessHistory.filter { !it.date.isAfter(today) })
        val checkins = inputs.weeklyCheckins.filter { !it.weekStart.isAfter(today) }.sortedBy { it.weekStart }
        val out = ArrayList<DeloadTrigger>()
        for (s in states.sortedBy { it.key.id }) regressing(s, today)?.let { out += it }
        windowEpisode(readiness, today, c.plat.dld03RedDaysOf7) { it.band == ReadinessBand.RED }?.let { (on, res) ->
            out += DeloadTrigger(RuleIds.DLD_03, KIND_RED_DAYS, on, res, "readiness Red on ≥ ${c.plat.dld03RedDaysOf7} of 7 days")
        }
        soreness(readiness)?.let { out += it }
        windowEpisode(readiness, today, c.plat.dld03SleepQualityNights) { q -> q.sleepQuality?.let { it <= c.plat.dld03SleepQualityLevel } ?: false }?.let { (on, res) ->
            out += DeloadTrigger(RuleIds.DLD_03, KIND_SLEEP_QUALITY, on, res, "sleep quality ≤ ${c.plat.dld03SleepQualityLevel} on ≥ ${c.plat.dld03SleepQualityNights} of 7 nights")
        }
        motivation(readiness, checkins, today)?.let { out += it }
        jointPain(readiness, checkins)?.let { out += it }
        return DeloadSignals(out)
    }

    /** Non-null when ≥ dld03MinTriggers kinds are active and at least one active "<kind>@<onset>" was never proposed. */
    fun reactive(signals: DeloadSignals, today: LocalDate, alreadyProposed: Set<String>): Explanation? {
        val active = signals.active(today, c.plat.dld03MinDays)
        val kinds = active.map { it.kind }.distinct()
        if (kinds.size < c.plat.dld03MinTriggers) return null
        if (active.all { key(it) in alreadyProposed }) return null
        val list = active.distinctBy { it.kind }.joinToString("; ") { "${it.detail} (since ${it.onset})" }
        return Explanations.of(
            listOf(RuleIds.DLD_03),
            "Deload this week? ${kinds.size} of $TRIGGER_KIND_COUNT triggers for ≥ ${c.plat.dld03MinDays} days: $list",
            mapOf(
                "triggers" to active.joinToString(",") { key(it) },
                "min_triggers" to c.plat.dld03MinTriggers.toString(),
                "min_days" to c.plat.dld03MinDays.toString(),
            ),
            Confidence.WEAK,
        )
    }

    fun scheduledExplanation(week: MesoWeek): Explanation = Explanations.of(
        listOf(RuleIds.DLD_02),
        "Deload week ${week.index}: sets × ${c.plat.dld02SetsFactor} (rounded up), compound loads × ${c.plat.dld02CompoundLoadFactor}, " +
            "machine/isolation loads unchanged, every set at RIR ${c.rir.rir04DeloadRir}; no increments",
        mapOf(
            "week_index" to week.index.toString(), "week_start" to week.startDate.toString(), "week_mode" to week.mode.name,
            "sets_factor" to c.plat.dld02SetsFactor.toString(), "compound_load_factor" to c.plat.dld02CompoundLoadFactor.toString(),
            "rir" to c.rir.rir04DeloadRir.toString(),
        ),
        Confidence.WEAK,
    )

    fun reentryExplanation(week: MesoWeek): Explanation = Explanations.of(
        listOf(RuleIds.DLD_04),
        "Re-entry week ${week.index}: loads × ${c.plat.dld04LoadFactor}, sets at the lower bound; the +${c.plat.dld04RirOffset} RIR is the usual " +
            "week-1 offset (not added twice); your working loads are kept",
        mapOf(
            "week_index" to week.index.toString(), "week_start" to week.startDate.toString(), "week_mode" to week.mode.name,
            "load_factor" to c.plat.dld04LoadFactor.toString(), "rir_offset" to c.plat.dld04RirOffset.toString(),
        ),
        Confidence.WEAK,
    )

    // ---------------------------------------------------------------------------------------------------------------

    /** History walk oldest → newest over fair NORMAL entries with a HIGH e1RM; onset = 2nd consecutive drop, resolved = first later recovery. */
    private fun regressing(s: ProgressionState, today: LocalDate): DeloadTrigger? {
        val entries = s.history.filter { it.fair && it.weekMode == WeekMode.NORMAL && it.e1rm?.confidence == E1rmConfidence.HIGH && !it.date.isAfter(today) }
            .sortedBy { it.date }
        var run = 0
        var onset: LocalDate? = null
        var resolved: LocalDate? = null
        for ((i, e) in entries.withIndex()) {
            val best = bestBefore(entries.subList(0, i), e.date) ?: continue
            val kg = e.e1rm?.kg ?: continue
            if (kg <= best * c.plat.plat03DropFactor + EPS) {
                run += 1
                if (run == c.plat.plat03Sessions && (onset == null || resolved != null)) { onset = e.date; resolved = null }
            } else {
                run = 0
                if (onset != null && resolved == null) resolved = e.date
            }
        }
        if (onset == null && s.status == ProgressionStatus.REGRESSING) onset = s.lastSessionDate ?: today
        val on = onset ?: return null
        return DeloadTrigger(RuleIds.DLD_03, KIND_REGRESSING, on, resolved, "${s.key.exerciseId}: e1RM down ≥ ${Math.round((1 - c.plat.plat03DropFactor) * 100)} % on two sessions")
    }

    private fun bestBefore(prior: List<SessionSummary>, date: LocalDate): Double? =
        prior.filter { ChronoUnit.DAYS.between(it.date, date) < c.prog.e1rm04RollingDays.toLong() }.mapNotNull { it.e1rm?.kg }.maxOrNull()

    /**
     * Rolling-7-day episode: onset = first day (an entry day) on which the trailing 7 days hold ≥ `needed` qualifying
     * entries; resolved = first later day on which they hold fewer. Only the newest episode is returned.
     */
    private fun windowEpisode(entries: List<ReadinessCheckin>, today: LocalDate, needed: Int, q: (ReadinessCheckin) -> Boolean): Pair<LocalDate, LocalDate?>? {
        if (entries.isEmpty()) return null
        val qualifying = entries.filter(q).map { it.date }.toSet()
        var day = entries.first().date
        var onset: LocalDate? = null
        var resolved: LocalDate? = null
        var on = false
        while (!day.isAfter(today)) {
            val from = day.minusDays(6)
            val count = qualifying.count { !it.isBefore(from) && !it.isAfter(day) }
            if (!on && count >= needed) { on = true; onset = day; resolved = null }
            else if (on && count < needed) { on = false; resolved = day }
            day = day.plusDays(1)
        }
        val o = onset ?: return null
        return o to resolved
    }

    /** Soreness ≥ level persisting > 72 h: onset = the first entry ≥ level that is ≥ 3 days after the run's first; resolved = first later entry < level. */
    private fun soreness(entries: List<ReadinessCheckin>): DeloadTrigger? {
        val level = c.plat.dld03SorenessLevel
        val days = (c.plat.dld03SorenessHours + 23) / 24
        var runStart: LocalDate? = null
        var onset: LocalDate? = null
        var resolved: LocalDate? = null
        var on = false
        for (e in entries) {
            val s = e.soreness ?: continue
            if (s >= level) {
                val start = runStart ?: e.date
                runStart = start
                if (!on && ChronoUnit.DAYS.between(start, e.date) >= days.toLong()) { on = true; onset = e.date; resolved = null }
            } else {
                runStart = null
                if (on) { on = false; resolved = e.date }
            }
        }
        val o = onset ?: return null
        return DeloadTrigger(RuleIds.DLD_03, KIND_SORENESS, o, resolved, "soreness ≥ $level for more than ${c.plat.dld03SorenessHours} h")
    }

    /** Daily motivation ≤ level on ≥ N of 7 days; else the newest run of weekly CHK Q11 ≤ level (onset = its first week). */
    private fun motivation(entries: List<ReadinessCheckin>, checkins: List<WeeklyCheckin>, today: LocalDate): DeloadTrigger? {
        val level = c.plat.dld03MotivationLevel
        val daily = windowEpisode(entries, today, c.plat.dld03MotivationDays) { r -> r.motivation?.let { it <= level } ?: false }
        if (daily != null && daily.second == null) {
            return DeloadTrigger(RuleIds.DLD_03, KIND_MOTIVATION, daily.first, null, "motivation ≤ $level on ≥ ${c.plat.dld03MotivationDays} of 7 days")
        }
        val events = checkins.mapNotNull { w -> answer(w, Q11)?.number?.let { w.weekStart to (it <= level) } }
        val weekly = episode(events)
        return when {
            weekly != null && weekly.second == null -> DeloadTrigger(RuleIds.DLD_03, KIND_MOTIVATION, weekly.first, null, "weekly check-in: motivation ≤ $level (Q11)")
            daily != null -> DeloadTrigger(RuleIds.DLD_03, KIND_MOTIVATION, daily.first, daily.second, "motivation ≤ $level on ≥ ${c.plat.dld03MotivationDays} of 7 days")
            weekly != null -> DeloadTrigger(RuleIds.DLD_03, KIND_MOTIVATION, weekly.first, weekly.second, "weekly check-in: motivation ≤ $level (Q11)")
            else -> null
        }
    }

    /** New joint pain ≥ dld03JointPain from readiness entries or CHK Q7 (yes); resolved by the first later entry below it. */
    private fun jointPain(entries: List<ReadinessCheckin>, checkins: List<WeeklyCheckin>): DeloadTrigger? {
        val events = ArrayList<Pair<LocalDate, Boolean>>()
        for (r in entries) events += r.date to (r.jointPain0to10 >= c.plat.dld03JointPain)
        for (w in checkins) {
            val a = answer(w, Q7) ?: continue
            events += w.weekStart to q7Yes(a)
        }
        val ep = episode(events.sortedBy { it.first }) ?: return null
        return DeloadTrigger(RuleIds.DLD_03, KIND_JOINT_PAIN, ep.first, ep.second, "joint pain ≥ ${c.plat.dld03JointPain}/10")
    }

    /** Newest episode of a dated boolean stream: onset = first true after a false (or the start), resolved = first false after it. */
    private fun episode(events: List<Pair<LocalDate, Boolean>>): Pair<LocalDate, LocalDate?>? {
        var onset: LocalDate? = null
        var resolved: LocalDate? = null
        var on = false
        for ((d, v) in events) {
            if (v && !on) { on = true; onset = d; resolved = null }
            else if (!v && on) { on = false; resolved = d }
        }
        val o = onset ?: return null
        return o to resolved
    }

    private fun latestPerDay(list: List<ReadinessCheckin>): List<ReadinessCheckin> =
        list.groupBy { it.date }.map { (_, v) -> v.maxByOrNull { it.loggedAt } ?: v.last() }.sortedBy { it.date }

    private fun answer(w: WeeklyCheckin, id: String): CheckinAnswer? = w.answers.lastOrNull { it.questionId == id }

    private fun key(t: DeloadTrigger): String = "${t.kind}@${t.onset}"

    companion object {
        const val KIND_REGRESSING: String = "regressing"
        const val KIND_RED_DAYS: String = "red_days"
        const val KIND_SORENESS: String = "soreness"
        const val KIND_SLEEP_QUALITY: String = "sleep_quality"
        const val KIND_MOTIVATION: String = "motivation"
        const val KIND_JOINT_PAIN: String = "joint_pain"
        const val TRIGGER_KIND_COUNT: Int = 6
        private const val Q7: String = "Q7"
        private const val Q11: String = "Q11"
        private const val EPS: Double = 1e-6

        /** CHK Q7 is a text question ("which joint"): an explicit yes, or a non-empty text that is not a plain "no". */
        fun q7Yes(a: CheckinAnswer): Boolean {
            if (a.yes != null) return a.yes
            val t = a.text?.trim()?.lowercase() ?: return false
            return t.isNotEmpty() && t !in setOf("no", "none", "nein", "-", "n", "0")
        }
    }
}
