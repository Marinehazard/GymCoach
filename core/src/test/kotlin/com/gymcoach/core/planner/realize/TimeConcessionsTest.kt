package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeConcessionsTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val tc = TimeConcessions(c)

    private fun w(slot: String, role: SlotRole, id: String, lt: LoadType, sets: Int, required: Boolean = true, group: String? = null, heavy: Boolean = false) =
        WorkBlock(slot, role, heavy, required, kb.requireExercise(id), 0, lt, sets, sets, group)

    /** E2's LOWER_B at the hotel after substitution: 44.7 lifting minutes against a 40-minute budget. */
    private fun e2Blocks(): MutableList<WorkBlock> = mutableListOf(
        w("s1", SlotRole.PRIMARY, "dumbbell_romanian_deadlift", LoadType.DUMBBELL, 3, heavy = true),
        w("s2", SlotRole.SECONDARY, "goblet_squat", LoadType.DUMBBELL, 3),
        w("s3", SlotRole.ACCESSORY, "glute_bridge", LoadType.DUMBBELL, 2, required = false),
        w("s4", SlotRole.ACCESSORY, "reverse_lunge", LoadType.DUMBBELL, 2, required = false),
        w("s5", SlotRole.ISOLATION, "slider_leg_curl", LoadType.BODYWEIGHT, 3),
        w("s6", SlotRole.ISOLATION, "single_leg_calf_raise", LoadType.BODYWEIGHT, 3, group = "s6+s7"),
        w("s7", SlotRole.CORE, "side_plank", LoadType.BODYWEIGHT, 3, required = false, group = "s6+s7"),
    )

    /** E1 LOWER_A compounds: hamstrings RDL 3 × 1.0 + squat 4 × 0.25 + BSS 2 × 0.25 = 4.5; glutes 4.5; quads 6. */
    private fun e2WeekCredit(): DoubleArray {
        val out = DoubleArray(Muscle.entries.size)
        out[Muscle.HAMSTRINGS.ordinal] = 4.5
        out[Muscle.GLUTES.ordinal] = 4.5
        out[Muscle.QUADS.ordinal] = 6.0
        return out
    }

    @Test
    fun e2LadderLogIsL2AndTheRequiredIsolationSlotDropsToZero_TIMEL2() {
        val blocks = e2Blocks()
        assertEquals(44.745, tc.minutes(blocks, 0), 1e-6)
        val r = tc.apply(blocks, 45, 5, 0, wu05Applied = true, weekCompoundCredit = e2WeekCredit(), priorityIds = setOf("s1"))
        assertEquals(listOf(RuleIds.TIME_L2), tc.levelsOf(r))
        assertEquals(0, blocks.first { it.slotId == "s5" }.sets)
        assertEquals(RuleIds.TIME_L2, blocks.first { it.slotId == "s5" }.droppedBy)
        assertEquals(39.495, r.liftingMinutes, 1e-6)
        assertEquals(5, r.warmupMinutes)
        assertEquals(16, blocks.sumOf { it.sets })
        assertTrue(r.applied.first().ruleIds.contains(RuleIds.TIME_01))
    }

    @Test
    fun l1TrimsTheWarmupWhenWu05DoesNotAlreadyApply_TIMEL1() {
        val blocks = e2Blocks()
        val r = tc.apply(blocks, 50, 10, 0, wu05Applied = false, weekCompoundCredit = e2WeekCredit(), priorityIds = setOf("s1"))
        assertEquals(RuleIds.TIME_L1, tc.levelsOf(r).first())
        assertEquals(c.wu.wu05TrimmedMinutes, r.warmupMinutes)
        assertTrue(r.liftingMinutes <= 50 - r.warmupMinutes + 1e-6)
    }

    @Test
    fun deeperLevelsKeepTheMinimumViableSession_TIME01() {
        val blocks = e2Blocks()
        val r = tc.apply(blocks, 25, 5, 0, wu05Applied = true, weekCompoundCredit = DoubleArray(Muscle.entries.size), priorityIds = setOf("s1", "s2"))
        assertTrue(r.liftingMinutes <= 20.0 + 1e-6, "lifting ${r.liftingMinutes}")
        assertTrue(blocks.first { it.slotId == "s1" }.sets >= c.trv.time01MinViablePrioritySets)
        assertTrue(blocks.first { it.slotId == "s2" }.sets >= c.trv.time01MinViablePrioritySets)
        val levels = tc.levelsOf(r)
        assertTrue(levels.containsAll(listOf(RuleIds.TIME_L3, RuleIds.TIME_L4)), levels.toString())
        assertTrue(levels.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun nothingHappensWhenTheSessionFits() {
        val blocks = e2Blocks()
        val r = tc.apply(blocks, 60, 10, 0, wu05Applied = false, weekCompoundCredit = e2WeekCredit(), priorityIds = emptySet())
        assertTrue(r.applied.isEmpty())
        assertEquals(0, r.level)
        assertEquals(19, blocks.sumOf { it.sets })
    }

    @Test
    fun level4CutsIsolationCostAndCompetingPairsAreNotSupersetted() {
        assertEquals(1.5, com.gymcoach.core.planner.TimeModel(c).costPerSet(kb.requireExercise("dumbbell_lateral_raise"), LoadType.DUMBBELL, false, 4))
        assertTrue(tc.competing(kb.requireExercise("dumbbell_lateral_raise"), kb.requireExercise("cable_lateral_raise")))
        assertTrue(!tc.competing(kb.requireExercise("dumbbell_lateral_raise"), kb.requireExercise("dumbbell_curl")))
    }

    @Test fun time03DropSetsOnlyOnMachineCableOrIsolation() {
        val machine = tc.advancedTechniqueAllowed(kb.requireExercise("machine_chest_press"), LoadType.MACHINE)
        assertEquals(listOf(RuleIds.TIME_03), machine?.ruleIds)
        assertTrue(tc.advancedTechniqueAllowed(kb.requireExercise("lat_pulldown"), LoadType.CABLE) != null)
        assertTrue(tc.advancedTechniqueAllowed(kb.requireExercise("barbell_curl"), LoadType.BARBELL) != null)
        assertEquals(null, tc.advancedTechniqueAllowed(kb.requireExercise("back_squat"), LoadType.BARBELL))
        assertEquals(null, tc.advancedTechniqueAllowed(kb.requireExercise("barbell_bench_press"), LoadType.BARBELL))
        assertEquals(null, tc.advancedTechniqueAllowed(kb.requireExercise("dumbbell_bench_press"), LoadType.DUMBBELL))
    }
}
