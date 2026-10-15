package com.gymcoach.core.coach.wire

import com.gymcoach.core.coach.CoachFixtures
import com.gymcoach.core.json.CoreJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SseParserTest {
    private fun parseAll(text: String): List<SseEvent> {
        val p = SseParser()
        return p.feed(text) + p.flush()
    }

    private fun types(events: List<SseEvent>): List<String> =
        events.map { CoreJson.wire.parseToJsonElement(it.data).jsonObject["type"]!!.jsonPrimitive.content }

    @Test
    fun r1VerbatimTextFixtureParsesByteForByte() {
        val events = parseAll(CoachFixtures.sse("text_end_turn.sse"))
        assertEquals(listOf("message_start", "content_block_start", "ping", "content_block_delta", "content_block_stop", "message_delta", "message_stop"), events.map { it.name })
        assertEquals(events.map { it.name }, types(events))
        assertEquals("{\"type\": \"content_block_delta\", \"index\": 0, \"delta\": {\"type\": \"text_delta\", \"text\": \"Hello\"}}", events[3].data)
    }

    @Test
    fun everyFixtureYieldsJsonPayloadsWhoseTypeMatchesTheEventName() {
        for (name in SseFixtureNames.ALL) {
            val events = parseAll(CoachFixtures.sse(name))
            assertTrue(events.isNotEmpty(), name)
            assertEquals(events.map { it.name }, types(events), name)
        }
    }

    @Test
    fun crlfCommentsIdRetryAndMultiLineDataAreHandled() {
        val events = parseAll(CoachFixtures.sse("crlf_comments.sse"))
        assertEquals(listOf("message_start", "content_block_start", "future_event", "content_block_delta", "content_block_stop", "message_delta", "message_stop"), events.map { it.name })
        assertEquals("{\"type\":\"content_block_start\",\n\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}", events[1].data)
        assertTrue(events[0].data.startsWith("{\"type\":\"message_start\""), "no space after the colon keeps the payload intact")
        assertTrue(events[3].data.contains("Grüße 💪 aus Ulm"))
    }

    @Test
    fun loneCarriageReturnTerminatesLinesAndHeldCrWaitsForTheNextChunk() {
        val p = SseParser()
        assertEquals(emptyList(), p.feed("event: a\rdata: 1\r"))
        val out = p.feed("\ndata: 2\r\n\r\n")
        assertEquals(listOf(SseEvent("a", "1\n2")), out)
    }

    @Test
    fun eventWithoutDataIsNotDispatchedAndNameResets() {
        val out = parseAll("event: lonely\n\ndata: x\n\n")
        assertEquals(listOf(SseEvent(null, "x")), out)
    }

    @Test
    fun flushDispatchesAnUnterminatedFinalEvent() {
        val p = SseParser()
        assertEquals(emptyList(), p.feed("event: message_stop\ndata: {\"type\":\"message_stop\"}"))
        val out = p.flush()
        assertEquals(1, out.size)
        assertEquals("message_stop", out[0].name)
        assertEquals(emptyList(), p.flush())
    }

    @Test
    fun byteOrderMarkAtStreamStartIsIgnored() {
        val out = parseAll("﻿event: ping\ndata: {\"type\":\"ping\"}\n\n")
        assertEquals("ping", out.single().name)
    }

    @Test
    fun fieldWithoutColonAndUnknownFieldsAreIgnored() {
        val out = parseAll("data\nfoo: bar\ndata: y\n\n")
        assertEquals("\ny", out.single().data)
        assertNull(out.single().name)
    }
}

object SseFixtureNames {
    val ALL: List<String> = listOf(
        "text_end_turn.sse", "tool_use_proposal.sse", "thinking_summarized.sse", "redacted_thinking.sse", "read_only_lookup.sse",
        "error_overloaded.sse", "refusal.sse", "crlf_comments.sse", "max_tokens.sse",
    )
}
