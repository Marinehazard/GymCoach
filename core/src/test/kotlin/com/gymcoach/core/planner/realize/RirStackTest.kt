package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Goal
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RirStackTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT

    private fun p(id: String, goal: Goal = Goal.HYPERTROPHY) = kb.prescription(kb.requireExercise(id), goal)

    private fun input(base: Int, week: Int = 0, readiness: Int = 0, layoff: Int = 0, sleep: Int = 0, stress: Int = 0, keep: Int = 0, floor: Int = 0, deload: Int? = null, endurance: Int? = null) =
        RirInput(base, week, readiness, layoff, sleep, stress, keep, floor, deload, endurance)

    @Test
    fun profileTargetsAreTheBandMiddleRoundedUp_RIR01_RIR03() {
        assertEquals(2, RirStack.profileTargetRir(p("barbell_bench_press")))      // barbell_compound hypertrophy [1,3]
        assertEquals(2, RirStack.profileTargetRir(p("dumbbell_bench_press")))     // compound [1,3]
        assertEquals(3, RirStack.profileTargetRir(p("back_squat", Goal.STRENGTH))) // [2,3] → 3
        assertEquals(1, RirStack.profileTargetRir(p("dumbbell_fly")))             // isolation [0,2]
    }

    @Test
    fun lastWeekUsesRirLastWeekHiElseTargetMinusOne_RIR04() {
        assertEquals(1, RirStack.lastWeekTargetRir(p("barbell_bench_press")))
        assertEquals(1, RirStack.lastWeekTargetRir(p("dumbbell_bench_press", Goal.STRENGTH)))  // compound strength [2,2], no rir_last_week → 2 − 1
    }

    @Test
    fun stackClampsToFloorAndCapWithTheRirCapExplanation() {
        assertEquals(3 to null, RirStack.finalRir(input(2, week = 1), c))
        val (red, note) = RirStack.finalRir(input(2, week = 1, readiness = 2), c)
        assertEquals(4, red)
        assertNotNull(note)
        assertEquals(listOf(RuleIds.RIR_CAP, RuleIds.DEF_04), note.ruleIds)
        assertNull(RirStack.finalRir(input(2, week = 1, readiness = 1), c).second)   // Amber week 1 = 4: at the cap, not capped
        assertEquals(2, RirStack.finalRir(input(1, keep = -1, floor = 2), c).first)  // SP-without-rack floor
        assertEquals(0, RirStack.finalRir(input(1, keep = -1, floor = 0), c).first)
        assertEquals(4, RirStack.finalRir(input(2, sleep = 1, stress = 1, layoff = 1), c).first)
    }

    @Test
    fun fixedDeloadAndEnduranceValuesBypassTheStack() {
        assertEquals(4 to null, RirStack.finalRir(input(2, week = 1, readiness = 2, deload = c.rir.rir04DeloadRir), c))
        assertEquals(1 to null, RirStack.finalRir(input(2, week = 1, readiness = 1, endurance = c.trv.trv01EnduranceRir), c))
    }
}
