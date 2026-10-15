package com.gymcoach.core.coach

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.Effort
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TranscriptCompactorTest {
    private val now = CoachFixtures.NOW
    private fun conv(id: String = "c1", tokens: Int = 0, turns: Int = 0, started: Instant = now) = ChatConversation(
        id = id, startedAt = started, backend = BackendMode.DIRECT_API, model = "claude-opus-5", effort = Effort.LOW,
        systemPromptHash = "h", toolContractVersion = 1, status = ConversationStatus.OPEN, assistantTurns = turns, lastInputTokens = tokens,
    )

    @Test
    fun triggers() {
        val c = TranscriptCompactor(FakeCoach(), { FakeBackend() }, FakeSettings(), MutableClock(now))
        assertFalse(c.needsCompaction(conv(), now))
        assertTrue(c.needsCompaction(conv(tokens = 40_001), now))
        assertFalse(c.needsCompaction(conv(tokens = 40_000), now))
        assertTrue(c.needsCompaction(conv(turns = 41), now))
        assertTrue(c.needsCompaction(conv(started = now.minusSeconds(8L * 24 * 3600)), now))
        assertFalse(c.needsCompaction(conv(tokens = 50_000).copy(status = ConversationStatus.COMPACTED), now))
    }

    @Test
    fun compactSendsTheDisplayTranscriptWithoutToolsAndSeedsANewConversation() = runBlocking {
        val coach = FakeCoach()
        val backend = FakeBackend()
        val clock = MutableClock(now)
        coach.createConversation(conv())
        coach.addMessage(ChatMessage("u1", "c1", 1, ChatRole.USER, "[]", "Bike on Wednesday?", now))
        coach.addMessage(ChatMessage("a1", "c1", 2, ChatRole.ASSISTANT, "[]", "Yes, 30 min zone 2.", now))
        coach.addMessage(ChatMessage("u2", "c1", 3, ChatRole.USER, "[]", "", now))
        backend.completeResponse = CoreJson.wire.parseToJsonElement(
            "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"x\"},{\"type\":\"text\",\"text\":\"Wednesday bike.\"}],\"usage\":{\"input_tokens\":200,\"output_tokens\":40}}",
        ).jsonObject
        val compactor = TranscriptCompactor(coach, { backend }, FakeSettings(), clock)
        val fresh = compactor.compact("c1", SystemPrompt.compactionPrompt())
        val body = backend.completeBodies.single()
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("tool_choice"))
        assertEquals(SystemPrompt.compactionPrompt(), body["system"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val transcript = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("User: Bike on Wednesday?\n\nCoach: Yes, 30 min zone 2.", transcript)
        assertEquals("Wednesday bike.", fresh.seedSummary)
        assertEquals(ConversationStatus.OPEN, fresh.status)
        assertEquals(ConversationStatus.COMPACTED, coach.conversations["c1"]!!.status)
        assertEquals(now, coach.conversations["c1"]!!.endedAt)
        assertEquals(1, coach.spend.size)
        assertEquals(PricingTable.costUsd("claude-opus-5", coach.spend.single().usage), coach.spend.single().costUsd, 1e-12)
        assertEquals(fresh, coach.openConversation())
    }

    @Test
    fun neverCompactsWhileProposalsArePending() {
        val coach = FakeCoach()
        runBlocking {
            coach.createConversation(conv())
            coach.addProposal(Proposal("p", "c1", "a1", "propose_deload", 1, "{}", null, "r", ProposalStatus.PENDING, createdAt = now, expiresOn = CoachFixtures.TODAY))
        }
        val compactor = TranscriptCompactor(coach, { FakeBackend() }, FakeSettings(), MutableClock(now))
        assertFailsWith<IllegalStateException> { runBlocking { compactor.compact("c1", "P") } }
        assertEquals(ConversationStatus.OPEN, coach.conversations["c1"]!!.status)
    }
}
