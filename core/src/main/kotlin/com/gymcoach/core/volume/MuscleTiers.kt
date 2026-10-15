package com.gymcoach.core.volume

import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier

/**
 * §4.3 "muscle tiers" (TIER, planner convention, weak): TIER_1 = the seven large muscles plus the user's priority muscles,
 * TIER_2 = biceps, triceps, rear delts, calves, abs (minus promoted ones), TIER_3 = everything else (no target, advisory
 * direct cap `tierTier3DirectCap`). The returned map lists every muscle once, in enum (knowledge) order.
 */
object MuscleTiers {
    val TIER_1_BASE: Set<Muscle> = setOf(
        Muscle.CHEST, Muscle.LATS, Muscle.UPPER_BACK, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.SIDE_DELTS,
    )
    val TIER_2_BASE: Set<Muscle> = setOf(Muscle.BICEPS, Muscle.TRICEPS, Muscle.REAR_DELTS, Muscle.CALVES, Muscle.ABS)

    fun assign(priorityMuscles: List<Muscle>): Map<Muscle, MuscleTier> {
        val promoted = priorityMuscles.toSet()
        val out = LinkedHashMap<Muscle, MuscleTier>()
        for (m in Muscle.entries) {
            out[m] = when {
                m in TIER_1_BASE || m in promoted -> MuscleTier.TIER_1
                m in TIER_2_BASE -> MuscleTier.TIER_2
                else -> MuscleTier.TIER_3
            }
        }
        return out
    }

    /** The allocator's / selector's tier weight (§4.6): 1.0 / tierTier2Factor (0.6) / 0. */
    fun weight(tier: MuscleTier?, tier2Factor: Double): Double = when (tier) {
        MuscleTier.TIER_1 -> 1.0
        MuscleTier.TIER_2 -> tier2Factor
        MuscleTier.TIER_3 -> 0.0
        null -> 0.0
    }
}
