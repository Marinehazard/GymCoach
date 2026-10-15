package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.GoalPrescription
import com.gymcoach.core.planner.PlannerConstants
import kotlin.math.ceil

data class RirInput(
    val profileTargetRir: Int,
    val weekOffset: Int,
    val readinessOffset: Int,
    val layoffOffset: Int,
    val sleepOffset: Int,
    val stressOffset: Int,
    val keepAtMaxOffset: Int,
    val floor: Int,
    val fixedDeloadRir: Int?,
    val fixedEnduranceRir: Int?,
)

/**
 * §4.7 RIR stack (H17): finalRir = clamp(base + week + readiness + layoff + sleep + stress + keepAtMax, floor, rirCapMax).
 * DELOAD weeks (rir04DeloadRir) and the light-load endurance fallback (trv01EnduranceRir) are fixed and bypass the stack.
 * When the sum exceeds the cap the RIR-CAP explanation is returned (DEF-04: a working set is ≤ 4 RIR).
 */
object RirStack {
    fun finalRir(input: RirInput, c: PlannerConstants): Pair<Int, Explanation?> {
        input.fixedDeloadRir?.let { return it to null }
        input.fixedEnduranceRir?.let { return it to null }
        val sum = input.profileTargetRir + input.weekOffset + input.readinessOffset + input.layoffOffset + input.sleepOffset + input.stressOffset + input.keepAtMaxOffset
        val cap = c.rir.rirCapMax
        val floor = minOf(input.floor, cap)
        if (sum > cap) {
            return cap to Explanations.of(
                listOf(RuleIds.RIR_CAP, RuleIds.DEF_04),
                "RIR capped at $cap (a working set stays within $cap reps of failure; the modifiers summed to $sum).",
                mapOf("sum" to sum.toString(), "cap" to cap.toString()),
                Confidence.WEAK,
            )
        }
        return maxOf(floor, sum) to null
    }

    /** ceil((rir.lo + rir.hi) / 2): the band's middle is the mid-mesocycle target (RIR-01/03). */
    fun profileTargetRir(prescription: GoalPrescription): Int = ceil((prescription.rir.lo + prescription.rir.hi) / 2.0).toInt()

    /** RIR-04 last accumulation week: rirLastWeek.hi, else profileTargetRir − 1 (never below 0). */
    fun lastWeekTargetRir(prescription: GoalPrescription): Int =
        prescription.rirLastWeek?.hi ?: maxOf(0, profileTargetRir(prescription) - 1)
}
