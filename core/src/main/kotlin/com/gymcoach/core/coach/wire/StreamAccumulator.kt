// core/src/main/kotlin/com/gymcoach/core/coach/wire/StreamAccumulator.kt
package com.gymcoach.core.coach.wire

import com.gymcoach.core.coach.TurnEvent
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.TokenUsage
import com.gymcoach.core.repo.BackendError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Folds Messages-API stream events (R1 §3.6) into one assistant message (§6.3 table). Dispatches on the JSON `type`
 * field, never on the SSE event name. Emits [TurnEvent.TextDelta] per `text_delta`, [TurnEvent.ToolUseStarted] per
 * opened `tool_use` block and a terminal [TurnEvent.Failed] for an `error` event. Completed `tool_use` blocks are
 * queued for the caller ([takeCompletedToolUses]) because only the turn service can turn them into
 * [TurnEvent.ToolUseComplete] (it needs the proposal / read-only result). After `message_stop`, [result] returns the
 * verbatim content array (thinking / signature / redacted_thinking blocks kept unchanged, tool inputs parsed).
 */
class StreamAccumulator {
    private class Block(val index: Int, val start: JsonObject) {
        val type: String = (start["type"] as? JsonPrimitive)?.contentOrNull ?: ""
        val text = StringBuilder((start["text"] as? JsonPrimitive)?.contentOrNull ?: "")
        val thinking = StringBuilder((start["thinking"] as? JsonPrimitive)?.contentOrNull ?: "")
        val signature = StringBuilder((start["signature"] as? JsonPrimitive)?.contentOrNull ?: "")
        val json = StringBuilder()
        var input: JsonObject? = null
        var inputError: String? = null
        var closed = false
    }

    private val blocks = LinkedHashMap<Int, Block>()
    private val completed = ArrayList<ToolUseBlock>()
    private var id: String? = null
    private var model: String? = null
    private var stopReason: String? = null
    private var stopDetails: JsonObject? = null
    private var usage = TokenUsage()
    private var unknown = 0
    private var hasOutput = false

    var isComplete: Boolean = false
        private set
    var error: BackendError? = null
        private set

    /** True once any text, thinking or tool content has streamed (a refusal/failure then discards partial output). */
    val producedOutput: Boolean get() = hasOutput

    fun accept(event: JsonObject): List<TurnEvent> {
        val type = str(event["type"]) ?: run { unknown++; return emptyList() }
        val out = ArrayList<TurnEvent>()
        when (type) {
            "message_start" -> {
                val msg = event["message"] as? JsonObject
                if (msg != null) {
                    id = str(msg["id"])
                    model = str(msg["model"])
                    (msg["usage"] as? JsonObject)?.let { usage = mergeUsage(usage, it) }
                    str(msg["stop_reason"])?.let { stopReason = it }
                }
            }
            "content_block_start" -> {
                val index = int(event["index"]) ?: return out
                val cb = event["content_block"] as? JsonObject ?: return out
                val b = Block(index, cb)
                blocks[index] = b
                if (b.type == "tool_use") {
                    hasOutput = true
                    out.add(TurnEvent.ToolUseStarted(str(cb["id"]) ?: "", str(cb["name"]) ?: ""))
                }
                if (b.text.isNotEmpty()) hasOutput = true
            }
            "content_block_delta" -> {
                val index = int(event["index"]) ?: return out
                val b = blocks[index] ?: return out
                val delta = event["delta"] as? JsonObject ?: return out
                when (str(delta["type"])) {
                    "text_delta" -> {
                        val t = str(delta["text"]) ?: ""
                        b.text.append(t)
                        if (t.isNotEmpty()) {
                            hasOutput = true
                            out.add(TurnEvent.TextDelta(t))
                        }
                    }
                    "input_json_delta" -> b.json.append(str(delta["partial_json"]) ?: "")
                    "thinking_delta" -> {
                        b.thinking.append(str(delta["thinking"]) ?: "")
                        hasOutput = true
                    }
                    "signature_delta" -> b.signature.append(str(delta["signature"]) ?: "")
                    else -> Unit      // citations_delta and future delta types: ignored
                }
            }
            "content_block_stop" -> {
                val index = int(event["index"]) ?: return out
                val b = blocks[index] ?: return out
                b.closed = true
                if (b.type == "tool_use") {
                    val buf = b.json.toString()
                    if (buf.isBlank()) {
                        b.input = JsonObject(emptyMap())
                    } else {
                        try {
                            b.input = CoreJson.wire.parseToJsonElement(buf) as? JsonObject
                            if (b.input == null) {
                                b.input = JsonObject(emptyMap())
                                b.inputError = "tool input is not a JSON object"
                            }
                        } catch (e: Exception) {
                            b.input = JsonObject(emptyMap())
                            b.inputError = "tool input is not valid JSON"
                        }
                    }
                    completed.add(ToolUseBlock(index, str(b.start["id"]) ?: "", str(b.start["name"]) ?: "", b.input ?: JsonObject(emptyMap()), b.inputError))
                }
            }
            "message_delta" -> {
                val delta = event["delta"] as? JsonObject
                if (delta != null) {
                    str(delta["stop_reason"])?.let { stopReason = it }
                    val sd = delta["stop_details"]
                    if (sd is JsonObject) stopDetails = sd
                }
                val sdTop = event["stop_details"]
                if (sdTop is JsonObject) stopDetails = sdTop
                (event["usage"] as? JsonObject)?.let { usage = mergeUsage(usage, it) }
            }
            "message_stop" -> isComplete = true
            "ping" -> Unit
            "error" -> {
                val err = event["error"] as? JsonObject
                val errType = str(err?.get("type")) ?: "error"
                val message = str(err?.get("message")) ?: ""
                val e = if (errType == "overloaded_error") BackendError.Overloaded(midStream = true) else BackendError.StreamError(errType, message)
                error = e
                out.add(TurnEvent.Failed(e, draftKept = true))
            }
            else -> unknown++
        }
        return out
    }

