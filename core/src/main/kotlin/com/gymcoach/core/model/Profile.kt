// core/src/main/kotlin/com/gymcoach/core/model/Profile.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** INJ-00: pain ≤ 3/10 acceptable (penalty + note); > 3 → regress/exclude. RED-02 uses `since`. */
@Serializable
data class Limitation(
    val id: String,
    val region: LimitationRegion,
    val side: Side = Side.BOTH,
    @SerialName("pain_0_10") val pain0to10: Int,
    val since: LocalDate,
    val active: Boolean = true,
    @SerialName("limited_range") val limitedRange: Boolean = false,   // MOB-02 chronic dose applies
    val notes: String = "",
)

@Serializable
data class Profile(
    val sex: Sex,
    @SerialName("birth_year") val birthYear: Int,
    val height: EnteredLength,
    @SerialName("training_months_consistent") val trainingMonthsConsistent: Int,   // DEF-01: months of ≥ 2 sessions/week
    @SerialName("layoff_months") val layoffMonths: Int = 0,                         // length of the most recent complete layoff
    @SerialName("months_retrained_since_layoff") val monthsRetrainedSinceLayoff: Int = 0,   // DEF-01 recency (H11)
    val goal: Goal,
    @SerialName("days_per_week") val daysPerWeek: Int,                              // 1..6
    @SerialName("session_minutes") val sessionMinutes: Int,                         // 30..120
    @SerialName("preferred_weekdays") val preferredWeekdays: List<Int> = emptyList(),   // ISO 1..7; empty = split defaults
    val limitations: List<Limitation> = emptyList(),
    @SerialName("medical_flags") val medicalFlags: Set<MedicalFlag> = emptySet(),
    @SerialName("clearance_confirmed_on") val clearanceConfirmedOn: LocalDate? = null,   // RED-03
    @SerialName("priority_muscles") val priorityMuscles: List<Muscle> = emptyList(),      // ≤ 3
    @SerialName("liked_exercise_ids") val likedExerciseIds: List<String> = emptyList(),
    @SerialName("disliked_exercise_ids") val dislikedExerciseIds: List<String> = emptyList(),
    @SerialName("known_loads_kg") val knownLoadsKg: Map<String, Double> = emptyMap(),   // exerciseId -> working load from onboarding
    @SerialName("allow_advanced_exercises") val allowAdvancedExercises: Boolean = false,   // R4 §5.3
    @SerialName("body_fat_pct") val bodyFatPct: Double? = null,                          // NUT-01/05 leanness gate
    @SerialName("physical_job") val physicalJob: Boolean = false,                        // NUT-04 activity factor
    @SerialName("activity_factor_override") val activityFactorOverride: Double? = null,  // NUT-04, user-set
    @SerialName("has_hr_monitor") val hasHrMonitor: Boolean = false,
    @SerialName("measured_hr_max") val measuredHrMax: Int? = null,                       // CAR-03: preferred over Tanaka
    @SerialName("wake_time_local") val wakeTimeLocal: String = "06:30",                  // "HH:mm", WU-06
    @SerialName("bedtime_local") val bedtimeLocal: String = "23:00",                     // "HH:mm", SUP-02
    @SerialName("usual_session_time") val usualSessionTime: String = "evening",          // "morning" | "midday" | "evening" (SLP-03)
    @SerialName("cardio_inside_session_minutes") val cardioInsideSessionMinutes: Boolean = false,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    fun ageOn(date: LocalDate): Int = date.year - birthYear
}

/** Morning metrics. `weight` is the sacred typed value; MA7 etc. are derived elsewhere (NUT-15). */
@Serializable
data class BodyMetric(
    val id: String,
    val date: LocalDate,
    val weight: EnteredWeight? = null,
    @SerialName("waist_cm") val waistCm: Double? = null,
    @SerialName("resting_hr") val restingHr: Int? = null,
    val steps: Int? = null,
    val note: String = "",
    @SerialName("logged_at") val loggedAt: Instant,
)
