package com.gymcoach.core.coach.wire

import com.gymcoach.core.coach.ToolSchemas
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.Effort
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RequestBuilderTest {
    private val settings = AppSettings(backendMode = BackendMode.DIRECT_API, coachUserId = "3f9c-user")
    private val at = Instant.parse("2026-09-09T07:00:00Z")
    private val assistantContent =
        "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"Eos…\"},{\"type\":\"redacted_thinking\",\"data\":\"abc\"},{\"type\":\"text\",\"text\":\"Two changes.\"}," +
            "{\"type\":\"tool_use\",\"id\":\"toolu_01L\",\"name\":\"lookup_exercises\",\"input\":{\"query\":\"rdl\",\"available_only\":true}}," +
            "{\"type\":\"tool_use\",\"id\":\"toolu_01A\",\"name\":\"propose_add_cardio\",\"input\":{\"contract_version\":1,\"weekday\":3,\"minutes\":30,\"kind\":\"zone2\",\"rationale\":\"CAR-07\"}}]"

    private fun msg(seq: Int, role: ChatRole, content: String) =
        ChatMessage("m$seq", "c1", seq, role, content, "", at)

    private val history = listOf(
        msg(1, ChatRole.USER, "[{\"type\":\"text\",\"text\":\"<context schema=\\\"1\\\">{}</context>\"},{\"type\":\"text\",\"text\":\"Bike on Wednesday?\"}]"),
        msg(2, ChatRole.ASSISTANT, assistantContent),
    )

    private fun newUser(vararg blocks: JsonObject) = JsonArray(blocks.toList())
    private val results = arrayOf(
        RequestBuilder.toolResultBlock("toolu_01L", "[{\"id\":\"romanian_deadlift\"}]", false),
        RequestBuilder.toolResultBlock("toolu_01A", "{\"status\":\"pending\"}", false),
        RequestBuilder.textBlock("<delta schema=\"1\">{}</delta>"),
        RequestBuilder.textBlock("Thanks"),
    )

    private fun body(): JsonObject = RequestBuilder.build(settings, "SYSTEM", ToolSchemas.all, history, newUser(*results))

    @Test
    fun toolResultsComeFirstOnePerToolUseInContentOrder() {
        val b = body()
        val last = b["messages"]!!.jsonArray.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        val types = last["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("tool_result", "tool_result", "text", "text"), types)
        assertEquals(listOf("toolu_01L", "toolu_01A"), last["content"]!!.jsonArray.take(2).map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content })
    }

    @Test
    fun missingOrMisorderedOrLateToolResultsAreRejected() {
        assertFailsWith<IllegalArgumentException> { RequestBuilder.build(settings, "S", ToolSchemas.all, history, newUser(results[0], results[2])) }
        assertFailsWith<IllegalArgumentException> { RequestBuilder.build(settings, "S", ToolSchemas.all, history, newUser(results[1], results[0], results[3])) }
        assertFailsWith<IllegalArgumentException> { RequestBuilder.build(settings, "S", ToolSchemas.all, history, newUser(results[2], results[0], results[1])) }
        assertFailsWith<IllegalArgumentException> { RequestBuilder.build(settings, "S", ToolSchemas.all, history, JsonArray(emptyList())) }
    }

    @Test
    fun assistantContentIsEchoedUnchanged() {
        val echoed = body()["messages"]!!.jsonArray[1].jsonObject
        assertEquals("assistant", echoed["role"]!!.jsonPrimitive.content)
        assertEquals(assistantContent, CoreJson.wire.encodeToString(JsonElement.serializer(), echoed["content"]!!))
    }

    @Test
    fun requestNeverEndsWithAnAssistantMessage() {
        val b = RequestBuilder.build(settings, "S", ToolSchemas.all, history.take(1) + msg(2, ChatRole.ASSISTANT, "[{\"type\":\"text\",\"text\":\"Hi\"}]"), newUser(RequestBuilder.textBlock("continue")))
        assertEquals("user", b["messages"]!!.jsonArray.last().jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun consecutiveUserMessagesAreAllowed() {
        val b = RequestBuilder.build(settings, "S", null, history.take(1), newUser(RequestBuilder.textBlock("again")))
        assertEquals(listOf("user", "user"), b["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun cacheControlOnSystemAndTopLevelAndProfileFields() {
        val b = body()
        val system = b["system"]!!.jsonArray.single().jsonObject
        assertEquals("text", system["type"]!!.jsonPrimitive.content)
        assertEquals("SYSTEM", system["text"]!!.jsonPrimitive.content)
        assertEquals("{\"type\":\"ephemeral\"}", system["cache_control"].toString())
        assertEquals("{\"type\":\"ephemeral\"}", b["cache_control"].toString())
        assertEquals("claude-opus-5", b["model"]!!.jsonPrimitive.content)
        assertEquals(4096, b["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(b["stream"]!!.jsonPrimitive.boolean)
        assertEquals("{\"type\":\"auto\"}", b["tool_choice"].toString())
        assertEquals("{\"type\":\"adaptive\"}", b["thinking"].toString())
        assertEquals("{\"effort\":\"low\"}", b["output_config"].toString())
        assertEquals("{\"user_id\":\"3f9c-user\"}", b["metadata"].toString())
        val tools = b["tools"]!!.jsonArray
        assertEquals(20, tools.size)
        assertTrue(tools.all { it.jsonObject["strict"]!!.jsonPrimitive.boolean })
        val medium = RequestBuilder.build(settings.copy(effort = Effort.MEDIUM, modelId = "claude-sonnet-5"), "S", null, emptyList(), newUser(RequestBuilder.textBlock("x")))
        assertEquals("{\"effort\":\"medium\"}", medium["output_config"].toString())
        assertEquals("claude-sonnet-5", medium["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun noSamplingParamsPrefillOrForbiddenKeysEverSent() {
        val forbidden = setOf("temperature", "top_p", "top_k", "stop_sequences")
        for (b in listOf(body(), RequestBuilder.buildCompaction(settings, "P", "T"), RequestBuilder.buildTest(settings))) {
            assertTrue(b.keys.none { it in forbidden }, b.keys.toString())
            assertFalse(b.toString().contains("budget_tokens"))
            assertEquals("user", b["messages"]!!.jsonArray.last().jsonObject["role"]!!.jsonPrimitive.content)
        }
        assertEquals(listOf("model", "max_tokens", "stream", "system", "tools", "tool_choice", "thinking", "output_config", "cache_control", "metadata", "messages"), body().keys.toList())
    }

    @Test
    fun metadataOmittedWhileCoachUserIdIsBlank() {
        val b = RequestBuilder.build(settings.copy(coachUserId = ""), "S", ToolSchemas.all, history, newUser(*results))
        assertFalse(b.containsKey("metadata"))
        assertFalse(RequestBuilder.buildTest(settings.copy(coachUserId = "  ")).containsKey("metadata"))
    }

    @Test
    fun systemAndToolsAreByteIdenticalAcrossRequests() {
        val a = body()
        val b = RequestBuilder.build(settings, "SYSTEM", ToolSchemas.all, history + listOf(msg(3, ChatRole.USER, CoreJson.wire.encodeToString(JsonElement.serializer(), newUser(*results))), msg(4, ChatRole.ASSISTANT, "[{\"type\":\"text\",\"text\":\"ok\"}]")), newUser(RequestBuilder.textBlock("next")))
        assertEquals(a["system"].toString(), b["system"].toString())
        assertEquals(a["tools"].toString(), b["tools"].toString())
        val prefixA = a["messages"]!!.jsonArray.take(2).toString()
        val prefixB = b["messages"]!!.jsonArray.take(2).toString()
        assertEquals(prefixA, prefixB)
    }

    @Test
    fun compactionBodyHasNoToolsOrToolChoiceAndIsNotStreamed() {
        val b = RequestBuilder.buildCompaction(settings, "Summarise.", "User: hi\n\nCoach: hello")
        assertFalse(b.containsKey("tools"))
        assertFalse(b.containsKey("tool_choice"))
        assertFalse(b.containsKey("cache_control"))
        assertFalse(b["stream"]!!.jsonPrimitive.boolean)
        assertEquals(1024, b["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("{\"type\":\"adaptive\"}", b["thinking"].toString())
        assertEquals("{\"effort\":\"low\"}", b["output_config"].toString())
        val sys = b["system"]!!.jsonArray.single().jsonObject
        assertEquals("Summarise.", sys["text"]!!.jsonPrimitive.content)
        assertNull(sys["cache_control"])
        assertEquals("User: hi\n\nCoach: hello", b["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun testBodyIsNonStreamedPingWithThinkingDisabled() {
        val b = RequestBuilder.buildTest(settings)
        assertFalse(b["stream"]!!.jsonPrimitive.boolean)
        assertEquals(32, b["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("{\"type\":\"disabled\"}", b["thinking"].toString())
        assertFalse(b.containsKey("tools"))
        assertFalse(b.containsKey("tool_choice"))
        assertEquals("ping", b["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun homeBridgeOmitsReadOnlyToolsAndDropsToolResults() {
        val bridge = settings.copy(backendMode = BackendMode.HOME_BRIDGE)
        val b = RequestBuilder.build(bridge, "S", ToolSchemas.all, history, newUser(RequestBuilder.textBlock("<delta schema=\"1\">{}</delta>"), RequestBuilder.textBlock("Thanks")))
        val names = b["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(16, names.size)
        assertTrue(names.none { it in RequestBuilder.READ_ONLY_TOOLS })
        val dropped = RequestBuilder.build(bridge, "S", ToolSchemas.all, history, newUser(*results))
        val last = dropped["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "text"), last.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun toolResultBlockCarriesIsErrorOnlyForErrors() {
        assertEquals("{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"x\"}", RequestBuilder.toolResultBlock("t", "x", false).toString())
        assertEquals("{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"x\",\"is_error\":true}", RequestBuilder.toolResultBlock("t", "x", true).toString())
    }
}
