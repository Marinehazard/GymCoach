package com.gymcoach.core.nutrition

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.WeightTrendPoint
import com.gymcoach.core.model.WeightUnit
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NUT-04 REE / activity factor / adaptive TDEE (G4 −150 → 2,609), NUT-15 weight trend. */
class EnergyAndTrendTest {
    private val energy = EnergyEstimator(NF.c)
    private val adapter = TdeeAdapter(NF.c)
    private val trend = WeightTrend(NF.c)

    @Test fun activityFactorTable() {
        assertEquals(1.2, energy.activityFactor(1, 3000, false))
        assertEquals(1.375, energy.activityFactor(3, null, false))
        assertEquals(1.375, energy.activityFactor(0, 6000, false))
        assertEquals(1.55, energy.activityFactor(4, null, false))
        assertEquals(1.55, energy.activityFactor(1, 9000, false))
        assertEquals(1.55, energy.activityFactor(0, null, true))
        assertEquals(1.725, energy.activityFactor(6, null, false))
        assertEquals(1.725, energy.activityFactor(3, null, true))
        assertEquals(1.9, energy.model(NF.p1.copy(activityFactorOverride = 1.9), 84.0, 38, 4, null, null).activityFactor)
    }

    @Test fun storedAdaptiveTdeeWins() {
        val m = energy.model(NF.p1, 84.0, 38, 4, null, NutritionState(tdeeEstimateKcal = 2609, updatedAt = NF.t0))
        assertEquals(1780, m.reeKcal)
        assertEquals(2609, m.tdeeEstimateKcal)
        assertEquals(listOf(RuleIds.NUT_04), m.explanation.ruleIds)
    }

    private fun window(days: Int = 14, kcal: Int? = 3000, travel: Int = 0): Pair<List<NutritionLog>, List<WeightTrendPoint>> {
        val start = NF.today.minusDays((days - 1).toLong())
        val logs = (0 until days).map { i ->
            val d = start.plusDays(i.toLong())
            NutritionLog("n$i", d, kcal = kcal, travelDay = i < travel, loggedAt = d.atTime(20, 0).toInstant(ZoneOffset.UTC))
        }
        // MA7 rising exactly 0.6 kg/week.
        val pts = (0 until days).map { i -> WeightTrendPoint(start.plusDays(i.toLong()), 84.0, 84.0 + 0.6 / 7.0 * i) }
        return logs to pts
    }

    @Test fun g4AdaptiveTdeeClampedMinus150() {
        val model = energy.model(NF.p1, 84.0, 38, 4, null, null)
        val (logs, pts) = window()
        val adj = adapter.review(model, logs, pts, NF.today)
        assertNotNull(adj)
        assertEquals(0.219, adj.expectedRateKgPerWeek)
        assertEquals(0.6, adj.observedRateKgPerWeek)
        assertEquals(-150, adj.deltaKcal)
        assertEquals(2609, model.tdeeEstimateKcal + adj.deltaKcal)
        assertEquals("-419", adj.explanation.inputs["raw_correction_kcal"])
    }

    @Test fun adaptiveNeedsTenKcalLogsAndSkipsTravelWeeks() {
        val model = energy.model(NF.p1, 84.0, 38, 4, null, null)
        val (logs, pts) = window()
        assertNull(adapter.review(model, logs.mapIndexed { i, l -> if (i < 5) l.copy(kcal = null) else l }, pts, NF.today))
        assertNotNull(adapter.review(model, logs.mapIndexed { i, l -> if (i < 4) l.copy(kcal = null) else l }, pts, NF.today))
        val (tl, tp) = window(travel = 3)
        assertNull(adapter.review(model, tl, tp, NF.today))
        assertNull(adapter.review(model, logs, pts.take(9), NF.today), "≥ 10 weigh-ins required")
    }

    private fun metrics(kgs: List<Double>, start: LocalDate = NF.today.minusDays((kgs.size - 1).toLong())) = kgs.mapIndexed { i, k ->
        val d = start.plusDays(i.toLong())
        BodyMetric("w$i", d, weight = EnteredWeight(k, WeightUnit.KG, k), loggedAt = d.atTime(7, 0).toInstant(ZoneOffset.UTC))
    }

    @Test fun nut15SeriesMa7AndNoise() {
        val pts = trend.series(metrics(listOf(84.0, 84.0, 85.0, 84.0)))
        assertEquals(listOf(84.0, 84.0, 84.33, 84.25), pts.map { it.ma7Kg })
        assertEquals(listOf(false, false, true, true), pts.map { it.noise })
        assertEquals(listOf(84.0, 84.0, 85.0, 84.0), pts.map { it.rawKg })
    }

    @Test fun rateAndDeltaNeedFourteenDays() {
        val short = trend.series(metrics((0 until 13).map { 84.0 + 0.1 * it }))
        assertNull(trend.delta14Kg(short))
        assertNull(trend.ratePctPerWeek(short))
        val long = trend.series(metrics((0 until 21).map { 84.0 + 0.1 * it }))
        val d = trend.delta14Kg(long)
        assertNotNull(d)
        assertEquals(1.4, d)
        val rate = trend.ratePctPerWeek(long)
        assertNotNull(rate)
        assertTrue(rate > 0.7 && rate < 0.9, "rate $rate")
    }

    @Test fun nut15AndNut14NoiseExplanation() {
        val short = trend.series(metrics(listOf(84.0, 84.0, 85.0, 84.0)))
        val e = trend.explanation(short)
        assertEquals(listOf(RuleIds.NUT_15, RuleIds.NUT_14), e.ruleIds)
        assertEquals("2", e.inputs["noise_days"])
        assertTrue(e.message.contains("14 days"), e.message)
        assertNull(e.inputs["rate_pct_per_week"])
        val long = trend.explanation(trend.series(metrics((0 until 21).map { 84.0 + 0.1 * it })))
        assertNotNull(long.inputs["rate_pct_per_week"])
    }

}
