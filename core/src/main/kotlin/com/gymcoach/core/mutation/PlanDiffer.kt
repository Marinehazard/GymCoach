package com.gymcoach.core.mutation

import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.PlanDiff
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.volume.VolumeAccountant

/**
 * §4.15 / §2.9: the diff returned to the coach after a proposal is applied. Sessions are matched across the two plans by
 * (week start, weekday, template) because session ids embed the inputs hash. The "current week" whose weekly fractional
 * sets are reported is the earliest week (by start date) whose sessions differ, else the first week of `after`.
 */
object PlanDiffer {

    fun between(before: Mesocycle, after: Mesocycle, warnings: List<String> = emptyList()): PlanDiff {
        val changed = ArrayList<String>()
        val beforeByKey = LinkedHashMap<String, PlannedSession>()
        for (w in before.weeks) for (s in w.sessions) beforeByKey[key(w, s)] = s
        val afterKeys = LinkedHashSet<String>()
        var firstDiffWeek: MesoWeek? = null
        for (w in after.weeks.sortedBy { it.startDate }) {
            for (s in w.sessions) {
                val k = key(w, s)
                afterKeys.add(k)
                val b = beforeByKey[k]
                if (b == null || signature(b) != signature(s)) {
                    changed.add(s.id)
                    if (firstDiffWeek == null) firstDiffWeek = w
                }
            }
        }
        for (w in before.weeks.sortedBy { it.startDate }) for (s in w.sessions) if (key(w, s) !in afterKeys) changed.add(s.id)
        val afterWeek = firstDiffWeek ?: after.weeks.minByOrNull { it.startDate }
        val beforeWeek = afterWeek?.let { aw -> before.weeks.firstOrNull { it.startDate == aw.startDate } } ?: before.weeks.minByOrNull { it.startDate }
        val beforeExercises = before.weeks.flatMap { w -> w.sessions.flatMap { s -> s.slots.map { it.exerciseId } } }.toSet()
        val afterExercises = after.weeks.flatMap { w -> w.sessions.flatMap { s -> s.slots.map { it.exerciseId } } }.toSet()
        return PlanDiff(
            sessionsChanged = changed.distinct(),
            weeklySetsBefore = beforeWeek?.let { weekly(it) } ?: emptyMap(),
            weeklySetsAfter = afterWeek?.let { weekly(it) } ?: emptyMap(),
            exercisesAdded = afterExercises.filter { it !in beforeExercises }.sorted(),
            exercisesRemoved = beforeExercises.filter { it !in afterExercises }.sorted(),
            warnings = warnings,
            underTargetReport = after.underTargetReport.map { it.message },
        )
    }

    private fun key(w: MesoWeek, s: PlannedSession): String = "${w.startDate}:${w.mode.name}:d${s.weekday}:${s.templateId}"

    private fun signature(s: PlannedSession): String = s.slots.joinToString("|") { "${it.slotId}=${it.exerciseId}x${it.sets}" }

    private fun weekly(w: MesoWeek): Map<Muscle, Double> {
        val arr = DoubleArray(Muscle.entries.size)
        for (s in w.sessions) for ((m, v) in s.volumeFractional) arr[m.ordinal] += v
        val out = LinkedHashMap<Muscle, Double>()
        for (m in Muscle.entries) if (arr[m.ordinal] > 0.0) out[m] = VolumeAccountant.round2(arr[m.ordinal])
        return out
    }
}
