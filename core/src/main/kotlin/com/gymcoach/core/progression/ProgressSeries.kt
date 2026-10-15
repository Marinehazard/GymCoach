// core/src/main/kotlin/com/gymcoach/core/progression/ProgressSeries.kt
package com.gymcoach.core.progression

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.ChartPoint
import com.gymcoach.core.model.ChartSeries
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleBandRow
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.Side
import com.gymcoach.core.nutrition.WeightTrend
import com.gymcoach.core.planner.PlannerConstants
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Chart rows for the Progress / Nutrition screens (§2.14 Views). Pure: every series is sorted by date, one point per
 * day (or week). Set dates for the per-week volume series come from `SetLog.loggedAt` at UTC (the caller passes the
 * sets of the window; `volume` itself counts every set it is given).
 */
class ProgressSeries(private val kb: KnowledgeBase) {
    private val trend = WeightTrend(PlannerConstants.DEFAULT)

    /** (raw weigh-ins, MA7) as two series; raw points carry the NUT-15 noise flag in lowConfidence. */
    fun weight(metrics: List<BodyMetric>): Pair<ChartSeries, ChartSeries> {
        val points = trend.series(metrics)
        val raw = points.mapNotNull { p -> p.rawKg?.let { ChartPoint(p.date.toEpochDay(), it, p.noise) } }
        val ma = points.mapNotNull { p -> p.ma7Kg?.let { ChartPoint(p.date.toEpochDay(), it, false) } }
        return ChartSeries(SERIES_WEIGHT_RAW, raw) to ChartSeries(SERIES_WEIGHT_MA7, ma)
    }

    /** One point per session date with an e1RM over every state of the exercise (all gym scopes); LOW estimates → lowConfidence (dashed). */
    fun e1rm(states: List<ProgressionState>, exerciseId: String): ChartSeries {
        val byDay = LinkedHashMap<LocalDate, ChartPoint>()
        val entries = states.filter { it.key.exerciseId == exerciseId }.flatMap { it.history }.mapNotNull { s -> s.e1rm?.let { s.date to it } }
        for ((date, est) in entries.sortedBy { it.first }) {
            val low = est.confidence == E1rmConfidence.LOW
            val p = ChartPoint(date.toEpochDay(), est.kg, low)
            val cur = byDay[date]
            byDay[date] = when {
                cur == null -> p
                cur.lowConfidence && !low -> p                    // a HIGH estimate beats a LOW one on the same day
                !cur.lowConfidence && low -> cur
                p.value > cur.value -> p
                else -> cur
            }
        }
        return ChartSeries("$SERIES_E1RM_PREFIX$exerciseId", byDay.values.sortedBy { it.dateEpochDay })
    }

    /** Best load × reps per session for gym-scoped keys (E1RM-04). */
    fun loadXReps(state: ProgressionState): ChartSeries {
        val pts = state.history.mapNotNull { s -> s.loadXReps?.let { ChartPoint(s.date.toEpochDay(), it, false) } }
            .groupBy { it.dateEpochDay }.map { (_, v) -> v.maxByOrNull { it.value } ?: v.first() }
            .sortedBy { it.dateEpochDay }
        return ChartSeries("$SERIES_LOAD_X_REPS_PREFIX${state.key.id}", pts)
    }

    /** Weekly volume rows (tier order, then muscle order); fractional R4 §4.2 credit, LEFT/RIGHT paired (UNI-02). */
    fun volume(sets: List<SetLog>, weekStart: LocalDate, targets: Map<Muscle, Double>, tiers: Map<Muscle, MuscleTier>): List<MuscleBandRow> {
        val (fractional, direct) = tally(sets)
        return Muscle.values().sortedWith(compareBy<Muscle>({ (tiers[it] ?: MuscleTier.TIER_3).ordinal }, { it.ordinal })).map { m ->
            val tier = tiers[m] ?: MuscleTier.TIER_3
            val rp = kb.rpLandmarks[m]
            MuscleBandRow(
                muscle = m,
                tier = tier,
                target = if (tier == MuscleTier.TIER_3) null else targets[m],
                achievedFractional = round2(fractional[m] ?: 0.0),
                plannedRemaining = 0.0,
                direct = Math.floor((direct[m] ?: 0.0) + 1e-9).toInt(),
                rpMev = rp?.mev,
                rpMrv = rp?.mrv,
            )
        }
    }

    /** Fractional sets of `muscle` per week (week starts Monday) for the last `weeks` weeks ending with today's week, oldest first. */
    fun volumeByWeek(sets: List<SetLog>, weeks: Int, today: LocalDate, muscle: Muscle): ChartSeries {
        val thisWeek = monday(today)
        val pts = (weeks - 1 downTo 0).map { i ->
            val start = thisWeek.minusWeeks(i.toLong())
            val end = start.plusDays(6)
            val inWeek = sets.filter { s -> val d = s.loggedAt.atZone(ZoneOffset.UTC).toLocalDate(); !d.isBefore(start) && !d.isAfter(end) }
            ChartPoint(start.toEpochDay(), round2(tally(inWeek).first[muscle] ?: 0.0), false)
        }
        return ChartSeries("$SERIES_VOLUME_PREFIX${muscle.name.lowercase()}", pts)
    }

