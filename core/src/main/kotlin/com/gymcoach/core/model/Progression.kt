// core/src/main/kotlin/com/gymcoach/core/model/Progression.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Progression is tracked per exercise; for MACHINE/CABLE/SMITH/SLED loads additionally per gym
 * (E1RM-04, TRV-04; decision record H6). gymScope is the GymSet id or null.
 */
@Serializable
data class ProgressionKey(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("gym_scope") val gymScope: String? = null,
    /** §3.4.1: a second track of the same exercise, e.g. "backoff" for the back-off sets after a top set (null = the main track). */
    val variant: String? = null,
) {
    val id: String get() = (if (variant == null) exerciseId else "$exerciseId#$variant") + (if (gymScope == null) "" else "@$gymScope")
    companion object {
        fun forExercise(exerciseId: String, effectiveLoadType: LoadType, gymId: String): ProgressionKey =
            ProgressionKey(exerciseId, if (effectiveLoadType.isGymScoped) gymId else null)
    }
}

@Serializable
data class E1rmEstimate(
    val kg: Double,
    @SerialName("from_weight_kg") val fromWeightKg: Double,
    @SerialName("from_reps") val fromReps: Int,
    @SerialName("from_rir") val fromRir: Int?,
    val confidence: E1rmConfidence,
    val date: LocalDate,
    @SerialName("formula_id") val formulaId: String = "E1RM-01",
)

@Serializable
data class SessionSummary(
    val date: LocalDate,
    @SerialName("load_kg") val loadKg: Double?,
    @SerialName("best_reps") val bestReps: Int,
    @SerialName("min_rir") val minRir: Int?,
    @SerialName("all_sets_at_hi") val allSetsAtHi: Boolean,
    val e1rm: E1rmEstimate? = null,
    @SerialName("load_x_reps") val loadXReps: Double? = null,            // machines/cables (E1RM-04)
    @SerialName("readiness_band") val readinessBand: ReadinessBand? = null,
    @SerialName("week_mode") val weekMode: WeekMode = WeekMode.NORMAL,
    val fair: Boolean = true,                                            // false for deload/re-entry/travel/capped/Red/external-cause sessions (§4.9.3)
)

@Serializable
data class ProgressionState(
    val key: ProgressionKey,
    val status: ProgressionStatus,
    @SerialName("working_load_kg") val workingLoadKg: Double? = null,
    @SerialName("band_level") val bandLevel: Int? = null,               // BAND_PROGRESSION
    @SerialName("working_reps") val workingReps: Band,
    @SerialName("rep_hi_base") val repHiBase: Int,                      // PROG-02 > 10 % rule (B graft)
    @SerialName("rep_hi_extended") val repHiExtended: Boolean = false,
    @SerialName("target_rir") val targetRir: Int,
    @SerialName("consecutive_hits") val consecutiveHits: Int = 0,       // R4 §5.4 two-session rule for coarse implements
    @SerialName("last_session_date") val lastSessionDate: LocalDate? = null,
    @SerialName("last_increment_date") val lastIncrementDate: LocalDate? = null,
    @SerialName("sessions_since_increment") val sessionsSinceIncrement: Int = 0,
    @SerialName("no_progress_count") val noProgressCount: Int = 0,      // PLAT-01
    @SerialName("best_e1rm_14d") val bestE1rm14d: Double? = null,        // E1RM-04
    @SerialName("best_e1rm_14d_date") val bestE1rm14dDate: LocalDate? = null,
    @SerialName("plateau_step") val plateauStep: Int = 0,               // PLAT-02 ladder position 0..5
    @SerialName("calibration_sessions") val calibrationSessions: Int = 0,
    @SerialName("rep_zone_shift") val repZoneShift: Int = 0,            // PLAT-02 step 2: +1 = one zone lighter
    val history: List<SessionSummary> = emptyList(),                    // last 8 sessions, newest first
    @SerialName("updated_at") val updatedAt: Instant,
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions
)
