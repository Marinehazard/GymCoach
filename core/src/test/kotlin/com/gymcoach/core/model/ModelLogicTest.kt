package com.gymcoach.core.model

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour of the small members declared inside the §2 contracts. */
class ModelLogicTest {

    // --- Band ---
    @Test fun bandContainsInclusiveEnds() {
        val b = Band(8, 12)
        assertTrue(8 in b)
        assertTrue(12 in b)
        assertFalse(7 in b)
        assertFalse(13 in b)
    }

    @Test fun bandShifted() = assertEquals(Band(9, 11), Band(8, 12).shifted(1, -1))

    @Test fun bandRejectsInvertedRange() {
        assertFails { Band(12, 8) }
    }

    @Test fun bandShiftedIntoInvertedRangeFails() {
        assertFails { Band(8, 8).shifted(1, 0) }
    }

    // --- Goal ---
    @Test fun goalRepProfileKeysH7() {
        assertEquals("hypertrophy", Goal.HYPERTROPHY.repProfileKey)
        assertEquals("strength", Goal.STRENGTH.repProfileKey)
        assertEquals("general_fitness", Goal.GENERAL_FITNESS.repProfileKey)
        assertEquals("hypertrophy", Goal.FAT_LOSS.repProfileKey)
        assertEquals("endurance", Goal.ENDURANCE_SUPPORT.repProfileKey)
    }

    @Test fun repProfileKeysAreTheFourR4Keys() {
        assertEquals(setOf("hypertrophy", "strength", "general_fitness", "endurance"), Goal.entries.map { it.repProfileKey }.toSet())
    }

    // --- LoadType ---
    @Test fun gymScopedLoadTypes() {
        assertEquals(
            setOf(LoadType.MACHINE, LoadType.CABLE, LoadType.SMITH, LoadType.SLED),
            LoadType.entries.filter { it.isGymScoped }.toSet(),
        )
    }

    // --- RepProfile ---
    @Test fun repProfileForGoalUsesTheKey() {
        assertEquals(Fixtures.goalPrescription, Fixtures.repProfile.forGoal(Goal.HYPERTROPHY))
        assertEquals(Fixtures.goalPrescription, Fixtures.repProfile.forGoal(Goal.FAT_LOSS))
        assertEquals(Band(12, 20), Fixtures.repProfile.forGoal(Goal.ENDURANCE_SUPPORT).range)
    }

    @Test fun repProfileForGoalMissingKeyThrowsWithProfileId() {
        val partial = RepProfile(id = "partial", unit = RepUnit.REPS, byGoal = mapOf("strength" to Fixtures.goalPrescription))
        val e = assertFails { partial.forGoal(Goal.HYPERTROPHY) }
        assertTrue(e is IllegalStateException)
        assertTrue(e.message.orEmpty().contains("partial"), e.message)
        assertTrue(e.message.orEmpty().contains("hypertrophy"), e.message)
    }

    // --- Exercise ---
    @Test fun exerciseDerivedFlags() {
        assertFalse(Fixtures.exercise.isCardioOrMobility)
        assertFalse(Fixtures.exercise.isIsolation)
        assertTrue(Fixtures.isolationExercise.isIsolation)
        assertTrue(Fixtures.cardioExercise.isCardioOrMobility)
        assertFalse(Fixtures.cardioExercise.isIsolation)
    }

    @Test fun mobilityIsNeverIsolation() {
        val mob = Fixtures.cardioExercise.copy(id = "hip_90_90_switch", pattern = MovementPattern.MOBILITY, loadType = LoadType.NONE)
        assertTrue(mob.isCardioOrMobility)
        assertFalse(mob.isIsolation)
    }

    @Test fun primaryMusclesAreWeightOne() {
        assertEquals(listOf(Muscle.CHEST), Fixtures.exercise.primaryMuscles)
        assertEquals(listOf(Muscle.BICEPS), Fixtures.isolationExercise.primaryMuscles)
    }

    @Test fun weightOfReturnsZeroForUntrainedMuscle() {
        assertEquals(1.0, Fixtures.exercise.weightOf(Muscle.CHEST))
        assertEquals(0.5, Fixtures.exercise.weightOf(Muscle.TRICEPS))
        assertEquals(0.0, Fixtures.exercise.weightOf(Muscle.CALVES))
    }

    @Test fun exerciseClassIsolationOverridesImplement() {
        for (lt in LoadType.entries) assertEquals(ExerciseClass.ISOLATION, Fixtures.isolationExercise.exerciseClass(lt))
    }

    @Test fun exerciseClassUpperBarbell() {
        assertEquals(ExerciseClass.UPPER_BARBELL, Fixtures.exercise.exerciseClass(LoadType.BARBELL))
        assertEquals(ExerciseClass.UPPER_BARBELL, Fixtures.exercise.exerciseClass(LoadType.SMITH))
    }

