// core/src/main/kotlin/com/gymcoach/core/nutrition/WeightTrend.kt
package com.gymcoach.core.nutrition

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.WeightTrendPoint
import com.gymcoach.core.planner.PlannerConstants
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * NUT-15: one point per weigh-in day (the latest entry of a day wins), 7-day simple moving average over the weigh-ins
 * of [date − 6, date], and a `noise` flag when the change from the previous day's weigh-in is ≥ nut15NoiseSingleDayPct
 * of body weight. Logged values are never altered (rawKg is the entered weight's kg value).
 */
class WeightTrend(private val c: PlannerConstants) {

    fun series(metrics: List<BodyMetric>): List<WeightTrendPoint> {
        val byDay = metrics.filter { it.weight != null }
            .groupBy { it.date }
            .map { (d, list) -> d to (list.maxByOrNull { it.loggedAt } ?: list.last()) }
            .sortedBy { it.first }
        val raw = byDay.map { (d, m) -> d to (m.weight?.kg ?: 0.0) }
        val out = ArrayList<WeightTrendPoint>()
        for ((i, p) in raw.withIndex()) {
            val (date, kg) = p
            val from = date.minusDays(6)
            val window = raw.filter { !it.first.isBefore(from) && !it.first.isAfter(date) }.map { it.second }
            val ma7 = round2(window.sum() / window.size)
            val prev = if (i > 0) raw[i - 1] else null
            val noise = prev != null && ChronoUnit.DAYS.between(prev.first, date) == 1L &&
                abs(kg - prev.second) >= prev.second * c.nut.nut15NoiseSingleDayPct / 100.0 - 1e-9
            out += WeightTrendPoint(date = date, rawKg = kg, ma7Kg = ma7, noise = noise)
        }
        return out
    }

    /** Δ(MA7) over `days` as % of body weight per week; null below nut15MinDays of data. */
    fun ratePctPerWeek(points: List<WeightTrendPoint>, days: Int = 14): Double? {
        val (first, last) = endpoints(points, days) ?: return null
        val span = ChronoUnit.DAYS.between(first.date, last.date).toDouble()
        val a = first.ma7Kg ?: return null
        val b = last.ma7Kg ?: return null
        if (span <= 0.0 || a <= 0.0) return null
        return (b - a) / a * 100.0 / (span / 7.0)
    }

    /** MA7 change over the last 14 days (kg); null below nut15MinDays of data. */
    fun delta14Kg(points: List<WeightTrendPoint>): Double? {
        val (first, last) = endpoints(points, DELTA_DAYS) ?: return null
        val a = first.ma7Kg ?: return null
        val b = last.ma7Kg ?: return null
        return round2(b - a)
    }

    /**
     * NUT-15 / NUT-14 (4) explanation text shown with the weight chart: compare 7-day averages, never single days; the
     * expected noise; how many weigh-ins carry the noise flag; and whether the ≥ nut15MinDays window for a calorie
     * adjustment (NUT-16) is reached. Purely descriptive — no value is changed.
     */
    fun explanation(points: List<WeightTrendPoint>): Explanation {
        val noiseDays = points.count { it.noise }
        val rate = ratePctPerWeek(points)
        val enough = rate != null
        val message = buildString {
            append("Compare 7-day averages, never single days: day-to-day noise is about 0.4–0.6 kg, the weekly cycle about 0.35 % of body weight, ")
            append("and +0.5–1.5 kg is normal after salty restaurant days or weekends.")
            if (noiseDays > 0) append(" $noiseDays weigh-in(s) changed by ≥ ${fmt(c.nut.nut15NoiseSingleDayPct)} % in a day and are treated as noise.")
            if (enough) append(" 14-day trend: ${fmt(rate ?: 0.0)} % per week.")
            else append(" Calorie targets are reviewed only after ${c.nut.nut15MinDays} days of weigh-ins.")
        }
        val inputs = LinkedHashMap<String, String>()
        inputs["weigh_ins"] = points.size.toString()
        inputs["noise_days"] = noiseDays.toString()
        if (rate != null) inputs["rate_pct_per_week"] = fmt(rate)
        return Explanations.of(listOf(RuleIds.NUT_15, RuleIds.NUT_14), message, inputs, Confidence.MODERATE)
    }

    private fun fmt(x: Double): String = String.format(java.util.Locale.ROOT, "%.2f", x)

    /** (oldest point inside [last − days, last], last point) when the data spans ≥ nut15MinDays calendar days. */
    private fun endpoints(points: List<WeightTrendPoint>, days: Int): Pair<WeightTrendPoint, WeightTrendPoint>? {
        val withMa = points.filter { it.ma7Kg != null }.sortedBy { it.date }
        if (withMa.size < 2) return null
        val last = withMa.last()
        val coverage = ChronoUnit.DAYS.between(withMa.first().date, last.date) + 1
        if (coverage < c.nut.nut15MinDays.toLong()) return null
        val from = last.date.minusDays(days.toLong())
        val first = withMa.firstOrNull { !it.date.isBefore(from) } ?: return null
        if (first.date == last.date) return null
        return first to last
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    private companion object {
        const val DELTA_DAYS: Int = 14
    }
}
