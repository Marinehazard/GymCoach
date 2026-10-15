// core/src/main/kotlin/com/gymcoach/core/model/Settings.kt
package com.gymcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    @SerialName("unit_system") val unitSystem: UnitSystem = UnitSystem.METRIC,
    @SerialName("backend_mode") val backendMode: BackendMode = BackendMode.NONE,
    @SerialName("model_id") val modelId: String = "claude-opus-5",
    val effort: Effort = Effort.LOW,
    @SerialName("monthly_budget_usd") val monthlyBudgetUsd: Double = 30.0,
    @SerialName("bridge_url") val bridgeUrl: String = "",
    @SerialName("show_experimental") val showExperimental: Boolean = false,
    @SerialName("onboarding_done") val onboardingDone: Boolean = false,
    @SerialName("week_starts_on_monday") val weekStartsOnMonday: Boolean = true,
    @SerialName("planner_overrides_json") val plannerOverridesJson: String = "{}",   // Settings ▸ Advanced (§4.1): nested by group, e.g. {"vol":{"vol04Novice":[8,12]}}
    @SerialName("coach_user_id") val coachUserId: String = "",                   // opaque UUID for metadata.user_id; SettingsDataStore fills it on first read (§7.3)
    @SerialName("readiness_reminder_time") val readinessReminderTime: String = "07:30",   // v1.1 notification; v1 in-app card
    @SerialName("weekly_checkin_weekday") val weeklyCheckinWeekday: Int = 7,     // ISO Sunday
    @SerialName("keep_screen_on_in_session") val keepScreenOnInSession: Boolean = true,
    @SerialName("last_week_rollover") val lastWeekRollover: String = "",         // ISO date
    @SerialName("catalog_version_seen") val catalogVersionSeen: Int = 0,
    @SerialName("mesocycle_anchor") val mesocycleAnchor: String = "",           // ISO date of the current mesocycle start ("" = none yet)
    @SerialName("mesocycle_week_paused_at") val mesocycleWeekPausedAt: Int = 0, // week index paused by TRAVEL_MAINTENANCE (0 = not paused)
    @SerialName("mesocycles_completed") val mesocyclesCompleted: Int = 0,       // PlanCoordinator increments when a mesocycle ends (§4.4 DLD-01 novice rule)
    @SerialName("last_deload_week_start") val lastDeloadWeekStart: String = "", // ISO Monday of the last DELOAD week ("" = none); §4.11 two-week guard
    @SerialName("deload_triggers_proposed") val deloadTriggersProposed: String = "[]",   // JSON list of "<kind>@<onset>" (§4.11); PlanCoordinator maintains it
    @SerialName("active_gym_id") val activeGymId: String = "",                  // gym chosen on Today ("" = the default gym); §8.6, ToolExecutor default
    @SerialName("today_context_json") val todayContextJson: String = "",        // TodayContext of the current date (extras/removed/spotter/minutes), cleared when the date changes; survives process death on Today
    @SerialName("session_draft_json") val sessionDraftJson: String = "",        // unlogged stepper state of the active session (§8.8 RECOVERED); "" = none
    @SerialName("onboarding_draft_json") val onboardingDraftJson: String = "",  // rotation/kill-safe onboarding state (§8.5); "" = none
)