    @Test fun exerciseClassLowerBarbellPatterns() {
        for (p in listOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.LUNGE_SPLIT, MovementPattern.HIP_EXTENSION_ISOLATION)) {
            val e = Fixtures.exercise.copy(pattern = p)
            assertEquals(ExerciseClass.LOWER_BARBELL, e.exerciseClass(LoadType.BARBELL), p.name)
            assertEquals(ExerciseClass.LOWER_BARBELL, e.exerciseClass(LoadType.SMITH), p.name)
        }
    }

    @Test fun exerciseClassByImplement() {
        val e = Fixtures.exercise
        assertEquals(ExerciseClass.DUMBBELL, e.exerciseClass(LoadType.DUMBBELL))
        assertEquals(ExerciseClass.DUMBBELL, e.exerciseClass(LoadType.KETTLEBELL))
        assertEquals(ExerciseClass.MACHINE, e.exerciseClass(LoadType.MACHINE))
        assertEquals(ExerciseClass.MACHINE, e.exerciseClass(LoadType.SLED))
        assertEquals(ExerciseClass.CABLE, e.exerciseClass(LoadType.CABLE))
        for (lt in listOf(LoadType.BODYWEIGHT, LoadType.BAND, LoadType.MEDICINE_BALL, LoadType.CARDIO, LoadType.NONE)) {
            assertEquals(ExerciseClass.BODYWEIGHT, e.exerciseClass(lt), lt.name)
        }
    }

    // --- SessionEquipment ---
    @Test fun sessionEquipmentItemsApplyRemovalsAndExtras() {
        val eq = SessionEquipment(
            gym = Fixtures.gymSet,
            extraItems = listOf(GymItem("resistance_bands")),
            removedEquipmentIds = setOf("barbell"),
            spotterPresent = true,
        )
        val ids = eq.items.map { it.equipmentId }
        assertEquals(listOf("dumbbells", "power_rack", "resistance_bands"), ids)
        assertTrue(eq.has("dumbbells"))
        assertTrue(eq.has("resistance_bands"))
        assertFalse(eq.has("barbell"))
        assertFalse(eq.has("leg_press_45"))
    }

    @Test fun sessionEquipmentDefaults() {
        val eq = SessionEquipment(gym = Fixtures.gymSet)
        assertEquals(Fixtures.gymSet.items, eq.items)
        assertFalse(eq.spotterPresent)
    }

    // --- ProgressionKey ---
    @Test fun progressionKeyIdFormats() {
        assertEquals("barbell_bench_press", ProgressionKey("barbell_bench_press").id)
        assertEquals("leg_press_45@gym1", ProgressionKey("leg_press_45", "gym1").id)
    }

    @Test fun progressionKeyForExerciseScopesGymScopedLoadTypesOnly() {
        for (lt in LoadType.entries) {
            val key = ProgressionKey.forExercise("x", lt, "gym1")
            if (lt.isGymScoped) assertEquals("gym1", key.gymScope, lt.name) else assertNull(key.gymScope, lt.name)
        }
    }

    // --- Profile ---
    @Test fun profileAgeOnIsYearDifference() {
        assertEquals(38, Fixtures.profile.ageOn(LocalDate.of(2026, 9, 5)))
        assertEquals(12, Fixtures.profile.ageOn(LocalDate.of(2000, 1, 1)))
    }

    // --- TokenUsage ---
    @Test fun tokenUsageTotalInputSumsAllInputKinds() {
        assertEquals(1100, Fixtures.tokenUsage.totalInput)
        assertEquals(0, TokenUsage().totalInput)
    }

    // --- Explanation ---
    @Test fun explanationDefaults() {
        val e = Explanation(listOf("POL-01"), "m")
        assertEquals(emptyMap(), e.inputs)
        assertNull(e.confidence)
    }

    // --- AppSettings defaults (§2.10) ---
    @Test fun appSettingsDefaults() {
        val s = AppSettings()
        assertEquals(UnitSystem.METRIC, s.unitSystem)
        assertEquals(BackendMode.NONE, s.backendMode)
        assertEquals("claude-opus-5", s.modelId)
        assertEquals(Effort.LOW, s.effort)
        assertEquals(30.0, s.monthlyBudgetUsd)
        assertEquals("", s.bridgeUrl)
        assertFalse(s.showExperimental)
        assertFalse(s.onboardingDone)
        assertTrue(s.weekStartsOnMonday)
        assertEquals("{}", s.plannerOverridesJson)
        assertEquals("07:30", s.readinessReminderTime)
        assertEquals(7, s.weeklyCheckinWeekday)
        assertTrue(s.keepScreenOnInSession)
        assertEquals(0, s.mesocycleWeekPausedAt)
    }

    // --- Model defaults pinned by §2 ---
    @Test fun contractDefaults() {
        assertEquals(Side.BOTH, Limitation(id = "l", region = LimitationRegion.OTHER, pain0to10 = 0, since = Fixtures.date).side)
        assertEquals(CheckinSchedule.WEEKLY, CheckinQuestion(id = "q", text = "t", answerType = CheckinAnswerType.TEXT).schedule)
        assertEquals(OverrideSource.PLANNER, CheckinQuestion(id = "q", text = "t", answerType = CheckinAnswerType.TEXT).addedBy)
        assertEquals(4, PlanMutation.SetCheckinQuestion(question = "q", answerType = CheckinAnswerType.TEXT, schedule = CheckinSchedule.WEEKLY, rationale = "r").weeksActive)
        assertEquals("E1RM-01", E1rmEstimate(100.0, 80.0, 5, null, E1rmConfidence.HIGH, Fixtures.date).formulaId)
        assertEquals(1, Fixtures.mesocycle.copy(schemaVersion = 1).schemaVersion)
        assertEquals(LoadSource.NONE, SetPrescription(index = 1, kind = SetKind.WORKING, restSeconds = 90).loadSource)
        assertEquals(IncrementDirection.LOAD, Increment(kind = IncrementKind.KG).direction)
        assertEquals(ContentStatus.TODO, Fixtures.isolationExercise.contentStatus)
    }
}
