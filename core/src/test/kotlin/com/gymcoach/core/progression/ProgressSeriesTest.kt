package com.gymcoach.core.progression

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.E1rmEstimate
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SessionSummary
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.WeightUnit
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §2.14 chart rows: weight MA7 (+ NUT-15 noise), e1RM with dashed LOW points, volume rows, readiness, load × reps, RDY-06. */
class ProgressSeriesTest {
    private val series = ProgressSeries(PF.kb)
    private val d0: LocalDate = LocalDate.of(2026, 9, 7)

    private fun bm(day: Int, kg: Double?, rhr: Int? = null) = BodyMetric(
        id = "m$day", date = d0.plusDays(day.toLong()), weight = kg?.let { EnteredWeight(it, WeightUnit.KG, it) }, restingHr = rhr,
        loggedAt = d0.plusDays(day.toLong()).atTime(7, 0).toInstant(ZoneOffset.UTC),
    )

    @Test fun weightRawAndMa7WithNoiseFlag() {
        val kgs = listOf(84.0, 84.2, 83.8, 84.0, 85.0, 84.1, 84.3, 84.4)
        val (raw, ma) = series.weight(kgs.mapIndexed { i, k -> bm(i, k) })
        assertEquals(8, raw.points.size)
        assertEquals(84.0, raw.points[0].value)
        assertTrue(raw.points[4].lowConfidence, "84.0 → 85.0 is ≥ 1 % in a day")
        assertFalse(raw.points[1].lowConfidence)
        // MA7 on day 6 = mean of days 0..6; day 7 = mean of days 1..7.
        assertEquals(Math.round(kgs.take(7).average() * 100.0) / 100.0, ma.points[6].value)
        assertEquals(Math.round(kgs.subList(1, 8).average() * 100.0) / 100.0, ma.points[7].value)
        assertEquals(d0.toEpochDay(), ma.points[0].dateEpochDay)
        assertEquals(ProgressSeries.SERIES_WEIGHT_MA7, ma.name)
    }

    @Test fun e1rmOnePointPerSessionLowDashed() {
        fun est(kg: Double, c: E1rmConfidence, d: LocalDate) = E1rmEstimate(kg, 60.0, 12, 1, c, d)
        val st = PF.state("barbell_bench_press", 80.0, Band(5, 10), 2).copy(history = listOf(
            SessionSummary(d0.plusDays(7), 80.0, 8, 2, false, e1rm = est(106.7, E1rmConfidence.HIGH, d0.plusDays(7))),
            SessionSummary(d0, 60.0, 12, 1, false, e1rm = est(80.0, E1rmConfidence.LOW, d0)),
            SessionSummary(d0.plusDays(3), 80.0, 4, 2, false),
        ))
        val s = series.e1rm(listOf(st), "barbell_bench_press")
        assertEquals(2, s.points.size)
        assertEquals(d0.toEpochDay(), s.points[0].dateEpochDay)
        assertTrue(s.points[0].lowConfidence)
        assertEquals(106.7, s.points[1].value)
        assertFalse(s.points[1].lowConfidence)
    }

    @Test fun loadXRepsForGymScopedKeys() {
        val st = PF.state("lat_pulldown", 50.0, Band(8, 12), 2, gymScope = "gym_home").copy(history = listOf(
            SessionSummary(d0.plusDays(7), 50.0, 12, 2, true, loadXReps = 600.0),
            SessionSummary(d0, 50.0, 10, 2, false, loadXReps = 500.0),
        ))
        val s = series.loadXReps(st)
        assertEquals(listOf(500.0, 600.0), s.points.map { it.value })
        assertEquals("load_x_reps:lat_pulldown@gym_home", s.name)
    }

