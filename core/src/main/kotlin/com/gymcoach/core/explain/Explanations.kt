// core/src/main/kotlin/com/gymcoach/core/explain/Explanations.kt
package com.gymcoach.core.explain

import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation

object Explanations {
    fun of(ruleId: String, message: String, vararg inputs: Pair<String, String>, confidence: Confidence? = null): Explanation =
        Explanation(listOf(ruleId), message, inputs.toMap(), confidence)
    fun of(ruleIds: List<String>, message: String, inputs: Map<String, String> = emptyMap(), confidence: Confidence? = null): Explanation =
        Explanation(ruleIds, message, inputs, confidence)
}
