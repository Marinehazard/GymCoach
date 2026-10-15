// core/src/main/kotlin/com/gymcoach/core/coach/TurnEvent.kt
package com.gymcoach.core.coach

import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.TokenUsage
import com.gymcoach.core.repo.BackendError

sealed class TurnEvent {
    data class TextDelta(val text: String) : TurnEvent()
    data class ToolUseStarted(val toolUseId: String, val name: String) : TurnEvent()
    /** One per completed tool_use block; readOnlyResult is the ToolExecution JSON for read-only tools. */
    data class ToolUseComplete(val proposal: Proposal?, val readOnlyResult: String?, val error: String?) : TurnEvent()
    /** One per API response in the chain (§6.7 step 4), i.e. one per SpendRecord. */
    data class Usage(val usage: TokenUsage, val costUsd: Double) : TurnEvent()
    /** Emitted exactly ONCE per user turn, after the whole read-only follow-up chain has ended; assistantMessageId = the last assistant row — null ONLY for stopReason "red_flag" (§6.7 step 0: nothing was sent, no assistant row exists). */
    data class Done(val stopReason: String, val stopDetailsJson: String?, val assistantMessageId: String?) : TurnEvent()
    data class Failed(val error: BackendError, val draftKept: Boolean) : TurnEvent()
}
