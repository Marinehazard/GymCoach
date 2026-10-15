package com.gymcoach.core.coach.wire

import com.gymcoach.core.coach.CoachFixtures
import com.gymcoach.core.coach.TurnEvent
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.TokenUsage
import com.gymcoach.core.repo.BackendError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class StreamAccumulatorTest {
    private class Run(val acc: StreamAccumulator, val events: List<TurnEvent>, val toolUses: List<ToolUseBlock>)

    private fun run(fixture: String): Run {
        val acc = StreamAccumulator()
        val events = ArrayList<TurnEvent>()
        val tools = ArrayList<ToolUseBlock>()
        for (f in CoachFixtures.frames(CoachFixtures.sse(fixture))) {
            events.addAll(acc.accept(f.event))
            tools.addAll(acc.takeCompletedToolUses())
        }
        return Run(acc, events, tools)
    }

    @Test
    fun textStreamAccumulatesAndEmitsTextDeltas() {
        val r = run("text_end_turn.sse")
        assertTrue(r.acc.isComplete)
        assertEquals(listOf<TurnEvent>(TurnEvent.TextDelta("Hello")), r.events)
        val m = r.acc.result()
        assertEquals("msg_1nZdL29xx5MUA1yADyHTEsnR8uuvGzszyY", m.id)
        assertEquals("claude-opus-5", m.model)
        assertEquals("end_turn", m.stopReason)
        assertNull(m.stopDetails)
        assertEquals("[{\"type\":\"text\",\"text\":\"Hello\"}]", RequestBuilder.contentJson(m.content))
        assertEquals(TokenUsage(inputTokens = 25, outputTokens = 15), m.usage)
        assertEquals(0, m.unknownEventTypes)
    }

    @Test
    fun toolUseWithEmptyFirstDeltaParsesInputAtBlockStop() {
        val r = run("tool_use_proposal.sse")
        val started = r.events.filterIsInstance<TurnEvent.ToolUseStarted>()
        assertEquals(listOf(TurnEvent.ToolUseStarted("toolu_01A", "propose_swap_exercise")), started)
        val tu = r.toolUses.single()
        assertEquals("toolu_01A", tu.id)
        assertEquals(2, tu.index)
        assertNull(tu.inputError)
        assertEquals("dumbbell_bench_press", tu.input["to_exercise_id"]!!.jsonPrimitive.content)
        assertEquals(1, tu.input["contract_version"]!!.jsonPrimitive.int)
        val m = r.acc.result()
        assertEquals("tool_use", m.stopReason)
        val content = m.content
        assertEquals(3, content.size)
        assertEquals("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"EosnCkYICxIMMb3LzNrMu==\"}", CoreJson.wire.encodeToString(JsonObject.serializer(), content[0].jsonObject))
        assertEquals("Swap it for dumbbells — Kurzhanteln.", content[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(tu.input, content[2].jsonObject["input"])
        assertEquals("propose_swap_exercise", content[2].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun thinkingSignatureOnlyBlockIsKeptVerbatimAndSummarizedThinkingIsJoined() {
        val r = run("thinking_summarized.sse")
        val block = r.acc.result().content[0].jsonObject
        assertEquals("thinking", block["type"]!!.jsonPrimitive.content)
        assertTrue(block["thinking"]!!.jsonPrimitive.content.startsWith("I need to find the GCD"))
        assertEquals("EqQBCgIYAhIM1gbcDa9GJwZA2b3hGgxBdjrkzLoky3dl1pkiMOYds...", block["signature"]!!.jsonPrimitive.content)
        assertEquals(60, r.acc.result().usage.thinkingTokens)
    }

    @Test
    fun redactedThinkingIsEchoedUnchanged() {
        val r = run("redacted_thinking.sse")
        val raw = CoachFixtures.frames(CoachFixtures.sse("redacted_thinking.sse"))[1].event["content_block"]!!.jsonObject
        assertEquals(raw, r.acc.result().content[0].jsonObject)
        assertEquals("redacted_thinking", r.acc.result().content[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun usageIsCumulativeAndThinkingTokensComeFromTheFinalDelta() {
        val r = run("tool_use_proposal.sse")
        assertEquals(TokenUsage(inputTokens = 40, outputTokens = 312, cacheReadInputTokens = 3500, cacheCreationInputTokens = 0, thinkingTokens = 180), r.acc.result().usage)
        val merged = StreamAccumulator.mergeUsage(TokenUsage(inputTokens = 5, outputTokens = 1), CoreJson.wire.parseToJsonElement("{\"output_tokens\":9}").jsonObject)
        assertEquals(TokenUsage(inputTokens = 5, outputTokens = 9), merged)
    }

    @Test
    fun midStreamOverloadedErrorIsTerminalOverloaded() {
        val r = run("error_overloaded.sse")
        assertEquals(TurnEvent.Failed(BackendError.Overloaded(midStream = true), draftKept = true), r.events.last())
        assertEquals(BackendError.Overloaded(true), r.acc.error)
        assertFalse(r.acc.isComplete)
        assertTrue(r.acc.producedOutput)
    }

    @Test
    fun otherErrorTypesBecomeStreamError() {
        val acc = StreamAccumulator()
        val out = acc.accept(CoreJson.wire.parseToJsonElement("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}").jsonObject)
        assertEquals(listOf<TurnEvent>(TurnEvent.Failed(BackendError.StreamError("api_error", "boom"), true)), out)
    }

    @Test
    fun unknownEventTypesAreCountedAndIgnored() {
        val r = run("crlf_comments.sse")
        assertTrue(r.acc.isComplete)
        assertEquals(1, r.acc.result().unknownEventTypes)
        assertEquals(listOf<TurnEvent>(TurnEvent.TextDelta("Grüße 💪 aus Ulm")), r.events)
    }

    @Test
    fun refusalCarriesStopDetails() {
        val r = run("refusal.sse")
        val m = r.acc.result()
        assertEquals("refusal", m.stopReason)
        assertEquals("refusal", m.stopDetails!!["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyToolBufferBecomesEmptyObjectAndBrokenJsonIsFlagged() {
        val acc = StreamAccumulator()
        fun j(s: String) = CoreJson.wire.parseToJsonElement(s).jsonObject
        acc.accept(j("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"get_gym_equipment\",\"input\":{}}}"))
        acc.accept(j("{\"type\":\"content_block_stop\",\"index\":0}"))
        acc.accept(j("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"get_rule\",\"input\":{}}}"))
        acc.accept(j("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"rule_id\\\":\"}}"))
        acc.accept(j("{\"type\":\"content_block_stop\",\"index\":1}"))
        val done = acc.takeCompletedToolUses()
        assertEquals(JsonObject(emptyMap()), done[0].input)
        assertNull(done[0].inputError)
        assertEquals(JsonObject(emptyMap()), done[1].input)
        assertTrue(done[1].inputError != null)
        assertEquals(emptyList(), acc.takeCompletedToolUses())
    }
}
