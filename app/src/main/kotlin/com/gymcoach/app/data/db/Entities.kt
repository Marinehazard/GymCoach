// app/src/main/kotlin/com/gymcoach/app/data/db/Entities.kt
package com.gymcoach.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,                                   // single row
    @ColumnInfo(name = "json") val json: String,                   // core Profile
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "body_metrics", indices = [Index(value = ["date"])])
data class BodyMetricEntity(
    @PrimaryKey val id: String,
    val date: String,
    @ColumnInfo(name = "entered_weight") val enteredWeight: Double?,
    @ColumnInfo(name = "entered_weight_unit") val enteredWeightUnit: String?,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    @ColumnInfo(name = "waist_cm") val waistCm: Double?,
    @ColumnInfo(name = "resting_hr") val restingHr: Int?,
    val steps: Int?,
    val note: String,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
)

@Entity(tableName = "gyms", indices = [Index(value = ["last_used_at"])])
data class GymEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "preset_origin") val presetOrigin: String?,
    @ColumnInfo(name = "items_json") val itemsJson: String,        // List<GymItem>
    @ColumnInfo(name = "default_session_minutes") val defaultSessionMinutes: Int?,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    @ColumnInfo(name = "travel_kit_merged") val travelKitMerged: Boolean,
    val notes: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,   // GymSet.schemaVersion (columnar document, §7.2)
)

@Entity(tableName = "sessions", indices = [Index(value = ["date"]), Index(value = ["status"])])
data class SessionEntity(
    @PrimaryKey val id: String,
    val date: String,
    @ColumnInfo(name = "gym_id") val gymId: String,
    @ColumnInfo(name = "planned_session_id") val plannedSessionId: String?,
    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "plan_json") val planJson: String?,         // SessionPlan frozen at start
    val status: String,                                            // SessionStatus serial name
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "session_rpe") val sessionRpe: Int?,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int?,
    @ColumnInfo(name = "readiness_checkin_id") val readinessCheckinId: String?,
    @ColumnInfo(name = "skip_reason") val skipReason: String?,
    val notes: String,
)

@Entity(tableName = "set_logs", indices = [Index(value = ["session_id"]), Index(value = ["exercise_id", "logged_at"])])
data class SetLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "block_id") val blockId: String?,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "set_index") val setIndex: Int,
    val kind: String,
    @ColumnInfo(name = "entered_weight") val enteredWeight: Double?,
    @ColumnInfo(name = "entered_weight_unit") val enteredWeightUnit: String?,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    @ColumnInfo(name = "per_hand") val perHand: Boolean,
    val reps: Int?,
    val seconds: Int?,
    val metres: Int?,
    val rir: Int?,
    val side: String,
    @ColumnInfo(name = "prescribed_load_kg") val prescribedLoadKg: Double?,
    @ColumnInfo(name = "prescribed_reps_lo") val prescribedRepsLo: Int?,
    @ColumnInfo(name = "prescribed_reps_hi") val prescribedRepsHi: Int?,
    @ColumnInfo(name = "prescribed_rir") val prescribedRir: Int?,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
    @ColumnInfo(name = "superseded_by_id") val supersededById: String?,   // the only column ever UPDATEd
    @ColumnInfo(name = "supersedes_id") val supersedesId: String?,
    val note: String,
)

@Entity(tableName = "adjustments", indices = [Index(value = ["created_at"]), Index(value = ["session_id"])])
data class AdjustmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "exercise_id") val exerciseId: String?,
    val kind: String,
    @ColumnInfo(name = "rule_ids") val ruleIds: String,            // comma-joined
    val summary: String,
    @ColumnInfo(name = "inputs_json") val inputsJson: String,
    @ColumnInfo(name = "before_json") val beforeJson: String,
    @ColumnInfo(name = "after_json") val afterJson: String,
    val source: String,
    @ColumnInfo(name = "proposal_id") val proposalId: String?,
    @ColumnInfo(name = "override_id") val overrideId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "undone_at") val undoneAt: Long?,
)

@Entity(tableName = "progression_states", indices = [Index(value = ["exercise_id"])])
data class ProgressionStateEntity(
    @PrimaryKey val key: String,                                   // ProgressionKey.id
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "gym_scope") val gymScope: String?,
    val status: String,
    val json: String,                                              // core ProgressionState
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "overrides", indices = [Index(value = ["created_at"])])
data class OverrideEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val source: String,
    @ColumnInfo(name = "proposal_id") val proposalId: String?,
    @ColumnInfo(name = "mutation_type") val mutationType: String,  // PlanMutation discriminator
    @ColumnInfo(name = "mutation_json") val mutationJson: String,
    @ColumnInfo(name = "revoked_at") val revokedAt: Long?,
    @ColumnInfo(name = "expires_on") val expiresOn: String?,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,   // PlanOverride.schemaVersion (columnar document, §7.2)
)

@Entity(tableName = "plan_snapshots", indices = [Index(value = ["is_current"]), Index(value = ["inputs_hash"])])
data class PlanSnapshotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inputs_hash") val inputsHash: String,
    @ColumnInfo(name = "mesocycle_json") val mesocycleJson: String,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
)

