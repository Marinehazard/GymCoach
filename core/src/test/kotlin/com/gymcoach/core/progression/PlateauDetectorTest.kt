package com.gymcoach.core.progression

import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.equipment.LoadRounder
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.E1rmEstimate
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionSummary
import com.gymcoach.core.model.WeekMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §4.11 / G6: PLAT-01 counts only fair NORMAL-week sessions; PLAT-03 regression; the ladder itself is WP-03's. */
class PlateauDetectorTest {
    private val engine = ProgressionEngine(PF.kb, PF.c)
    private val detector = PlateauDetector(PF.c)
    private val bench = "barbell_bench_press"

    private fun e1(kg: Double, w: Double, reps: Int, rir: Int, d: LocalDate) = E1rmEstimate(kg, w, reps, rir, E1rmConfidence.HIGH, d)

    private fun g6Start(): ProgressionState {
        val wk2 = PF.MONDAY.minusWeeks(1)
        return PF.state(bench, 82.5, Band(5, 10), 2).copy(
            history = listOf(SessionSummary(wk2, 82.5, 8, 2, false, e1rm = e1(110.0, 82.5, 8, 2, wk2))),
            lastIncrementDate = PF.MONDAY.minusWeeks(2),
        )
    }

    private fun session(s: ProgressionState, reps: List<Int>, date: LocalDate, external: Boolean = false) = engine.afterSession(
        PF.input(PF.record("g6-$date", date), PF.sets("g6-$date", bench, 82.5, reps, 2, date = date), listOf(s), external = external),
    )

    @Test fun g6ThreeFairSessionsWithoutProgressBecomePlateau() {
        var s = g6Start()
        var r = session(s, listOf(8, 8, 7, 7), PF.MONDAY); s = r.states.single()
        assertEquals(1, s.noProgressCount)
        r = session(s, listOf(8, 8, 8, 7), PF.MONDAY.plusWeeks(1)); s = r.states.single()
        assertEquals(2, s.noProgressCount)
        assertEquals(ProgressionStatus.PROGRESSING, s.status)
        r = session(s, listOf(8, 8, 7, 7), PF.MONDAY.plusWeeks(2)); s = r.states.single()
        assertEquals(3, s.noProgressCount)
        assertEquals(ProgressionStatus.PLATEAU, s.status)
        assertEquals(0, s.plateauStep)
        assertTrue(r.flags.any { RuleIds.PLAT_01 in it.ruleIds }, r.flags.toString())
        assertTrue(RuleIds.PLAT_01 in r.adjustments.single().ruleIds)
        // bestE1rm14d = 82.5 × 8 @ 2 → r_eff 10 → 110.0; step-2 re-seed arithmetic (applied by WP-03's rollWeek): 110 × pct(12) = 78.1 → 77.5 kg.
        assertEquals(110.0, s.bestE1rm14d)
        val reseed = E1rm.loadFor(110.0, 10, 2)
        assertEquals(78.1, Math.round(reseed * 10.0) / 10.0)
        val rounded = LoadRounder(PF.kb).round(reseed, PF.kb.requireExercise(bench), LoadType.BARBELL, GymParams(PF.kb, SessionEquipment(PF.home)))
        assertEquals(77.5, rounded.kg)
    }

    @Test fun unfairSessionsNeverCount() {
        var s = g6Start()
        for (k in 0..3) s = session(s, listOf(8, 8, 7, 7), PF.MONDAY.plusWeeks(k.toLong()), external = true).states.single()
        assertEquals(0, s.noProgressCount)
        assertEquals(ProgressionStatus.PROGRESSING, s.status)
        val summary = SessionSummary(PF.MONDAY, 82.5, 8, 2, false)
        for (mode in listOf(WeekMode.DELOAD, WeekMode.REENTRY, WeekMode.TRAVEL_MAINTENANCE)) {
            val v = detector.evaluate(g6Start().copy(noProgressCount = 2), summary, true, mode)
            assertEquals(2, v.noProgressCount, mode.name)
            assertTrue(v.flags.isEmpty())
        }
        val v = detector.evaluate(g6Start().copy(noProgressCount = 2), summary, false, WeekMode.NORMAL)
        assertEquals(2, v.noProgressCount)
        assertEquals(ProgressionStatus.PROGRESSING, v.status)
    }

    @Test fun plateauEndsOnImprovement() {
        val s = g6Start().copy(status = ProgressionStatus.PLATEAU, noProgressCount = 3, plateauStep = 2)
        val v = detector.evaluate(s, SessionSummary(PF.MONDAY, 82.5, 9, 2, false), true, WeekMode.NORMAL)
        assertEquals(ProgressionStatus.PROGRESSING, v.status)
        assertEquals(0, v.noProgressCount)
    }

    @Test fun plat03TwoConsecutiveDropsAreRegressing() {
        val d0 = PF.MONDAY.minusDays(10)
        val d1 = PF.MONDAY.minusDays(3)
        val s = PF.state(bench, 82.5, Band(5, 10), 2).copy(history = listOf(
            SessionSummary(d1, 75.0, 8, 2, false, e1rm = e1(100.0, 75.0, 8, 2, d1)),
            SessionSummary(d0, 82.5, 8, 2, false, e1rm = e1(110.0, 82.5, 8, 2, d0)),
        ))
        val now = SessionSummary(PF.MONDAY, 75.0, 8, 2, false, e1rm = e1(100.0, 75.0, 8, 2, PF.MONDAY))
        val v = detector.evaluate(s, now, true, WeekMode.NORMAL)
        assertEquals(ProgressionStatus.REGRESSING, v.status)
        assertTrue(v.flags.any { RuleIds.PLAT_03 in it.ruleIds })
        // The previous compared session must be fair too: an unfair drop is skipped in the look-back.
        val unfairPrev = s.copy(history = listOf(s.history[0].copy(fair = false), s.history[1]))
        val v2 = detector.evaluate(unfairPrev, now, true, WeekMode.NORMAL)
        assertTrue(v2.flags.none { RuleIds.PLAT_03 in it.ruleIds })
    }
}