    /** Tool-use blocks completed since the last call, in completion order. */
    fun takeCompletedToolUses(): List<ToolUseBlock> {
        val copy = ArrayList(completed)
        completed.clear()
        return copy
    }

    /** All tool_use blocks of the message in content (index) order. */
    fun toolUses(): List<ToolUseBlock> = blocks.values.sortedBy { it.index }.filter { it.type == "tool_use" }.map {
        ToolUseBlock(it.index, str(it.start["id"]) ?: "", str(it.start["name"]) ?: "", it.input ?: JsonObject(emptyMap()), it.inputError)
    }

    fun result(): AccumulatedMessage =
        AccumulatedMessage(id, model, buildContent(), stopReason, stopDetails, usage, unknown)

    private fun buildContent(): JsonArray {
        val out = ArrayList<JsonElement>()
        for (b in blocks.values.sortedBy { it.index }) {
            val m = LinkedHashMap<String, JsonElement>(b.start)
            when (b.type) {
                "text" -> m["text"] = JsonPrimitive(b.text.toString())
                "thinking" -> {
                    m["thinking"] = JsonPrimitive(b.thinking.toString())
                    m["signature"] = JsonPrimitive(b.signature.toString())
                }
                "tool_use" -> m["input"] = b.input ?: parsedOrEmpty(b.json.toString())
                else -> Unit      // redacted_thinking and unknown block types: echoed exactly as received
            }
            out.add(JsonObject(m))
        }
        return JsonArray(out)
    }

    private fun parsedOrEmpty(buf: String): JsonObject =
        if (buf.isBlank()) JsonObject(emptyMap()) else try { CoreJson.wire.parseToJsonElement(buf) as? JsonObject ?: JsonObject(emptyMap()) } catch (e: Exception) { JsonObject(emptyMap()) }

    companion object {
        /** Cumulative merge (R1 §3.6): a field present in [u] replaces the previous value; thinking tokens come from output_tokens_details. */
        fun mergeUsage(prev: TokenUsage, u: JsonObject): TokenUsage {
            val details = u["output_tokens_details"] as? JsonObject
            return TokenUsage(
                inputTokens = int(u["input_tokens"]) ?: prev.inputTokens,
                outputTokens = int(u["output_tokens"]) ?: prev.outputTokens,
                cacheReadInputTokens = int(u["cache_read_input_tokens"]) ?: prev.cacheReadInputTokens,
                cacheCreationInputTokens = int(u["cache_creation_input_tokens"]) ?: prev.cacheCreationInputTokens,
                thinkingTokens = int(details?.get("thinking_tokens")) ?: prev.thinkingTokens,
            )
        }

        private fun str(e: JsonElement?): String? = if (e == null || e is JsonNull) null else (e as? JsonPrimitive)?.contentOrNull
        private fun int(e: JsonElement?): Int? = if (e == null || e is JsonNull) null else (e as? JsonPrimitive)?.intOrNull
    }
}