    @Test fun volumeRowsTierOrderFractionalAndPairing() {
        val id = "v1"
        val sets = PF.sets(id, "barbell_bench_press", 80.0, listOf(8, 8, 8), 2) +
            PF.set(id, "barbell_bench_press", 4, 60.0, 8, 4, kind = SetKind.RAMP) +
            listOf(PF.set(id, "bulgarian_split_squat", 1, 20.0, 10, 2, side = Side.LEFT), PF.set(id, "bulgarian_split_squat", 1, 20.0, 10, 2, side = Side.RIGHT),
                PF.set(id, "bulgarian_split_squat", 2, 20.0, 10, 2, side = Side.LEFT))
        val tiers = mapOf(Muscle.QUADS to MuscleTier.TIER_1, Muscle.CHEST to MuscleTier.TIER_2)
        val rows = series.volume(sets, d0, mapOf(Muscle.CHEST to 12.0, Muscle.QUADS to 10.0, Muscle.TRICEPS to 6.0), tiers)
        assertEquals(Muscle.values().size, rows.size)
        assertEquals(Muscle.QUADS, rows[0].muscle)
        assertEquals(Muscle.CHEST, rows[1].muscle)
        val chest = rows.single { it.muscle == Muscle.CHEST }
        assertEquals(3.0, chest.achievedFractional)
        assertEquals(3, chest.direct)
        assertEquals(12.0, chest.target)
        assertEquals(1.5, rows.single { it.muscle == Muscle.TRICEPS }.achievedFractional)
        assertNull(rows.single { it.muscle == Muscle.TRICEPS }.target, "TIER_3 has no target")
        val quads = rows.single { it.muscle == Muscle.QUADS }
        val w = PF.kb.requireExercise("bulgarian_split_squat").weightOf(Muscle.QUADS)
        assertEquals(Math.round(1.5 * w * 100.0) / 100.0, quads.achievedFractional)   // pair = 1, unpaired LEFT = 0.5
        assertNotNull(chest.rpMev)
    }

    @Test fun volumeByWeekAndTrainingLoad() {
        val wk1 = PF.sets("a", "barbell_bench_press", 80.0, listOf(8, 8), 2, date = d0)
        val wk2 = PF.sets("b", "barbell_bench_press", 80.0, listOf(8, 8, 8), 2, date = d0.plusDays(8))
        val s = series.volumeByWeek(wk1 + wk2, 3, d0.plusDays(9), Muscle.CHEST)
        assertEquals(listOf(0.0, 2.0, 3.0), s.points.map { it.value })
        val sessions = listOf(
            SessionRecord("a", d0, "g", status = SessionStatus.COMPLETED, sessionRpe = 7, durationMinutes = 60),
            SessionRecord("b", d0.plusDays(8), "g", status = SessionStatus.COMPLETED, sessionRpe = 8, durationMinutes = 50),
            SessionRecord("c", d0.plusDays(9), "g", status = SessionStatus.SKIPPED, sessionRpe = 8, durationMinutes = 50),
        )
        val load = series.trainingLoad(sessions, 2, d0.plusDays(9))
        assertEquals(listOf(d0 to 420, d0.plusDays(7) to 400), load)
    }

    @Test fun readinessAndRestingHrSeries() {
        fun rc(day: Int, score: Int) = ReadinessCheckin(id = "r$day", date = d0.plusDays(day.toLong()), score = score, band = ReadinessBand.AMBER,
            explanation = Explanation(emptyList(), ""), loggedAt = d0.plusDays(day.toLong()).atTime(7, 0).toInstant(ZoneOffset.UTC))
        val r = series.readiness(listOf(rc(1, 58), rc(0, 70)))
        assertEquals(listOf(70.0, 58.0), r.points.map { it.value })
        assertNull(series.restingHr(listOf(bm(0, 84.0))))
        assertEquals(listOf(58.0), series.restingHr(listOf(bm(0, 84.0, 58)))?.points?.map { it.value })
        assertEquals(ProgressionKey("x").id, "x")
    }

    @Test fun rdy06SpikeNoteAboveOneHundredFiftyPercentOfTheFourWeekMean() {
        val base = (4 downTo 1).map { d0.minusWeeks(it.toLong()) to 1000 }
        val spike = series.trainingLoadSpike(base + (d0 to 1600))
        assertNotNull(spike)
        assertEquals(listOf(RuleIds.RDY_06), spike.ruleIds)
        assertEquals("1000", spike.inputs["baseline_mean"])
        assertNull(series.trainingLoadSpike(base + (d0 to 1500)))
        assertNull(series.trainingLoadSpike(base.drop(1) + (d0 to 5000)))
        assertNull(series.trainingLoadSpike(base.map { it.first to 0 } + (d0 to 500)))
    }

}
