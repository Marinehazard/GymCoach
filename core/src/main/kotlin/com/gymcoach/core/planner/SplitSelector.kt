package com.gymcoach.core.planner

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.SessionTemplates
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.SessionTemplate
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.SplitTemplate
import com.gymcoach.core.model.TrainingAge

/** One session of the week: template on an ISO weekday. */
data class ScheduledTemplate(val weekday: Int, val template: SessionTemplate)

data class SplitChoice(val row: SplitTemplate, val schedule: List<ScheduledTemplate>, val explanations: List<Explanation>)

/**
 * Stage 2 (§4.5, SPLIT-01/02, FRQ-01/02): data-driven row selection — the first `kb.splits` row with the requested days, the
 * goal in `for_goals` (empty = any) and the training age in `for_training_ages` (empty = any); `travel_2` is never chosen by
 * the walk. Weekdays: `preferredWeekdays` when their count equals the days, else the row defaults. SPLIT-02 rotates the
 * template order so that no two consecutive days share a heavy slot for the same muscle group.
 */
class SplitSelector(private val kb: KnowledgeBase) {

    fun row(daysPerWeek: Int, goal: Goal, trainingAge: TrainingAge): SplitTemplate? =
        SessionTemplates.applicableSplits(kb, daysPerWeek, goal, trainingAge).firstOrNull()

    /** The fallback chain of §4.5: the requested row, then the applicable row with one day fewer, … (FRQ-03). */
    fun chain(daysPerWeek: Int, goal: Goal, trainingAge: TrainingAge): List<SplitTemplate> {
        val out = ArrayList<SplitTemplate>()
        var d = daysPerWeek
        while (d >= 1) {
            for (r in SessionTemplates.applicableSplits(kb, d, goal, trainingAge)) {
                if (out.none { it === r }) out.add(r)
                // upper_lower_full_3 falls to full_body_3 before the 2-day row (§4.5).
                if (r.kind == SplitKind.UPPER_LOWER_FULL_3 && out.none { it.kind == SplitKind.FULL_BODY_3 }) {
                    kb.splits.firstOrNull { it.kind == SplitKind.FULL_BODY_3 }?.let { out.add(it) }
                }
            }
            d--
        }
        return out
    }

    fun travelRow(): SplitTemplate? = kb.splits.firstOrNull { it.kind == SplitKind.TRAVEL_2 }

    fun choose(row: SplitTemplate, preferredWeekdays: List<Int>): SplitChoice {
        val notes = ArrayList<Explanation>()
        val templates = SessionTemplates.templatesOf(kb, row)
        val preferred = preferredWeekdays.filter { it in 1..7 }.distinct().sorted()
        val weekdays = if (preferred.size == row.daysPerWeek) preferred else row.defaultWeekdays
        notes.add(
            Explanations.of(
                listOf(RuleIds.SPLIT_01),
                "${row.daysPerWeek} days/week → ${row.kind.name.lowercase()} (${templates.joinToString(", ") { it.id }}).",
                mapOf("split" to row.kind.name.lowercase(), "weekdays" to weekdays.joinToString(",")),
                Confidence.WEAK,
            ),
        )
        when (row.kind) {
            SplitKind.FULL_BODY_1 -> notes.add(Explanations.of(RuleIds.FRQ_01, "One session per week: ≥ 2 days per week are recommended.", "days" to "1", confidence = Confidence.MODERATE))
            SplitKind.FULL_BODY_2, SplitKind.FULL_BODY_3, SplitKind.UPPER_LOWER_FULL_3, SplitKind.UPPER_LOWER_4,
            SplitKind.UL_PPL_5, SplitKind.PPL_6, SplitKind.UL_6, SplitKind.TRAVEL_2 -> Unit
        }
        var best: List<ScheduledTemplate>? = null
        for (rot in templates.indices) {
            val order = templates.drop(rot) + templates.take(rot)
            val schedule = weekdays.indices.map { ScheduledTemplate(weekdays[it], order[it % order.size]) }
            if (best == null) best = schedule
            if (conflicts(schedule).isEmpty()) {
                best = schedule
                if (rot > 0) notes.add(Explanations.of(RuleIds.SPLIT_02, "Template order rotated so heavy days for the same muscles are not back to back.", "rotation" to rot.toString(), confidence = Confidence.WEAK))
                return SplitChoice(row, schedule, notes)
            }
        }
        val chosen = checkNotNull(best) { "split ${row.kind} has no templates" }
        if (row.daysPerWeek <= 5 && conflicts(chosen).isNotEmpty()) {
            notes.add(Explanations.of(RuleIds.SPLIT_02, "No template order avoids heavy work for the same muscles on consecutive days.", "conflicts" to conflicts(chosen).joinToString(","), confidence = Confidence.WEAK))
        }
        return SplitChoice(row, chosen, notes)
    }

    /** Consecutive-day pairs (incl. Sunday → Monday) whose templates share a heavy-slot muscle group (SPLIT-02). */
    fun conflicts(schedule: List<ScheduledTemplate>): List<String> {
        val out = ArrayList<String>()
        val byDay = schedule.associateBy { it.weekday }
        for (s in schedule) {
            val next = byDay[if (s.weekday == 7) 1 else s.weekday + 1] ?: continue
            if (next === s) continue
            val shared = heavyGroups(s.template).intersect(heavyGroups(next.template))
            if (shared.isNotEmpty()) out.add("${s.weekday}-${next.weekday}:${shared.sorted().joinToString("+")}")
        }
        return out
    }

    companion object {
        /** SPLIT-02 muscle groups of a heavy slot's patterns. */
        fun groupOf(p: MovementPattern): String? = when (p) {
            MovementPattern.SQUAT, MovementPattern.LUNGE_SPLIT, MovementPattern.HINGE -> "legs"
            MovementPattern.HORIZONTAL_PUSH, MovementPattern.INCLINE_PUSH, MovementPattern.VERTICAL_PUSH -> "push"
            MovementPattern.HORIZONTAL_PULL, MovementPattern.VERTICAL_PULL -> "pull"
            else -> null
        }

        fun heavyGroups(t: SessionTemplate): Set<String> =
            t.slots.filter { it.heavy }.flatMap { s -> s.patterns.take(1).mapNotNull { groupOf(it) } }.toSet()
    }
}
