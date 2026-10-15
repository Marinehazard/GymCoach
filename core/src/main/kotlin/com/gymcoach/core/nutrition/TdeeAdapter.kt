// core/src/main/kotlin/com/gymcoach/core/nutrition/TdeeAdapter.kt
package com.gymcoach.core.nutrition

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.EnergyModel
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.TdeeAdjustment
import com.gymcoach.core.model.WeightTrendPoint
import com.gymcoach.core.planner.PlannerConstants
import java.time.temporal.ChronoUnit
import java.time.LocalDate

/**
 * NUT-04 adaptive TDEE (H14): over the last nut04WindowDays days, observed = Δ(MA7) per week, expected =
 * (mean intake − TDEE) × 7 / 7,700, correction = clamp((expected − observed) × 7,700 / 7, ±nut04ClampKcal).
 * The caller stores `model.tdeeEstimateKcal + deltaKcal` in NutritionState.tdeeEstimateKcal.
 */
class TdeeAdapter(private val c: PlannerConstants) {

    fun review(model: EnergyModel, logs: List<NutritionLog>, trend: List<WeightTrendPoint>, today: LocalDate): TdeeAdjustment? {
        val from = today.minusDays((c.nut.nut04WindowDays - 1).toLong())
        fun inWindow(d: LocalDate): Boolean = !d.isBefore(from) && !d.isAfter(today)
        val windowLogs = logs.filter { inWindow(it.date) }.groupBy { it.date }.map { (_, v) -> v.maxByOrNull { it.loggedAt } ?: v.last() }
        val kcalLogs = windowLogs.mapNotNull { it.kcal }
        if (kcalLogs.size < c.nut.nut04MinKcalLogs) return null
        if (windowLogs.count { it.travelDay } >= TRAVEL_DAYS_SKIP) return null
        val points = trend.filter { inWindow(it.date) }.sortedBy { it.date }
        if (points.count { it.rawKg != null } < MIN_WEIGH_INS) return null
        val withMa = points.filter { it.ma7Kg != null }
        if (withMa.size < 2) return null
        val first = withMa.first()
        val last = withMa.last()
        val span = ChronoUnit.DAYS.between(first.date, last.date).toDouble()
        if (span <= 0.0) return null
        val observed = ((last.ma7Kg ?: 0.0) - (first.ma7Kg ?: 0.0)) / span * 7.0
        val meanIntake = kcalLogs.sum().toDouble() / kcalLogs.size
        val kcalPerKg = c.nut.nut04KcalPerKg.toDouble()
        val expected = (meanIntake - model.tdeeEstimateKcal) * 7.0 / kcalPerKg
        val raw = (expected - observed) * kcalPerKg / 7.0
        val clamp = c.nut.nut04ClampKcal.toDouble()
        val delta = Math.round(raw.coerceIn(-clamp, clamp)).toInt()
        val newTdee = model.tdeeEstimateKcal + delta
        return TdeeAdjustment(
            date = today,
            observedRateKgPerWeek = round3(observed),
            expectedRateKgPerWeek = round3(expected),
            deltaKcal = delta,
            explanation = Explanations.of(
                listOf(RuleIds.NUT_04),
                "Mean intake ${Math.round(meanIntake)} kcal vs maintenance ${model.tdeeEstimateKcal}: expected ${fmt(expected)} kg/week, " +
                    "observed ${fmt(observed)} kg/week → maintenance ${if (delta >= 0) "+" else ""}$delta kcal = $newTdee kcal (step limited to ±${c.nut.nut04ClampKcal})",
                mapOf(
                    "mean_intake_kcal" to Math.round(meanIntake).toString(), "kcal_logs" to kcalLogs.size.toString(),
                    "observed_kg_per_week" to fmt(observed), "expected_kg_per_week" to fmt(expected),
                    "raw_correction_kcal" to Math.round(raw).toString(), "new_tdee_kcal" to newTdee.toString(),
                ),
                Confidence.MODERATE,
            ),
        )
    }

    private fun round3(x: Double): Double = Math.round(x * 1000.0) / 1000.0
    private fun fmt(x: Double): String = String.format(java.util.Locale.ROOT, "%.3f", x)

    private companion object {
        const val TRAVEL_DAYS_SKIP: Int = 3
        const val MIN_WEIGH_INS: Int = 10
    }
}
