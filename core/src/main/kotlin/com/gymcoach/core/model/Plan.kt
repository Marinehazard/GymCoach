// core/src/main/kotlin/com/gymcoach/core/model/Plan.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class PlannedSlot(
    @SerialName("slot_id") val slotId: String,
    val role: SlotRole,
    val pattern: MovementPattern,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("option_index") val optionIndex: Int,                 // which equipment option matched in the plan gym
    val sets: Int,
    val heavy: Boolean,
    @SerialName("superset_group") val supersetGroup: String? = null,
    val explanation: Explanation,
)

@Serializable
data class CardioPrescription(
    @SerialName("exercise_id") val exerciseId: String,                 // R4 cardio exercise id, e.g. "stationary_bike"
    val minutes: Int,
    val kind: CardioKind,
    @SerialName("hr_low") val hrLow: Int? = null,
    @SerialName("hr_high") val hrHigh: Int? = null,
    @SerialName("rpe_low") val rpeLow: Int? = null,                    // Borg 6-20
    @SerialName("rpe_high") val rpeHigh: Int? = null,
    val intervals: Int? = null,
    val placement: CardioPlacement,
    val weekday: Int,                                                  // ISO 1..7
    val explanation: Explanation,
)

@Serializable
data class PlannedSession(
    val id: String,                                                    // "<mesoId>:w<k>:d<isoWeekday>:<templateId>" — deterministic (HR-18); the weekday keeps it unique when a template repeats inside a week (ppl_6, ul_6; §4.16 #18)
    @SerialName("template_id") val templateId: String,
    val name: String,
    val weekday: Int,                                                  // ISO 1..7
    val slots: List<PlannedSlot>,
    @SerialName("estimated_minutes") val estimatedMinutes: Double,
    @SerialName("volume_fractional") val volumeFractional: Map<Muscle, Double>,
    @SerialName("volume_direct") val volumeDirect: Map<Muscle, Int>,
)

@Serializable
data class MesoWeek(
    val index: Int,                                                    // 1-based within the mesocycle
    @SerialName("start_date") val startDate: LocalDate,
    val mode: WeekMode,
    @SerialName("rir_offset") val rirOffset: Int,                      // RIR-04: +1 week 1, 0 middle, -1 last; DELOAD uses fixed RIR
    val targets: Map<Muscle, Double>,                                  // fractional sets/week (§4.4)
    val sessions: List<PlannedSession>,
    val cardio: List<CardioPrescription> = emptyList(),
    val notes: List<Explanation> = emptyList(),
)

@Serializable
data class Mesocycle(
    val id: String,                                                    // "meso-" + inputsHash.take(16) — deterministic, never IdGen (HR-18)
    @SerialName("start_date") val startDate: LocalDate,
    val goal: Goal,
    @SerialName("training_age") val trainingAge: TrainingAge,
    val split: SplitKind,
    @SerialName("gym_id") val gymId: String,                           // the gym the plan was built for
    val weeks: List<MesoWeek>,
    @SerialName("deload_week_index") val deloadWeekIndex: Int? = null,
    @SerialName("inputs_hash") val inputsHash: String,
    @SerialName("under_target_report") val underTargetReport: List<Explanation> = emptyList(),
    val explanations: List<Explanation> = emptyList(),
    @SerialName("schema_version") val schemaVersion: Int = 1,
)

@Serializable
data class DrillPrescription(
    @SerialName("drill_id") val drillId: String,
    val sets: Int,
    val reps: Band? = null,
    val seconds: Band? = null,
    val explanation: Explanation,
)

@Serializable
data class Warmup(
    @SerialName("raise_minutes") val raiseMinutes: Int,                // WU-01; 0 when trimmed (WU-05)
    @SerialName("raise_exercise_id") val raiseExerciseId: String? = null,
    val drills: List<DrillPrescription>,                               // MOB-01 dynamic drills 2×8–10
    @SerialName("total_minutes") val totalMinutes: Int,
    val explanation: Explanation,
)

@Serializable
data class SetPrescription(
    val index: Int,
    val kind: SetKind,
    val reps: Band? = null,
    val seconds: Band? = null,
    val metres: Band? = null,
    @SerialName("load_kg") val loadKg: Double? = null,                 // rounded by LoadRounder
    @SerialName("load_label") val loadLabel: String? = null,           // "2×22 kg", "band: heavy", "BW+10 kg", "stack 45 kg"
    @SerialName("per_hand") val perHand: Boolean = false,
    val rir: Int? = null,
    @SerialName("rest_seconds") val restSeconds: Int,
    @SerialName("load_source") val loadSource: LoadSource = LoadSource.NONE,
)

@Serializable
data class ExerciseBlock(
    @SerialName("block_id") val blockId: String,                      // slotId + ":" + exerciseId — deterministic (HR-18); a free-session block uses "free<n>:" + exerciseId
    @SerialName("slot_id") val slotId: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("option_index") val optionIndex: Int,
    @SerialName("effective_load_type") val effectiveLoadType: LoadType,
    @SerialName("substituted_for") val substitutedFor: String? = null,
    @SerialName("slot_role") val slotRole: SlotRole,
    val order: Int,
    val sets: List<SetPrescription>,
    @SerialName("superset_group") val supersetGroup: String? = null,
    @SerialName("progression_key") val progressionKey: ProgressionKey,
    val warnings: List<Explanation> = emptyList(),                     // dumbbell max exceeded, no safeties, …
    val explanation: Explanation,
)

@Serializable
data class VolumeSnapshot(val fractional: Map<Muscle, Double>, val direct: Map<Muscle, Int>)

/** Today's realized session (Stage 6). Frozen into SessionRecord.plan when the session starts. */
@Serializable
data class SessionPlan(
    val id: String,                                                    // (plannedSessionId ?: "free") + "@" + date.toString() — deterministic (HR-18)
    @SerialName("planned_session_id") val plannedSessionId: String?,
    @SerialName("template_id") val templateId: String,
    val name: String,
    val date: LocalDate,
    @SerialName("gym_id") val gymId: String,
    val mode: SessionMode,
    @SerialName("week_index") val weekIndex: Int,
    @SerialName("readiness_score") val readinessScore: Int? = null,
    val warmup: Warmup,
    val blocks: List<ExerciseBlock>,
    val cardio: CardioPrescription? = null,
    val cooldown: List<DrillPrescription> = emptyList(),
    val modifiers: List<Explanation>,                                  // every step of §4.7 that changed something
    @SerialName("estimated_minutes") val estimatedMinutes: Double,
    val volume: VolumeSnapshot,
    @SerialName("ledger_carryover") val ledgerCarryover: Map<Muscle, Double> = emptyMap(),
    @SerialName("alternative_offered") val alternativeOffered: Boolean = false,   // Red day: Z2 + mobility alternative
    @SerialName("schema_version") val schemaVersion: Int = 1,           // §7.2 DocVersions ("SessionPlan"; stored as sessions.plan_json)
)
