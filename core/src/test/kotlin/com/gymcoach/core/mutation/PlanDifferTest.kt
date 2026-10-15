package com.gymcoach.core.mutation

import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.planner.Planner
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanDifferTest {
    private val kb = PlannerFixtures.kb
    private val planner = Planner(kb, PlannerConstants.DEFAULT)
    private val inputs = PlannerFixtures.inputs(PlannerFixtures.p1())

    @Test
    fun identicalPlansHaveAnEmptyDiff() {
        val m = planner.plan(inputs, PlannerFixtures.MONDAY).mesocycle
        val d = PlanDiffer.between(m, m)
        assertTrue(d.sessionsChanged.isEmpty())
        assertTrue(d.exercisesAdded.isEmpty() && d.exercisesRemoved.isEmpty())
        assertEquals(d.weeklySetsBefore, d.weeklySetsAfter)
    }

    @Test
    fun aPermanentSwapShowsUpAsChangedSessionsAndExercises() {
        val before = planner.plan(inputs, PlannerFixtures.MONDAY).mesocycle
        val from = before.weeks.first().sessions.first().slots.first { it.exerciseId == "barbell_bench_press" }.exerciseId
        val swap = PlanOverride("o1", PlannerFixtures.NOW, OverrideSource.CHAT, "tool", PlanMutation.SwapExercise(from, "dumbbell_bench_press", null, MutationScope.PERMANENT, "shoulder"))
        val after = planner.plan(inputs.copy(overrides = listOf(swap)), PlannerFixtures.MONDAY).mesocycle
        val d = PlanDiffer.between(before, after, listOf("w"))
        assertTrue(d.sessionsChanged.isNotEmpty())
        assertTrue("dumbbell_bench_press" in d.exercisesAdded)
        assertTrue("barbell_bench_press" in d.exercisesRemoved)
        assertEquals(listOf("w"), d.warnings)
        assertTrue(d.weeklySetsAfter.containsKey(Muscle.CHEST))
        assertEquals(after.underTargetReport.map { it.message }, d.underTargetReport)
    }
}
