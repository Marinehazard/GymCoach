package com.gymcoach.core.mutation

import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import java.time.LocalDate

/**
 * §4.3 / §4.15 active overrides: not revoked, not expired at `today` (an ExcludeExercise also ends after its `until`);
 * per target the later `createdAt` wins (ties: the larger id); ExcludeExercise beats PinExercise for the same exercise id.
 * The result is sorted by (createdAt, id) so that every consumer sees a deterministic order (HR-18).
 */
object OverrideResolver {

    fun active(overrides: List<PlanOverride>, today: LocalDate): List<PlanOverride> {
        val live = overrides.filter { o ->
            o.revokedAt == null &&
                (o.expiresOn == null || !o.expiresOn.isBefore(today)) &&
                when (val m = o.mutation) {
                    is PlanMutation.ExcludeExercise -> m.until == null || !m.until.isBefore(today)
                    is PlanMutation.SetTravelDays -> !m.to.isBefore(today)
                    else -> true
                }
        }
        val byTarget = LinkedHashMap<String, PlanOverride>()
        for (o in live.sortedWith(compareBy<PlanOverride>({ it.createdAt }, { it.id }))) {
            byTarget[targetKey(o)] = o   // later createdAt overwrites the earlier one
        }
        val winners = byTarget.values.toList()
        val excluded = winners.mapNotNull { (it.mutation as? PlanMutation.ExcludeExercise)?.exerciseId }.toHashSet()
        return winners
            .filter { o -> !(o.mutation is PlanMutation.PinExercise && (o.mutation as PlanMutation.PinExercise).exerciseId in excluded) }
            .sortedWith(compareBy<PlanOverride>({ it.createdAt }, { it.id }))
    }

    /** The conflict target of a mutation: two overrides with the same key cannot both be active. */
    fun targetKey(o: PlanOverride): String = when (val m = o.mutation) {
        is PlanMutation.SwapExercise -> "swap:${m.fromExerciseId}:${m.templateId ?: "*"}:${m.scope.name}"
        is PlanMutation.ChangeVolume -> "volume:${m.muscle.name}"
        is PlanMutation.ChangeRepRange -> "reps:${m.exerciseId}"
        is PlanMutation.AddCardio -> "cardio:${m.weekday}"
        is PlanMutation.RemoveCardio -> "cardio:${m.weekday}"
        is PlanMutation.ChangeGoal -> "goal"
        is PlanMutation.ChangeSchedule -> "schedule"
        is PlanMutation.SetCheckinQuestion -> "question:${o.id}"
        is PlanMutation.ExcludeExercise -> "exclude:${m.exerciseId}"
        is PlanMutation.PinExercise -> "pin:${m.templateId ?: "*"}"
        is PlanMutation.AdjustWorkingLoad -> "load:${m.exerciseId}:${m.gymScope ?: "*"}"
        is PlanMutation.SetPriorityMuscles -> "priority"
        is PlanMutation.ScheduleDeload -> "deload"
        is PlanMutation.SetLimitation -> "limitation:${m.limitation.id}"
        is PlanMutation.SetTravelDays -> "travel:${o.id}"
        is PlanMutation.UpdateNutritionTargets -> "nutrition"
    }
}
