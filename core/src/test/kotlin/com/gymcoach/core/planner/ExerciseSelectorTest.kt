package com.gymcoach.core.planner

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.SessionTemplates
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.volume.TargetPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExerciseSelectorTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val planner = Planner(kb, c)
    private val selector = ExerciseSelector(kb, c)
    private val day = PlannerFixtures.MONDAY

    private fun ctx(inputs: com.gymcoach.core.model.PlanInputs, gym: com.gymcoach.core.model.GymSet = PlannerFixtures.home): SelectionContext {
        val n = planner.normalize(inputs, day)
        val t = TargetPolicy(c).weekTargets(n.goal, n.trainingAge, n.tiers, 1, emptySet(), null, n.daysPerWeek, n.age)
        return SelectionContext(n, n.goal, n.trainingAge, SessionEquipment(gym), t, n.tiers, inputs.progressionStates, inputs.profile, day)
    }

    private fun schedule(rowDays: Int, goal: Goal, ta: TrainingAge): List<ScheduledTemplate> {
        val sel = SplitSelector(kb)
        return sel.choose(checkNotNull(sel.row(rowDays, goal, ta)), emptyList()).schedule
    }

    @Test
    fun heavySlotPrePassResolvesATuesdayBackoffToFridaysHeavyPick() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1().copy(goal = Goal.STRENGTH), gyms = listOf(PlannerFixtures.base))
        val picks = selector.selectWeek(ctx(inputs, PlannerFixtures.base), schedule(4, Goal.STRENGTH, TrainingAge.INTERMEDIATE))
        val tueHinge = picks[1].first { it?.slot?.slotId == "s2" }!!
        val friHinge = picks[3].first { it?.slot?.slotId == "s1" }!!
        assertEquals("conventional_deadlift", friHinge.exercise.id)
        assertEquals(friHinge.exercise.id, tueHinge.exercise.id)
        assertEquals("LOWER_B_S.s1", tueHinge.explanation.inputs["backoff_of"])
        assertEquals("back_squat", picks[1].first { it?.slot?.slotId == "s1" }!!.exercise.id)
        assertEquals("barbell_bench_press", picks[0].first { it?.slot?.slotId == "s1" }!!.exercise.id)
        assertEquals("overhead_press_barbell", picks[2].first { it?.slot?.slotId == "s3" }!!.exercise.id)
        assertEquals("overhead_press_barbell", picks[0].first { it?.slot?.slotId == "s3" }!!.exercise.id)   // UPPER_A_S4.s3 backs off UPPER_B_S.s3
    }

    @Test
    fun coverageMakesSelectionVolumeAware_theWalkOrderMatters() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1())
        val cx = ctx(inputs)
        val slot = SessionTemplates.requireTemplate(kb, "UPPER_B").slots.first { it.slotId == "s7" }   // chest_fly | elbow_extension (chest, triceps)
        val cands = selector.candidates(slot, cx)
        val chestBehind = DoubleArray(Muscle.entries.size).also { it[Muscle.TRICEPS.ordinal] = 7.0 }
        val tricepsBehind = DoubleArray(Muscle.entries.size).also { it[Muscle.CHEST.ordinal] = 12.0 }
        val a = selector.score(cands, slot, cx, chestBehind, emptyList(), emptyList(), 0, null).first().cand.exercise
        val b = selector.score(cands, slot, cx, tricepsBehind, emptyList(), emptyList(), 0, null).first().cand.exercise
        assertEquals(com.gymcoach.core.model.MovementPattern.CHEST_FLY, a.pattern)
        assertEquals(com.gymcoach.core.model.MovementPattern.ELBOW_EXTENSION, b.pattern)
        // Repeat-in-session and variety penalties are part of the score (SEL).
        assertEquals(com.gymcoach.core.model.SlotRole.ACCESSORY, slot.role)
        val repeat = selector.score(cands, slot, cx, chestBehind, emptyList(), listOf(0 to a.id), 1, null).first { it.cand.exercise.id == a.id }
        assertEquals(-c.sel.selVarietyPenalty, repeat.terms["varietyPenalty"])        // selected in another session this week
    }

    @Test
    fun pinsAndPermanentSwapsTakeTheSlotOutright() {
        val pin = PlanOverride("p", PlannerFixtures.NOW, OverrideSource.USER, null, PlanMutation.PinExercise("dumbbell_bench_press", "UPPER_A", "x"))
        val swap = PlanOverride("s", PlannerFixtures.NOW, OverrideSource.CHAT, null, PlanMutation.SwapExercise("barbell_row", "dumbbell_row", null, MutationScope.PERMANENT, "x"))
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1()).copy(overrides = listOf(pin, swap))
        val picks = selector.selectWeek(ctx(inputs), schedule(4, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE))
        val s1 = picks[0][0]!!
        assertEquals("dumbbell_bench_press", s1.exercise.id)
        assertTrue(RuleIds.OVERRIDE in s1.explanation.ruleIds)
        assertTrue(picks.flatten().filterNotNull().none { it.exercise.id == "barbell_row" })
        assertTrue(picks.flatten().filterNotNull().any { it.exercise.id == "dumbbell_row" && RuleIds.OVERRIDE in it.explanation.ruleIds })
    }

    @Test
    fun prioritySlotsTargetTheLargestDeficitWithAnIsolationPattern() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p2(), gyms = listOf(PlannerFixtures.base))
        val picks = selector.selectWeek(ctx(inputs, PlannerFixtures.base), schedule(3, Goal.GENERAL_FITNESS, TrainingAge.NOVICE))
        val s6 = picks[0].first { it?.slot?.slotId == "s6" }!!
        val s7 = picks[0].first { it?.slot?.slotId == "s7" }!!
        assertTrue(s6.explanation.inputs.containsKey("priority_muscle"))
        assertTrue(s6.explanation.inputs["priority_muscle"] != s7.explanation.inputs["priority_muscle"])
        assertTrue(s6.exercise.pattern in s6.slot.patterns && s7.exercise.pattern in s7.slot.patterns)
    }

    @Test
    fun equalScoresBreakByKnowledgeOrder() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1())
        val cx = ctx(inputs)
        val slot = SessionTemplates.requireTemplate(kb, "UPPER_A").slots.first { it.slotId == "s8" }
        val scored = selector.score(selector.candidates(slot, cx), slot, cx, DoubleArray(Muscle.entries.size), emptyList(), emptyList(), 0, null)
        val top = scored.filter { it.total == scored.first().total }.map { it.cand.exercise.id }
        assertEquals(top.sortedBy { kb.exerciseOrder.indexOf(it) }, top)
        assertTrue(selector.candidates(slot, cx).all { it.exercise.pattern in slot.patterns })
    }
}
