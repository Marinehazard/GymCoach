// core/src/main/kotlin/com/gymcoach/core/model/PlanInputs.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Fully materialized planner input. :app builds it from Room/DataStore; tests build it from JSON fixtures. */
@Serializable
data class PlanInputs(
    val profile: Profile,
    val gyms: List<GymSet>,
    @SerialName("default_gym_id") val defaultGymId: String,
    val overrides: List<PlanOverride>,
    @SerialName("progression_states") val progressionStates: List<ProgressionState>,
    @SerialName("recent_sessions") val recentSessions: List<SessionRecord>,      // last 12 weeks
    @SerialName("recent_sets") val recentSets: List<SetLog>,                     // sets of recentSessions (non-superseded)
    @SerialName("body_metrics") val bodyMetrics: List<BodyMetric>,               // last 12 weeks
    @SerialName("weekly_checkins") val weeklyCheckins: List<WeeklyCheckin>,      // last 8
    @SerialName("readiness_history") val readinessHistory: List<ReadinessCheckin>,   // last 14 days
    @SerialName("mesocycle_anchor") val mesocycleAnchor: LocalDate? = null,      // start of the current mesocycle; null = start today
    @SerialName("week_of_mesocycle") val weekOfMesocycle: Int? = null,           // paused during TRAVEL_MAINTENANCE
    @SerialName("travel_weeks") val travelWeeks: List<LocalDate> = emptyList(),  // week-start dates in TRAVEL_MAINTENANCE mode
    @SerialName("week_starts_on_monday") val weekStartsOnMonday: Boolean = true,
    @SerialName("mesocycles_completed") val mesocyclesCompleted: Int = 0,        // from AppSettings; 0 = first mesocycle (DLD-01 novice rule, §4.4)
    @SerialName("last_deload_week_start") val lastDeloadWeekStart: LocalDate? = null,   // scheduled or reactive; §4.11 two-week guard
    @SerialName("deload_triggers_proposed") val deloadTriggersProposed: List<String> = emptyList(),   // "<kind>@<onset>" already turned into a proposal (§4.11)
    /** Every DLD-03 trigger active today as "<kind>@<onset>" — filled by PlanCoordinator.buildInputs() from DeloadPolicy.signals(...).active(today, c.plat.dld03MinDays) (§7.6); TargetPolicy holds every VOL-08 ramp while it is non-empty (§4.4). Empty in tests that do not exercise the hold. */
    @SerialName("deload_triggers_active") val deloadTriggersActive: List<String> = emptyList(),
    /** Last date any exercise of the pattern was logged (any gym), also beyond the 12-week window — LAY-01 (§4.7 step 2). Built by :app from WorkoutRepository.lastLoggedAtByExercise() + KnowledgeBase patterns. */
    @SerialName("pattern_last_trained") val patternLastTrained: Map<MovementPattern, LocalDate> = emptyMap(),
)

@Serializable
data class PlanOutput(
    val mesocycle: Mesocycle,
    @SerialName("current_week_index") val currentWeekIndex: Int,
    val explanations: List<Explanation>,
    val proposals: List<PlanMutation> = emptyList(),                             // PLANNER-source cards (deload, micro-session …)
)

@Serializable
data class SessionCloseResult(
    val states: List<ProgressionState>,
    val adjustments: List<Adjustment>,
    val flags: List<Explanation>,                                                // PLATEAU / REGRESSION flags
)

@Serializable
data class WeekRollResult(
    @SerialName("week_start") val weekStart: LocalDate,
    @SerialName("next_mode") val nextMode: WeekMode,
    @SerialName("volume_holds") val volumeHolds: List<Explanation>,              // VOL-08 holds
    @SerialName("deload_triggers") val deloadTriggers: List<Explanation>,        // DLD-03 — filled by PlanCoordinator from WP-04's DeloadPolicy (§7.6); ALWAYS empty when returned by Planner.rollWeek (WP-03 never calls DeloadPolicy)
    val proposals: List<PlanMutation>,                                           // PLAT-02 step 3/5 proposals from rollWeek; PlanCoordinator appends the reactive ScheduleDeload PLANNER card here (§4.11, §7.6)
    val states: List<ProgressionState> = emptyList(),                            // PLAT-02 ladder advances (plateauStep / repZoneShift written by rollWeek, §4.11); PlanCoordinator persists them via ProgressionRepository.saveAll (§7.6)
    @SerialName("ladder_notes") val ladderNotes: List<Explanation> = emptyList(), // one Explanation(PLAT-02) per advanced state (inputs: "exercise_id", "step"); PlanCoordinator records one Adjustment per entry (§7.6)
)

/** Returned to the coach in the tool_result after a proposal is applied (A graft). */
@Serializable
data class PlanDiff(
    @SerialName("sessions_changed") val sessionsChanged: List<String>,
    @SerialName("weekly_sets_before") val weeklySetsBefore: Map<Muscle, Double>,
    @SerialName("weekly_sets_after") val weeklySetsAfter: Map<Muscle, Double>,
    @SerialName("exercises_added") val exercisesAdded: List<String> = emptyList(),
    @SerialName("exercises_removed") val exercisesRemoved: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    @SerialName("under_target_report") val underTargetReport: List<String> = emptyList(),
)
