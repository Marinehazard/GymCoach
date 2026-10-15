// core/src/main/kotlin/com/gymcoach/core/model/Views.kt
package com.gymcoach.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One chart sample; dateEpochDay = LocalDate.toEpochDay(). lowConfidence → dashed segment (E1RM-02 LOW). */
@Serializable
data class ChartPoint(
    @SerialName("date_epoch_day") val dateEpochDay: Long,
    val value: Double,
    @SerialName("low_confidence") val lowConfidence: Boolean = false,
)

@Serializable
data class ChartSeries(val name: String, val points: List<ChartPoint>)

/** One row of the weekly ledger / Progress volume card. target == null for TIER_3 (no target, advisory cap only). */
@Serializable
data class MuscleBandRow(
    val muscle: Muscle,
    val tier: MuscleTier,
    val target: Double? = null,
    @SerialName("achieved_fractional") val achievedFractional: Double,
    @SerialName("planned_remaining") val plannedRemaining: Double,
    val direct: Int,
    @SerialName("rp_mev") val rpMev: Band? = null,
    @SerialName("rp_mrv") val rpMrv: Band? = null,
)

/** One supplement line as rendered on the Nutrition screen (§5.6); doses are already computed text. */
@Serializable
data class SupplementAdvice(
    val id: String,                      // SupplementEntry.id, e.g. "SUP-01"
    val name: String,
    val tier: String,                    // "A" | "B" | "C"
    @SerialName("dose_text") val doseText: String,        // "5 g/day" — or the RED-03 gate text when gated
    val timing: String,
    val evidence: Confidence,
    val citation: String,
    val gated: Boolean,                  // RED-03: medical flag without clearance, or gated_by_medical hit → no numbers
    val notes: List<String> = emptyList(),
    @SerialName("legal_note") val legalNote: String? = null,
    @SerialName("do_not_reason") val doNotReason: String? = null,   // tier C
)
