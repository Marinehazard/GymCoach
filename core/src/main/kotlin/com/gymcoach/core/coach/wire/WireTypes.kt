// core/src/main/kotlin/com/gymcoach/core/coach/wire/WireTypes.kt
package com.gymcoach.core.coach.wire

import com.gymcoach.core.model.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable data class CacheControl(val type: String = "ephemeral")
@Serializable data class SystemBlock(val type: String = "text", val text: String, @SerialName("cache_control") val cacheControl: CacheControl? = null)
@Serializable data class ToolDefinition(val name: String, val description: String, val strict: Boolean = true, @SerialName("input_schema") val inputSchema: JsonObject)
@Serializable data class MessageParam(val role: String, val content: JsonArray)     // content echoed verbatim

@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val stream: Boolean = true,
    val system: List<SystemBlock>,
    val tools: List<ToolDefinition>? = null,                 // null = omitted (compaction and Test requests; explicitNulls = false)
    @SerialName("tool_choice") val toolChoice: JsonObject? = null,   // null = omitted; never sent without `tools` (400)
    val thinking: JsonObject,
    @SerialName("output_config") val outputConfig: JsonObject,
    @SerialName("cache_control") val cacheControl: CacheControl? = CacheControl(),   // null for the non-streamed one-off requests
    val metadata: JsonObject? = null,                        // omitted while settings.coachUserId is blank
    val messages: List<MessageParam>,
)

/** Accumulated assistant message (R1 §3.7 shape), content kept verbatim as JsonArray. */
data class AccumulatedMessage(
    val id: String?,
    val model: String?,
    val content: JsonArray,
    val stopReason: String?,
    val stopDetails: JsonObject?,
    val usage: TokenUsage,
    val unknownEventTypes: Int,
)

/** One SSE event as dispatched by [SseParser]: `event:` name (null when absent) and the joined `data:` payload. */
data class SseEvent(val name: String?, val data: String)

/** A completed `tool_use` content block (input parsed at `content_block_stop`; an empty buffer parses to `{}`; unparsable JSON → `{}` plus [inputError]). */
data class ToolUseBlock(val index: Int, val id: String, val name: String, val input: JsonObject, val inputError: String? = null)
