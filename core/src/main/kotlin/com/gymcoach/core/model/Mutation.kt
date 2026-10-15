// core/src/main/kotlin/com/gymcoach/core/model/Mutation.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Closed set of things the user or the coach can change. Each maps 1:1 onto a coach tool (§6.6). */
@Serializable
sealed class PlanMutation {
    abstract val rationale: String

    @Serializable @SerialName("swap_exercise")
    data class SwapExercise(
        @SerialName("from_exercise_id") val fromExerciseId: String,
        @SerialName("to_exercise_id") val toExerciseId: String,
        @SerialName("template_id") val templateId: String? = null,     // null = every session containing `from`
        val scope: MutationScope,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("change_volume")
    data class ChangeVolume(
        val muscle: Muscle,
        @SerialName("delta_sets_per_week") val deltaSetsPerWeek: Int,   // validated −6..+6
        val scope: MutationScope,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("change_rep_range")
    data class ChangeRepRange(
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("rep_lo") val repLo: Int,
        @SerialName("rep_hi") val repHi: Int,
        val scope: MutationScope,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("add_cardio")
    data class AddCardio(
        val weekday: Int,                                               // ISO 1..7
        val minutes: Int,
        @SerialName("exercise_id") val exerciseId: String? = null,      // null = planner picks (CAR-08)
        val kind: CardioKind,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("remove_cardio")
    data class RemoveCardio(val weekday: Int, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("change_goal")
    data class ChangeGoal(val goal: Goal, @SerialName("start_next_week") val startNextWeek: Boolean, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("change_schedule")
    data class ChangeSchedule(
        @SerialName("days_per_week") val daysPerWeek: Int? = null,
        @SerialName("session_minutes") val sessionMinutes: Int? = null,
        @SerialName("preferred_weekdays") val preferredWeekdays: List<Int>? = null,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("set_checkin_question")
    data class SetCheckinQuestion(
        val question: String,
        @SerialName("answer_type") val answerType: CheckinAnswerType,
        val schedule: CheckinSchedule,
        @SerialName("on_date") val onDate: LocalDate? = null,
        @SerialName("weeks_active") val weeksActive: Int = 4,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("exclude_exercise")
    data class ExcludeExercise(@SerialName("exercise_id") val exerciseId: String, val until: LocalDate? = null, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("pin_exercise")
    data class PinExercise(@SerialName("exercise_id") val exerciseId: String, @SerialName("template_id") val templateId: String? = null, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("adjust_working_load")
    data class AdjustWorkingLoad(
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("gym_scope") val gymScope: String? = null,
        @SerialName("new_working_load_kg") val newWorkingLoadKg: Double,
        override val rationale: String,
    ) : PlanMutation()

    @Serializable @SerialName("set_priority_muscles")
    data class SetPriorityMuscles(val muscles: List<Muscle>, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("schedule_deload")
    data class ScheduleDeload(@SerialName("start_this_week") val startThisWeek: Boolean, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("set_limitation")
    data class SetLimitation(val limitation: Limitation, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("set_travel_days")
    data class SetTravelDays(val from: LocalDate, val to: LocalDate, @SerialName("gym_id") val gymId: String? = null, override val rationale: String) : PlanMutation()

    @Serializable @SerialName("update_nutrition_targets")
    data class UpdateNutritionTargets(
        @SerialName("kcal_target") val kcalTarget: Int? = null,
        @SerialName("protein_g_per_kg") val proteinGPerKg: Double? = null,
        override val rationale: String,
    ) : PlanMutation()
}

/** Append-only. Revocation sets revokedAt; expiry is a date; later overrides win over earlier ones for the same target. */
@Serializable
data class PlanOverride(
    val id: String,
    @SerialName("created_at") val createdAt: Instant,
    val source: OverrideSource,
    @SerialName("proposal_id") val proposalId: String? = null,         // coach tool_use id when source == CHAT
    val mutation: PlanMutation,
    @SerialName("revoked_at") val revokedAt: Instant? = null,
    @SerialName("expires_on") val expiresOn: LocalDate? = null,        // TODAY-scope mutations: set to the day they apply (§6.8)
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)
