// core/src/main/kotlin/com/gymcoach/core/model/Session.kt
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
 * A logged set. SACRED: written only by the logging UI through WorkoutRepository; never updated in
 * place — a correction inserts a new row and sets supersededById on the old one.
 */
@Serializable
data class SetLog(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("block_id") val blockId: String? = null,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("set_index") val setIndex: Int,
    val kind: SetKind,
    val weight: EnteredWeight? = null,
    @SerialName("per_hand") val perHand: Boolean = false,
    val reps: Int? = null,
    val seconds: Int? = null,
    val metres: Int? = null,
    val rir: Int? = null,
    val side: Side = Side.BOTH,
    @SerialName("prescribed_load_kg") val prescribedLoadKg: Double? = null,
    @SerialName("prescribed_reps") val prescribedReps: Band? = null,
    @SerialName("prescribed_rir") val prescribedRir: Int? = null,
    @SerialName("logged_at") val loggedAt: Instant,
    @SerialName("superseded_by_id") val supersededById: String? = null,
    @SerialName("supersedes_id") val supersedesId: String? = null,
    val note: String = "",
)

@Serializable
data class SessionRecord(
    val id: String,
    val date: LocalDate,
    @SerialName("gym_id") val gymId: String,
    @SerialName("planned_session_id") val plannedSessionId: String? = null,
    @SerialName("template_id") val templateId: String? = null,
    val plan: SessionPlan? = null,                                     // frozen at start
    val status: SessionStatus,
    @SerialName("started_at") val startedAt: Instant? = null,
    @SerialName("finished_at") val finishedAt: Instant? = null,
    @SerialName("session_rpe") val sessionRpe: Int? = null,            // RDY-06 (0-10)
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("readiness_checkin_id") val readinessCheckinId: String? = null,
    @SerialName("skip_reason") val skipReason: String? = null,
    val notes: String = "",
)

/** Persisted record of every automatic or user-confirmed change (POL-01). Append-only; undo sets undoneAt. */
@Serializable
data class Adjustment(
    val id: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("exercise_id") val exerciseId: String? = null,
    val kind: AdjustmentKind,
    @SerialName("rule_ids") val ruleIds: List<String>,
    val summary: String,                                               // "Load 80 → 82.5 kg (PROG-01: all sets hit 10 reps at ≤ 2 RIR)"
    val inputs: Map<String, String> = emptyMap(),
    @SerialName("before_json") val beforeJson: String,
    @SerialName("after_json") val afterJson: String,
    val source: OverrideSource,
    @SerialName("proposal_id") val proposalId: String? = null,
    @SerialName("override_id") val overrideId: String? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("undone_at") val undoneAt: Instant? = null,
)

@Serializable
data class CardioLog(
    val id: String,
    val date: LocalDate,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("exercise_id") val exerciseId: String,
    val kind: CardioKind,
    val minutes: Int,
    @SerialName("avg_hr") val avgHr: Int? = null,
    val rpe: Int? = null,
    val note: String = "",
    @SerialName("logged_at") val loggedAt: Instant,
)

/** Daily ordinal nutrition inputs (NUT-14 (5)); kcal is optional and enables NUT-04 (H14). */
@Serializable
data class NutritionLog(
    val id: String,
    val date: LocalDate,
    val kcal: Int? = null,
    @SerialName("protein_g") val proteinG: Int? = null,
    @SerialName("protein_target_hit") val proteinTargetHit: Boolean? = null,
    @SerialName("meals_heaviness") val mealsHeaviness: Int? = null,     // 1 light .. 3 heavy
    @SerialName("alcohol_drinks") val alcoholDrinks: Int? = null,       // NUT-13
    @SerialName("travel_day") val travelDay: Boolean = false,           // NUT-14 (4): suppress trend reactions
    val note: String = "",
    @SerialName("logged_at") val loggedAt: Instant,
)
