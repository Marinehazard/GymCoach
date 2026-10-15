// core/src/main/kotlin/com/gymcoach/core/coach/TranscriptCompactor.kt
package com.gymcoach.core.coach

import com.gymcoach.core.coach.wire.RequestBuilder
import com.gymcoach.core.coach.wire.StreamAccumulator
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.SpendRecord
import com.gymcoach.core.model.TokenUsage
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.util.IdGen
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * §6.9 "simple compaction": one non-streamed request (no tools, no tool_choice, max_tokens 1024, effort low) with the
 * whole transcript's display text produces a ≤ 600-token summary; the old conversation becomes COMPACTED (read-only),
 * and a new OPEN conversation carries the summary in `seedSummary` — CoachTurnService sends it as `<summary>` before
 * the fresh `<context>` snapshot of the first message. Nothing else is carried over (no turns, no thinking blocks).
 */
class TranscriptCompactor(private val coach: CoachRepository, private val backendFactory: (BackendMode) -> ClaudeBackend, private val settings: SettingsRepository, private val clock: Clock) {
    companion object {
        const val MAX_INPUT_TOKENS: Int = 40_000
        const val MAX_ASSISTANT_TURNS: Int = 40
        val MAX_AGE: Duration = Duration.ofDays(7)
    }

    /** §6.9: ClaudeBackend.complete(buildCompaction(...)) → summary; marks the old conversation COMPACTED; returns the new OPEN conversation (seedSummary set). Throws IllegalStateException while proposals are PENDING. */
    suspend fun compact(conversationId: String, compactionPrompt: String): ChatConversation {
        val old = coach.conversation(conversationId) ?: throw IllegalArgumentException("unknown conversation $conversationId")
        check(coach.proposals(conversationId).none { it.status == ProposalStatus.PENDING }) { "decide the pending proposals before compacting" }
        val cfg = settings.get()
        val transcript = coach.messages(conversationId).sortedBy { it.seq }
            .filter { it.displayText.isNotBlank() }
            .joinToString("\n\n") { (if (it.role == ChatRole.USER) "User: " else "Coach: ") + it.displayText }
        val body = RequestBuilder.buildCompaction(cfg.copy(modelId = old.model), compactionPrompt, transcript)
        val frame = backendFactory(old.backend).complete(body)
        val message = frame.event
        val summary = textOf(message).trim()
        val usage = (message["usage"] as? JsonObject)?.let { StreamAccumulator.mergeUsage(TokenUsage(), it) } ?: TokenUsage()
        val cost = if (old.backend == BackendMode.HOME_BRIDGE) 0.0 else PricingTable.costUsd(old.model, usage)
        coach.addSpend(SpendRecord(IdGen.newId(), clock.now(), old.model, usage, cost, old.backend, old.id))
        val now = clock.now()
        coach.updateConversation(old.copy(status = ConversationStatus.COMPACTED, endedAt = now, isDefault = false))
        val fresh = ChatConversation(
            id = IdGen.newId(),
            startedAt = now,
            backend = cfg.backendMode,
            model = cfg.modelId,
            effort = cfg.effort,
            systemPromptHash = old.systemPromptHash,
            toolContractVersion = old.toolContractVersion,
            seedSummary = summary,
            status = ConversationStatus.OPEN,
        )
        coach.createConversation(fresh)
        return fresh
    }

    fun needsCompaction(conversation: ChatConversation, now: Instant): Boolean {
        if (conversation.status == ConversationStatus.COMPACTED || conversation.status == ConversationStatus.CLOSED) return false
        return conversation.lastInputTokens > MAX_INPUT_TOKENS ||
            conversation.assistantTurns > MAX_ASSISTANT_TURNS ||
            Duration.between(conversation.startedAt, now) > MAX_AGE
    }

    private fun textOf(message: JsonObject): String {
        val content = message["content"] as? JsonArray ?: return ""
        return content.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
            (o["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("\n")
    }
}