@Entity(tableName = "nutrition_state")
data class NutritionStateEntity(@PrimaryKey val id: Int = 1, val json: String, @ColumnInfo(name = "updated_at") val updatedAt: Long)

@Entity(tableName = "readiness", indices = [Index(value = ["date", "logged_at"])])
data class ReadinessEntity(
    @PrimaryKey val id: String,
    val date: String,
    val json: String,                                              // core ReadinessCheckin
    val score: Int,
    val band: String,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
)

@Entity(tableName = "weekly_checkins", indices = [Index(value = ["week_start"], unique = true)])
data class WeeklyCheckinEntity(@PrimaryKey val id: String, @ColumnInfo(name = "week_start") val weekStart: String, val json: String, @ColumnInfo(name = "completed_at") val completedAt: Long?)

@Entity(tableName = "checkin_questions", indices = [Index(value = ["answered"])])
data class CheckinQuestionEntity(@PrimaryKey val id: String, val json: String, val answered: Boolean, @ColumnInfo(name = "active_until") val activeUntil: String?)

@Entity(tableName = "nutrition_logs", indices = [Index(value = ["date"], unique = true)])
data class NutritionLogEntity(
    @PrimaryKey val id: String,
    val date: String,
    val kcal: Int?,
    @ColumnInfo(name = "protein_g") val proteinG: Int?,
    @ColumnInfo(name = "protein_target_hit") val proteinTargetHit: Boolean?,
    @ColumnInfo(name = "meals_heaviness") val mealsHeaviness: Int?,
    @ColumnInfo(name = "alcohol_drinks") val alcoholDrinks: Int?,
    @ColumnInfo(name = "travel_day") val travelDay: Boolean,
    val note: String,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
)

@Entity(tableName = "cardio_logs", indices = [Index(value = ["date"])])
data class CardioLogEntity(
    @PrimaryKey val id: String,
    val date: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    val kind: String,
    val minutes: Int,
    @ColumnInfo(name = "avg_hr") val avgHr: Int?,
    val rpe: Int?,
    val note: String,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
)

@Entity(tableName = "chat_conversations", indices = [Index(value = ["status"]), Index(value = ["started_at"])])
data class ChatConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    val backend: String,
    val model: String,
    val effort: String,
    @ColumnInfo(name = "system_prompt_hash") val systemPromptHash: String,
    @ColumnInfo(name = "tool_contract_version") val toolContractVersion: Int,
    @ColumnInfo(name = "seed_summary") val seedSummary: String?,
    val status: String,
    @ColumnInfo(name = "assistant_turns") val assistantTurns: Int,
    @ColumnInfo(name = "last_input_tokens") val lastInputTokens: Int,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,       // ChatConversation.isDefault (§2.10; false for a refused conversation left open by "Retry on Sonnet 5", §6.7)
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,   // ChatConversation.schemaVersion (columnar document, §7.2)
)

@Entity(tableName = "chat_messages", indices = [Index(value = ["conversation_id", "seq"], unique = true)])
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val seq: Int,
    val role: String,
    @ColumnInfo(name = "content_json") val contentJson: String,   // verbatim content-block array
    @ColumnInfo(name = "display_text") val displayText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "usage_json") val usageJson: String?,
    @ColumnInfo(name = "request_id") val requestId: String?,
    @ColumnInfo(name = "stop_reason") val stopReason: String?,
    @ColumnInfo(name = "stop_details_json") val stopDetailsJson: String?,
    @ColumnInfo(name = "caveat_appended") val caveatAppended: Boolean,
    @ColumnInfo(name = "tool_results_json") val toolResultsJson: String?,   // assistant rows: {tool_use_id: read-only result JSON} (§6.7)
)

@Entity(tableName = "proposals", indices = [Index(value = ["conversation_id"]), Index(value = ["status"])])
data class ProposalEntity(
    @PrimaryKey val id: String,                                    // tool_use id
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "assistant_message_id") val assistantMessageId: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "contract_version") val contractVersion: Int,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "mutation_json") val mutationJson: String?,
    val rationale: String,
    val status: String,
    @ColumnInfo(name = "warnings_json") val warningsJson: String,
    @ColumnInfo(name = "errors_json") val errorsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_on") val expiresOn: String,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
    @ColumnInfo(name = "override_id") val overrideId: String?,
    @ColumnInfo(name = "result_summary") val resultSummary: String?,
    @ColumnInfo(name = "result_sent") val resultSent: Boolean,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,   // Proposal.schemaVersion (columnar document, §7.2)
)

@Entity(tableName = "spend_records", indices = [Index(value = ["at"])])
data class SpendRecordEntity(@PrimaryKey val id: String, val at: Long, val model: String, @ColumnInfo(name = "usage_json") val usageJson: String, @ColumnInfo(name = "cost_usd") val costUsd: Double, val backend: String, @ColumnInfo(name = "conversation_id") val conversationId: String)

/** Projection for WorkoutDao.lastLoggedAtByExercise (not an entity). */
data class ExerciseLastLogged(
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "last_logged_at") val lastLoggedAt: Long,
)
