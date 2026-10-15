package com.gymcoach.core.planner

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.MedicalFlag
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.TrainingAge
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NormalizerTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val planner = Planner(kb, c)
    private val p1 = PlannerFixtures.p1()
    private val day = PlannerFixtures.MONDAY

    @Test
    fun def01TrainingAgeIncludingLayoffRecency() {
        assertEquals(TrainingAge.NOVICE, planner.trainingAgeOf(p1.copy(trainingMonthsConsistent = 5), day))
        assertEquals(TrainingAge.INTERMEDIATE, planner.trainingAgeOf(p1.copy(trainingMonthsConsistent = 6), day))
        assertEquals(TrainingAge.INTERMEDIATE, planner.trainingAgeOf(p1.copy(trainingMonthsConsistent = 24), day))
        assertEquals(TrainingAge.ADVANCED, planner.trainingAgeOf(p1.copy(trainingMonthsConsistent = 25), day))
        assertEquals(TrainingAge.NOVICE, planner.trainingAgeOf(p1.copy(trainingMonthsConsistent = 60, layoffMonths = 12, monthsRetrainedSinceLayoff = 3), day))
        assertEquals(TrainingAge.ADVANCED, planner.trainingAgeOf(p1.copy(trainingMonthsConsistent = 60, layoffMonths = 12, monthsRetrainedSinceLayoff = 6), day))
        assertEquals(TrainingAge.ADVANCED, planner.trainingAgeOf(p1.copy(trainingMonthsConsistent = 60, layoffMonths = 11, monthsRetrainedSinceLayoff = 0), day))
    }

    @Test
    fun normalizeEqualsStage0OfPlan() {
        val inputs = PlannerFixtures.inputs(p1)
        val n = planner.normalize(inputs, day)
        assertEquals(38, n.age)
        assertEquals(TrainingAge.INTERMEDIATE, n.trainingAge)
        val m = planner.plan(inputs, day).mesocycle
        assertEquals(n.trainingAge, m.trainingAge)
        assertEquals(n.goal, m.goal)
        assertTrue(m.explanations.containsAll(n.explanations))
        assertTrue(n.explanations.any { RuleIds.DEF_01 in it.ruleIds })
    }

    @Test
    fun limitationsExcludeAboveThreeAndPenalizeAtOrBelow_INJ00() {
        val knee5 = planner.normalize(PlannerFixtures.inputs(p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.KNEE, 5)))), day)
        assertTrue("back_squat" in knee5.excludedExerciseIds)
        assertTrue("goblet_squat" in knee5.excludedExerciseIds)
        val knee2 = planner.normalize(PlannerFixtures.inputs(p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.KNEE, 2)))), day)
        assertFalse("goblet_squat" in knee2.excludedExerciseIds)
        assertTrue("goblet_squat" in knee2.penalizedExerciseIds)
        val back = planner.normalize(PlannerFixtures.inputs(p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.LOW_BACK, 4)))), day)
        assertTrue("conventional_deadlift" in back.excludedExerciseIds)
        assertTrue(back.explanations.any { RuleIds.INJ_03 in it.ruleIds })
        val hip = planner.normalize(PlannerFixtures.inputs(p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.HIP, 6)))), day)
        assertTrue("goblet_squat" in hip.penalizedExerciseIds && "goblet_squat" !in hip.excludedExerciseIds)
        val elbow = planner.normalize(PlannerFixtures.inputs(p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.ELBOW_WRIST, 4)))), day)
        assertTrue(elbow.elbowWristHalved)
        assertTrue(elbow.explanations.any { RuleIds.INJ_04 in it.ruleIds })
        val shoulder = planner.normalize(PlannerFixtures.inputs(p1.copy(limitations = listOf(PlannerFixtures.lim(LimitationRegion.SHOULDER, 4)))), day)
        assertTrue("barbell_bench_press" in shoulder.excludedExerciseIds)
        assertTrue(shoulder.explanations.any { RuleIds.INJ_02 in it.ruleIds })
    }

    @Test
    fun dislikesOverridesAndTheDifficultyGate() {
        val ov = listOf(
            PlanOverride("x", Instant.parse("2026-09-01T10:00:00Z"), OverrideSource.USER, null, PlanMutation.ExcludeExercise("dumbbell_fly", null, "x")),
            PlanOverride("pin", Instant.parse("2026-09-01T10:00:00Z"), OverrideSource.USER, null, PlanMutation.PinExercise("dumbbell_bench_press", "UPPER_A", "x")),
            PlanOverride("sw", Instant.parse("2026-09-01T10:00:00Z"), OverrideSource.CHAT, null, PlanMutation.SwapExercise("barbell_row", "dumbbell_row", null, MutationScope.REST_OF_MESOCYCLE, "x")),
            PlanOverride("sch", Instant.parse("2026-09-01T10:00:00Z"), OverrideSource.USER, null, PlanMutation.ChangeSchedule(3, 45, listOf(1, 3, 5), "x")),
            PlanOverride("pr", Instant.parse("2026-09-01T10:00:00Z"), OverrideSource.USER, null, PlanMutation.SetPriorityMuscles(listOf(Muscle.BICEPS), "x")),
            PlanOverride("g", Instant.parse("2026-09-01T10:00:00Z"), OverrideSource.USER, null, PlanMutation.ChangeGoal(Goal.STRENGTH, true, "x")),
        )
        val n = planner.normalize(PlannerFixtures.inputs(p1.copy(dislikedExerciseIds = listOf("hammer_curl"))).copy(overrides = ov), day)
        assertTrue("hammer_curl" in n.excludedExerciseIds && "dumbbell_fly" in n.excludedExerciseIds)
        assertEquals("dumbbell_bench_press", n.pinned["UPPER_A"])
        assertEquals("dumbbell_row", n.permanentSwaps["barbell_row"])
        assertEquals(3, n.daysPerWeek)
        assertEquals(45, n.sessionMinutes)
        assertEquals(listOf(1, 3, 5), n.preferredWeekdays)
        assertEquals(MuscleTier.TIER_1, n.tiers[Muscle.BICEPS])
        assertEquals(Goal.STRENGTH, n.goal)                                     // created 2026-09-01 (Tue) → applies from Mon 2026-09-07
        val before = planner.normalize(PlannerFixtures.inputs(p1).copy(overrides = ov), day.minusDays(1))
        assertEquals(Goal.HYPERTROPHY, before.goal)
        // ADVANCED exercises need allowAdvancedExercises; a novice with < 8 logged weeks gets BEGINNER exercises only.
        assertTrue("front_squat" in n.excludedExerciseIds)
        assertFalse("front_squat" in planner.normalize(PlannerFixtures.inputs(p1.copy(allowAdvancedExercises = true)), day).excludedExerciseIds)
        assertTrue(planner.normalize(PlannerFixtures.inputs(PlannerFixtures.p2()), day).beginnerOnly)
    }

    @Test
    fun medicalAndAgeGates_RED03_AGE03() {
        val gated = planner.normalize(PlannerFixtures.inputs(p1.copy(medicalFlags = setOf(MedicalFlag.HEART_DISEASE))), day)
        assertTrue(gated.medicalGate)
        assertFalse(gated.maxStrengthAllowed)
        assertTrue(gated.explanations.any { RuleIds.RED_03 in it.ruleIds })
        val cleared = planner.normalize(PlannerFixtures.inputs(p1.copy(medicalFlags = setOf(MedicalFlag.HEART_DISEASE), clearanceConfirmedOn = day.minusDays(3))), day)
        assertFalse(cleared.medicalGate)
        val old = planner.normalize(PlannerFixtures.inputs(p1.copy(birthYear = 1960)), day)
        assertTrue(old.over60)
        assertFalse(old.maxStrengthAllowed)
        val oldAdvanced = planner.normalize(PlannerFixtures.inputs(p1.copy(birthYear = 1960, trainingMonthsConsistent = 40, allowAdvancedExercises = true)), day)
        assertTrue(oldAdvanced.maxStrengthAllowed)
        assertTrue(planner.normalize(PlannerFixtures.inputs(p1), day).firstMesocycle)
    }
}
