// core/src/main/kotlin/com/gymcoach/core/coach/wire/RequestBuilder.kt
package com.gymcoach.core.coach.wire

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.Effort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Builds the exact Messages-API request bodies of §6.2 / §6.3. Never sends temperature, top_p, top_k,
 * stop_sequences or an assistant prefill; `system` and `tools` are byte-identical for identical inputs;
 * `metadata` is omitted while `settings.coachUserId` is blank. The model and effort come from [AppSettings] — the
 * turn service passes a copy carrying the conversation's frozen model/effort/backend (§6.9).
 */
object RequestBuilder {
    const val MAX_TOKENS: Int = 4096
    const val COMPACTION_MAX_TOKENS: Int = 1024
    const val TEST_MAX_TOKENS: Int = 32
    val READ_ONLY_TOOLS: Set<String> = setOf("lookup_exercises", "get_exercise_history", "get_rule", "get_gym_equipment")

    fun build(settings: AppSettings, systemText: String, tools: List<ToolDefinition>?, history: List<ChatMessage>, newUserContent: JsonArray): JsonObject {
        val bridge = settings.backendMode == BackendMode.HOME_BRIDGE
        val ordered = history.sortedBy { it.seq }
        val messages = ArrayList<MessageParam>()
        for (m in ordered) {
            val content = parseContent(m.contentJson)
            val role = if (m.role == ChatRole.USER) "user" else "assistant"
            val c = if (bridge && m.role == ChatRole.USER) dropToolResults(content) else content
            if (c.isEmpty()) continue
            messages.add(MessageParam(role, c))
        }
        val lastAssistant = ordered.lastOrNull()?.takeIf { it.role == ChatRole.ASSISTANT }
        val newContent = if (bridge) dropToolResults(newUserContent) else newUserContent
        require(newContent.isNotEmpty()) { "the new user message is empty" }
        if (!bridge) checkToolResults(lastAssistant?.let { parseContent(it.contentJson) }, newContent)
        messages.add(MessageParam("user", newContent))

        val declared = tools?.let { list -> if (bridge) list.filter { it.name !in READ_ONLY_TOOLS } else list }?.takeIf { it.isNotEmpty() }
        val request = MessagesRequest(
            model = settings.modelId,
            maxTokens = MAX_TOKENS,
            stream = true,
            system = listOf(SystemBlock(text = systemText, cacheControl = CacheControl())),
            tools = declared,
            toolChoice = if (declared != null) obj("type" to JsonPrimitive("auto")) else null,
            thinking = obj("type" to JsonPrimitive("adaptive")),
            outputConfig = obj("effort" to JsonPrimitive(effortName(settings.effort))),
            cacheControl = CacheControl(),
            metadata = metadata(settings),
            messages = messages,
        )
        return encode(request)
    }

    fun buildCompaction(settings: AppSettings, compactionPrompt: String, transcriptText: String): JsonObject {
        val request = MessagesRequest(
            model = settings.modelId,
            maxTokens = COMPACTION_MAX_TOKENS,
            stream = false,
            system = listOf(SystemBlock(text = compactionPrompt, cacheControl = null)),
            tools = null,
            toolChoice = null,
            thinking = obj("type" to JsonPrimitive("adaptive")),
            outputConfig = obj("effort" to JsonPrimitive("low")),
            cacheControl = null,
            metadata = metadata(settings),
            messages = listOf(MessageParam("user", JsonArray(listOf(textBlock(transcriptText.ifEmpty { "(empty conversation)" }))))),
        )
        return encode(request)
    }

    fun buildTest(settings: AppSettings): JsonObject {
        val request = MessagesRequest(
            model = settings.modelId,
            maxTokens = TEST_MAX_TOKENS,
            stream = false,
            system = listOf(SystemBlock(text = "Connection test. Reply with one word.", cacheControl = null)),
            tools = null,
            toolChoice = null,
            thinking = obj("type" to JsonPrimitive("disabled")),
            outputConfig = obj("effort" to JsonPrimitive("low")),
            cacheControl = null,
            metadata = metadata(settings),
            messages = listOf(MessageParam("user", JsonArray(listOf(textBlock("ping"))))),
        )
        return encode(request)
    }

    /** `{"type":"text","text":…}`. */
    fun textBlock(text: String): JsonObject = obj("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))

    /** `{"type":"tool_result","tool_use_id":…,"content":"…"}` plus `"is_error":true` only for errors. */
    fun toolResultBlock(toolUseId: String, content: String, isError: Boolean): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["type"] = JsonPrimitive("tool_result")
        m["tool_use_id"] = JsonPrimitive(toolUseId)
        m["content"] = JsonPrimitive(content)
        if (isError) m["is_error"] = JsonPrimitive(true)
        return JsonObject(m)
    }

    /** The tool_use blocks of an assistant content array, in content order: (id, name). */
    fun toolUses(content: JsonArray): List<Pair<String, String>> = content.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        if (typeOf(o) != "tool_use") return@mapNotNull null
        Pair((o["id"] as? JsonPrimitive)?.contentOrNull ?: "", (o["name"] as? JsonPrimitive)?.contentOrNull ?: "")
    }

    fun parseContent(json: String): JsonArray = CoreJson.wire.parseToJsonElement(json).jsonArray

    /** Canonical compact text of a content array (what is stored as ChatMessage.contentJson and re-sent). */
    fun contentJson(content: JsonArray): String = CoreJson.wire.encodeToString(JsonElement.serializer(), content)

    private fun checkToolResults(lastAssistant: JsonArray?, newContent: JsonArray) {
        val uses = lastAssistant?.let { toolUses(it) } ?: emptyList()
        var firstNonResult = newContent.size
        newContent.forEachIndexed { i, el -> if (firstNonResult == newContent.size && typeOf(el as? JsonObject) != "tool_result") firstNonResult = i }
        val results = newContent.take(firstNonResult).map { ((it as JsonObject)["tool_use_id"] as? JsonPrimitive)?.contentOrNull ?: "" }
        require(newContent.drop(firstNonResult).none { typeOf(it as? JsonObject) == "tool_result" }) { "tool_result blocks must come first in the user content" }
        require(results == uses.map { it.first }) { "one tool_result per tool_use of the last assistant message, in content order: expected ${uses.map { it.first }}, got $results" }
    }

    private fun dropToolResults(content: JsonArray): JsonArray = JsonArray(content.filter { typeOf(it as? JsonObject) != "tool_result" })

    private fun typeOf(o: JsonObject?): String? = (o?.get("type") as? JsonPrimitive)?.contentOrNull

    private fun metadata(settings: AppSettings): JsonObject? =
        if (settings.coachUserId.isBlank()) null else obj("user_id" to JsonPrimitive(settings.coachUserId))

    private fun effortName(e: Effort): String = when (e) {
        Effort.LOW -> "low"
        Effort.MEDIUM -> "medium"
    }

    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

    private fun encode(request: MessagesRequest): JsonObject =
        CoreJson.wire.encodeToJsonElement(MessagesRequest.serializer(), request).jsonObject
}
