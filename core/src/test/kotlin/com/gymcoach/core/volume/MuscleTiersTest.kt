package com.gymcoach.core.volume

import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import kotlin.test.Test
import kotlin.test.assertEquals

class MuscleTiersTest {
    private val c = PlannerConstants.DEFAULT

    @Test
    fun baseTiersFollowSection43() {
        val t = MuscleTiers.assign(emptyList())
        for (m in listOf(Muscle.CHEST, Muscle.LATS, Muscle.UPPER_BACK, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.SIDE_DELTS)) assertEquals(MuscleTier.TIER_1, t[m], m.name)
        for (m in listOf(Muscle.BICEPS, Muscle.TRICEPS, Muscle.REAR_DELTS, Muscle.CALVES, Muscle.ABS)) assertEquals(MuscleTier.TIER_2, t[m], m.name)
        for (m in listOf(Muscle.FRONT_DELTS, Muscle.ROTATOR_CUFF, Muscle.TRAPS_UPPER, Muscle.LOWER_BACK, Muscle.FOREARMS, Muscle.OBLIQUES, Muscle.HIP_FLEXORS, Muscle.GLUTE_MED, Muscle.ADDUCTORS)) assertEquals(MuscleTier.TIER_3, t[m], m.name)
        assertEquals(Muscle.entries.toList(), t.keys.toList())
    }

    @Test
    fun tierAssignmentPromotesPriorityMuscles() {
        val t1 = MuscleTiers.assign(listOf(Muscle.BICEPS, Muscle.ADDUCTORS))
        assertEquals(MuscleTier.TIER_1, t1[Muscle.BICEPS])
        assertEquals(MuscleTier.TIER_1, t1[Muscle.ADDUCTORS])
        assertEquals(MuscleTier.TIER_2, t1[Muscle.TRICEPS])
        assertEquals(MuscleTier.TIER_3, t1[Muscle.FRONT_DELTS])
        assertEquals(1.0, MuscleTiers.weight(MuscleTier.TIER_1, c.tier.tierTier2Factor))
        assertEquals(0.6, MuscleTiers.weight(MuscleTier.TIER_2, c.tier.tierTier2Factor))
        assertEquals(0.0, MuscleTiers.weight(MuscleTier.TIER_3, c.tier.tierTier2Factor))
        val pt = TargetPolicy(c).weekTargets(Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, t1, 1, emptySet(), null, 4, 38)
        assertEquals(12.0, pt[Muscle.ADDUCTORS])
        assertEquals(12.0, pt[Muscle.BICEPS])
    }
}
