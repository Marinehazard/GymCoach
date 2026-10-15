// core/src/main/kotlin/com/gymcoach/core/model/Values.kt
package com.gymcoach.core.model

import com.gymcoach.core.json.BandSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Inclusive integer range, encoded as [lo, hi]. */
@Serializable(with = BandSerializer::class)
data class Band(val lo: Int, val hi: Int) {
    init { require(lo <= hi) { "Band lo > hi: $lo > $hi" } }
    operator fun contains(v: Int): Boolean = v in lo..hi
    fun shifted(byLo: Int, byHi: Int): Band = Band(lo + byLo, hi + byHi)
}

/** Why something is the way it is. ruleIds are R3 §10 IDs or planner convention ids (SEL, SUB, REBAL, TIME-L1..7). */
@Serializable
data class Explanation(
    @SerialName("rule_ids") val ruleIds: List<String>,
    val message: String,
    val inputs: Map<String, String> = emptyMap(),
    val confidence: Confidence? = null,
)

/** What the user typed, verbatim, plus the derived SI value (sacred content; decision record H18). */
@Serializable
data class EnteredWeight(val entered: Double, val unit: WeightUnit, val kg: Double)

@Serializable
data class EnteredLength(val entered: Double, val unit: LengthUnit, val cm: Double)
