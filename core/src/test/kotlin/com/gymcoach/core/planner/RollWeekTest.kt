package com.gymcoach.core.planner

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.model.WeeklyCheckin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §4.11 PLAT-02 ladder advanced by Planner.rollWeek (G6), VOL-08 holds; DLD-03 is never evaluated here. */
class RollWeekTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val planner = Planner(kb, c)
    private val week2 = PlannerFixtures.MONDAY.plusWeeks(1)

    private fun bench(step: Int, status: ProgressionStatus = ProgressionStatus.PLATEAU) = ProgressionState(
        ProgressionKey("barbell_bench_press"), status, workingLoadKg = 82.5, workingReps = PlannerFixtures.band(5, 10), repHiBase = 10, targetRir = 2,
        noProgressCount = 3, bestE1rm14d = 110.0, plateauStep = step, updatedAt = PlannerFixtures.NOW,
    )

    private fun roll(vararg states: ProgressionState) = planner.rollWeek(PlannerFixtures.inputs(PlannerFixtures.p1(), states = states.toList()), week2)

    @Test
    fun g6Step2ShiftsTheRepZoneAndReseedsTheLoadFromE1rm_PLAT02() {
        val r = roll(bench(1))
        val s = r.states.single()
        assertEquals(2, s.plateauStep)
        assertEquals(1, s.repZoneShift)
        assertEquals(77.5, s.workingLoadKg)                                    // 110.0 × pct(10 + 2) = 78.1 → 77.5
        val note = r.ladderNotes.single()
        assertEquals(listOf(RuleIds.PLAT_02, RuleIds.E1RM_03), note.ruleIds)
        assertEquals("barbell_bench_press", note.inputs["exercise_id"])
        assertEquals("2", note.inputs["step"])
        assertTrue(r.deloadTriggers.isEmpty())
        assertTrue(r.proposals.isEmpty())
    }

    @Test
    fun ladderSteps1345() {
        assertEquals(1, roll(bench(0)).states.single().plateauStep)
        val s3 = roll(bench(2))
        assertEquals(PlanMutation.SwapExercise("barbell_bench_press", "dumbbell_bench_press", null, MutationScope.REST_OF_MESOCYCLE, (s3.proposals.single() as PlanMutation.SwapExercise).rationale), s3.proposals.single())
        val s4 = roll(bench(3))
        assertEquals(0, s4.states.single().repZoneShift)
        assertEquals("4", s4.ladderNotes.single().inputs["step"])
        val s5 = roll(bench(4))
        assertTrue(s5.proposals.single() is PlanMutation.ScheduleDeload)
        assertTrue(roll(bench(5)).states.isEmpty())                          // top of the ladder: nothing advances
    }

    @Test
    fun aRecoveredStateResetsItsLadderWithoutANote() {
        val r = roll(bench(2, ProgressionStatus.PROGRESSING).copy(repZoneShift = 1))
        assertEquals(0, r.states.single().plateauStep)
        assertEquals(0, r.states.single().repZoneShift)
        assertTrue(r.ladderNotes.isEmpty())
    }

    @Test
    fun step4AddsASetToTheMusclesTargetWhileUnderVol04Hi() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1(), states = listOf(bench(4)))
        val w = planner.plan(inputs, PlannerFixtures.MONDAY).mesocycle.weeks.first()
        assertEquals(13.0, w.targets[Muscle.CHEST])
    }

    @Test
    fun volumeHoldsAndNextMode_VOL08() {
        val holds = PlannerFixtures.inputs(PlannerFixtures.p1()).copy(
            weeklyCheckins = listOf(WeeklyCheckin("w", PlannerFixtures.MONDAY, emptyList(), holds = listOf(Muscle.QUADS))),
            deloadTriggersActive = listOf("red_days@2026-09-01"),
        )
        val r = planner.rollWeek(holds, week2)
        assertEquals(WeekMode.NORMAL, r.nextMode)
        assertTrue(r.volumeHolds.any { it.ruleIds == listOf(RuleIds.VOL_08, RuleIds.DLD_03) })
        assertTrue(r.volumeHolds.any { it.inputs["muscle"] == "quads" })
        val w2 = planner.plan(holds, week2).mesocycle.weeks.first { it.startDate == week2 }
        assertEquals(12.0, w2.targets[Muscle.CHEST])                         // week-2 ramp held at the week-1 value
        assertEquals(WeekMode.DELOAD, planner.rollWeek(PlannerFixtures.inputs(PlannerFixtures.p1()), PlannerFixtures.MONDAY.plusWeeks(6)).nextMode)
    }
}