    /** RDY-01 score per day (the newest check-in of a day wins). */
    fun readiness(checkins: List<ReadinessCheckin>): ChartSeries {
        val pts = checkins.groupBy { it.date }.map { (d, v) -> ChartPoint(d.toEpochDay(), (v.maxByOrNull { it.loggedAt } ?: v.last()).score.toDouble(), false) }
            .sortedBy { it.dateEpochDay }
        return ChartSeries(SERIES_READINESS, pts)
    }

    fun restingHr(metrics: List<BodyMetric>): ChartSeries? {
        val pts = metrics.filter { it.restingHr != null }.groupBy { it.date }
            .mapNotNull { (d, v) -> (v.maxByOrNull { it.loggedAt } ?: v.last()).restingHr?.let { ChartPoint(d.toEpochDay(), it.toDouble(), false) } }
            .sortedBy { it.dateEpochDay }
        return if (pts.isEmpty()) null else ChartSeries(SERIES_RESTING_HR, pts)
    }

    /** RDY-06 Σ (session RPE × minutes) per week (Monday start) over COMPLETED sessions, last `weeks` weeks, oldest first. */
    fun trainingLoad(sessions: List<SessionRecord>, weeks: Int, today: LocalDate): List<Pair<LocalDate, Int>> {
        val thisWeek = monday(today)
        return (weeks - 1 downTo 0).map { i ->
            val start = thisWeek.minusWeeks(i.toLong())
            val end = start.plusDays(6)
            val load = sessions.filter { it.status == SessionStatus.COMPLETED && !it.date.isBefore(start) && !it.date.isAfter(end) }
                .sumOf { (it.sessionRpe ?: 0) * (it.durationMinutes ?: 0) }
            start to load
        }
    }

    /**
     * RDY-06 advisory "spike" note for the Progress training-load card: the newest week of [loads] (as returned by
     * [trainingLoad], oldest first) exceeds rdy06SpikeFactor × the mean of the [SPIKE_BASELINE_WEEKS] weeks before it.
     * Null without a full positive baseline or below the threshold. Advice only — never a readiness modifier.
     */
    fun trainingLoadSpike(loads: List<Pair<LocalDate, Int>>, c: PlannerConstants = PlannerConstants.DEFAULT): Explanation? {
        if (loads.size < SPIKE_BASELINE_WEEKS + 1) return null
        val (week, load) = loads.last()
        val baseline = loads.subList(loads.size - 1 - SPIKE_BASELINE_WEEKS, loads.size - 1).map { it.second }
        val mean = baseline.sum().toDouble() / baseline.size
        if (mean <= 0.0 || load <= c.rdy.rdy06SpikeFactor * mean) return null
        val pct = Math.round(load * 100.0 / mean).toInt()
        return Explanations.of(
            listOf(RuleIds.RDY_06),
            "Training load this week is $pct % of the previous 4-week mean (spike above ${Math.round(c.rdy.rdy06SpikeFactor * 100).toInt()} %) — advice only: consider an easier next week.",
            mapOf("week_start" to week.toString(), "load" to load.toString(), "baseline_mean" to Math.round(mean).toString()),
            Confidence.CONTESTED,
        )
    }

    /** (fractional, direct) per muscle: WORKING/AMRAP/CALIBRATION/DROP, non-superseded; LEFT+RIGHT of one set index count once, an unpaired side 0.5. */
    private fun tally(sets: List<SetLog>): Pair<Map<Muscle, Double>, Map<Muscle, Double>> {
        val frac = HashMap<Muscle, Double>()
        val direct = HashMap<Muscle, Double>()
        val counted = sets.filter { it.supersededById == null && it.kind in COUNTED }
        val groups = counted.groupBy { "${it.sessionId}|${it.blockId ?: it.exerciseId}|${it.setIndex}" }
        for ((_, list) in groups) {
            val ex = kb.exercise(list.first().exerciseId) ?: continue
            val both = list.count { it.side == Side.BOTH }
            val l = list.count { it.side == Side.LEFT }
            val r = list.count { it.side == Side.RIGHT }
            val paired = minOf(l, r)
            val weight = both + paired + 0.5 * ((l - paired) + (r - paired))
            for (mw in ex.muscles) {
                frac[mw.muscle] = (frac[mw.muscle] ?: 0.0) + mw.weight * weight
                if (mw.weight >= 1.0) direct[mw.muscle] = (direct[mw.muscle] ?: 0.0) + weight
            }
        }
        return frac to direct
    }

    private fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    companion object {
        const val SERIES_WEIGHT_RAW: String = "weight_raw"
        const val SERIES_WEIGHT_MA7: String = "weight_ma7"
        const val SERIES_E1RM_PREFIX: String = "e1rm:"
        const val SERIES_LOAD_X_REPS_PREFIX: String = "load_x_reps:"
        const val SERIES_VOLUME_PREFIX: String = "volume:"
        const val SERIES_READINESS: String = "readiness"
        const val SERIES_RESTING_HR: String = "resting_hr"
        const val SPIKE_BASELINE_WEEKS: Int = 4
        private val COUNTED: Set<SetKind> = setOf(SetKind.WORKING, SetKind.AMRAP, SetKind.CALIBRATION, SetKind.DROP)
    }
}
