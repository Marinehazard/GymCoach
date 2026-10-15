package com.gymcoach.core.volume

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetPolicyTest {
    private val c = PlannerConstants.DEFAULT
    private val p = TargetPolicy(c)
    private val tiers = MuscleTiers.assign(emptyList())

    private fun t(goal: Goal, ta: TrainingAge, week: Int, holds: Set<Muscle> = emptySet(), days: Int = 4, age: Int = 38, active: List<String> = emptyList(), acc: Map<Muscle, Double>? = null) =
        p.weekTargets(goal, ta, tiers, week, holds, acc, days, age, active)

    @Test
    fun vol04BandsByTrainingAgeAndTheTier2Factor() {
        assertEquals(12.0, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 1)[Muscle.CHEST])
        assertEquals(7.0, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 1)[Muscle.TRICEPS])
        assertEquals(8.0, t(Goal.HYPERTROPHY, TrainingAge.NOVICE, 1)[Muscle.CHEST])
        assertEquals(5.0, t(Goal.HYPERTROPHY, TrainingAge.NOVICE, 1)[Muscle.TRICEPS])
        assertEquals(15.0, t(Goal.HYPERTROPHY, TrainingAge.ADVANCED, 1)[Muscle.CHEST])
        assertEquals(9.0, t(Goal.HYPERTROPHY, TrainingAge.ADVANCED, 1)[Muscle.TRICEPS])
        // E3: GENERAL_FITNESS novice 8 / 5.
        assertEquals(8.0, t(Goal.GENERAL_FITNESS, TrainingAge.NOVICE, 1)[Muscle.QUADS])
        assertEquals(5.0, t(Goal.GENERAL_FITNESS, TrainingAge.NOVICE, 1)[Muscle.CALVES])
        // STRENGTH: muscles use the novice band regardless of training age (VOL-05 is per lift).
        assertEquals(8.0, t(Goal.STRENGTH, TrainingAge.ADVANCED, 1)[Muscle.CHEST])
        assertEquals(4.0, t(Goal.ENDURANCE_SUPPORT, TrainingAge.INTERMEDIATE, 1)[Muscle.CHEST])
        assertEquals(2.0, t(Goal.ENDURANCE_SUPPORT, TrainingAge.INTERMEDIATE, 1)[Muscle.BICEPS])
        assertFalse(t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 1).containsKey(Muscle.FRONT_DELTS))
    }

    @Test
    fun vol08RampStepsAndCapsAtTheUpperBound() {
        assertEquals(13.0, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 2)[Muscle.CHEST])
        assertEquals(7.5, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 2)[Muscle.TRICEPS])
        assertEquals(17.0, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 6)[Muscle.CHEST])
        assertEquals(18.0, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 20)[Muscle.CHEST])
        assertEquals(11.0, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 20)[Muscle.TRICEPS])
    }

    @Test
    fun vol08HoldKeepsLastWeeksTargetForTheHeldMuscle() {
        val held = t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 3, holds = setOf(Muscle.CHEST))
        assertEquals(13.0, held[Muscle.CHEST])
        assertEquals(14.0, held[Muscle.LATS])
    }

    @Test
    fun everyRampIsHeldWhileDeloadTriggersAreActive_VOL08_DLD03() {
        val held = t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 3, active = listOf("red_days@2026-09-01"))
        assertEquals(13.0, held[Muscle.CHEST])
        assertEquals(13.0, held[Muscle.LATS])
        assertEquals(7.5, held[Muscle.TRICEPS])
        val notes = p.holdExplanations(emptyMap(), listOf("red_days@2026-09-01", "soreness@2026-09-02"), 3)
        assertEquals(listOf(RuleIds.VOL_08, RuleIds.DLD_03), notes.single().ruleIds)
        assertEquals("red_days@2026-09-01,soreness@2026-09-02", notes.single().inputs["triggers"])
    }

    @Test
    fun frq03SingleDayTargetsAreTheVol01FloorWithoutRamp() {
        val one = t(Goal.HYPERTROPHY, TrainingAge.ADVANCED, 5, days = 1)
        assertTrue(one.values.all { it == 4.0 })
        assertEquals(4.0, one[Muscle.CALVES])
    }

    @Test
    fun age02UsesTheNoviceBandFromSixty() {
        assertEquals(8.0, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 1, age = 62)[Muscle.CHEST])
        assertEquals(5.0, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 1, age = 62)[Muscle.BICEPS])
        assertEquals(12.0, t(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, 1, age = 59)[Muscle.CHEST])
    }

    @Test
    fun vol09FatLossUsesAccustomedVolumeOrTheNoHistoryFactor() {
        val none = t(Goal.FAT_LOSS, TrainingAge.INTERMEDIATE, 1)
        assertEquals(9.6, none[Muscle.CHEST])           // 0.8 × 12
        val acc = t(Goal.FAT_LOSS, TrainingAge.INTERMEDIATE, 1, acc = mapOf(Muscle.CHEST to 20.0, Muscle.TRICEPS to 5.0))
        assertEquals(12.0, acc[Muscle.CHEST])           // 0.6 × 20
        assertEquals(4.0, acc[Muscle.TRICEPS])          // floor VOL-01
        assertEquals(18.0, t(Goal.FAT_LOSS, TrainingAge.INTERMEDIATE, 30, acc = mapOf(Muscle.CHEST to 30.0))[Muscle.CHEST]) // capped at VOL-04.hi
    }
}
