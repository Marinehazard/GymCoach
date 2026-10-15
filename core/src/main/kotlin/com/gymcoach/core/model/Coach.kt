// core/src/main/kotlin/com/gymcoach/core/model/Coach.kt
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
data class ChatConversation(
    val id: String,
    @SerialName("started_at") val startedAt: Instant,
    @SerialName("ended_at") val endedAt: Instant? = null,
    val backend: BackendMode,
    val model: String,
    val effort: Effort,
    @SerialName("system_prompt_hash") val systemPromptHash: String,        // SHA-256 of the byte-stable prompt (§6.4)
    @SerialName("tool_contract_version") val toolContractVersion: Int,
    @SerialName("seed_summary") val seedSummary: String? = null,           // set when started by compaction (§6.9)
    val status: ConversationStatus,
    @SerialName("assistant_turns") val assistantTurns: Int = 0,
    @SerialName("last_input_tokens") val lastInputTokens: Int = 0,         // input + cache_read + cache_creation of the last response
    @SerialName("is_default") val isDefault: Boolean = true,               // false for a refused conversation left open by "Retry on Sonnet 5" (§6.7)
    @SerialName("schema_version") val schemaVersion: Int = 1,              // §7.2 DocVersions
)

/**
 * One transcript entry. contentJson is the verbatim Messages-API content array (thinking blocks included).
 * A synthetic user message (tool_result-only follow-up, §6.7) has displayText == "" and is hidden by the UI.
 */
@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val seq: Int,
    val role: ChatRole,
    @SerialName("content_json") val contentJson: String,
    @SerialName("display_text") val displayText: String,                   // text blocks joined; never sent back
    @SerialName("created_at") val createdAt: Instant,
    val usage: TokenUsage? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_details_json") val stopDetailsJson: String? = null,
    @SerialName("caveat_appended") val caveatAppended: Boolean = false,    // SUP-12 post-check (§6.12)
    /** Assistant messages only: {tool_use_id: result JSON (≤ 2,000 chars)} for every READ-ONLY tool_use block of this message, so the next request can echo the tool_result after process death (§6.7). */
    @SerialName("tool_results_json") val toolResultsJson: String? = null,
)

@Serializable
data class TokenUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    @SerialName("thinking_tokens") val thinkingTokens: Int? = null,
) {
    val totalInput: Int get() = inputTokens + cacheReadInputTokens + cacheCreationInputTokens
}

@Serializable
data class Proposal(
    val id: String,                                                        // == tool_use id
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("assistant_message_id") val assistantMessageId: String,
    @SerialName("tool_name") val toolName: String,
    @SerialName("contract_version") val contractVersion: Int,
    @SerialName("payload_json") val payloadJson: String,                   // verbatim tool_use.input
    val mutation: PlanMutation? = null,                                    // null when INVALID
    val rationale: String,
    val status: ProposalStatus,
    @SerialName("validation_warnings") val validationWarnings: List<String> = emptyList(),
    @SerialName("validation_errors") val validationErrors: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("expires_on") val expiresOn: LocalDate,                    // created + 7 days
    @SerialName("decided_at") val decidedAt: Instant? = null,
    @SerialName("override_id") val overrideId: String? = null,
    @SerialName("result_summary") val resultSummary: String? = null,       // text sent back as tool_result
    @SerialName("result_sent") val resultSent: Boolean = false,
    @SerialName("schema_version") val schemaVersion: Int = 1,              // §7.2 DocVersions
)

@Serializable
data class SpendRecord(val id: String, val at: Instant, val model: String, val usage: TokenUsage, @SerialName("cost_usd") val costUsd: Double, val backend: BackendMode, @SerialName("conversation_id") val conversationId: String)
