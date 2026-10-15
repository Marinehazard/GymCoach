package com.gymcoach.core.planner

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.volume.TargetPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetAllocatorTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val alloc = SetAllocator(kb, c)
    private val inputs = PlannerFixtures.inputs(PlannerFixtures.p1())
    private val n = Planner(kb, c).normalize(inputs, PlannerFixtures.MONDAY)
    private val targets = TargetPolicy(c).weekTargets(n.goal, n.trainingAge, n.tiers, 1, emptySet(), null, 4, 38)
    private val eq = SessionEquipment(PlannerFixtures.home)

    /** The E1 Stage-3 picks of §4.17 ("how the allocator got there": UPPER_B s7/s8 start as lying triceps extension ⟂ hammer curl). */
    private fun e1Sessions(budget: Double = 50.0): List<AllocSession> {
        val picks = linkedMapOf(
            "UPPER_A" to listOf("barbell_bench_press", "barbell_row", "seated_dumbbell_shoulder_press", "pull_up", "dumbbell_lateral_raise", "face_pull", "dumbbell_fly", "incline_dumbbell_curl"),
            "LOWER_A" to listOf("back_squat", "romanian_deadlift", "bulgarian_split_squat", "slider_leg_curl", "single_leg_calf_raise", "dead_bug"),
            "UPPER_B" to listOf("pull_up", "incline_dumbbell_press", "overhead_press_barbell", "chest_supported_dumbbell_row", "dumbbell_lateral_raise", "dumbbell_rear_delt_fly", "lying_triceps_extension", "hammer_curl", "dumbbell_shrug"),
            "LOWER_B" to listOf("conventional_deadlift", "goblet_squat", "hip_thrust", "reverse_lunge", "slider_leg_curl", "single_leg_calf_raise", "pallof_press"),
        )
        val days = listOf(1, 2, 4, 5)
        val r = EquipmentResolver(kb)
        return picks.entries.mapIndexed { si, (tid, ids) ->
            val t = kb.templates.getValue(tid)
            val slots = ids.mapIndexed { i, id ->
                val e = kb.requireExercise(id)
                val a = r.resolve(e, eq)
                val band = checkNotNull(kb.prescription(e, n.goal).sets)
                AllocSlot(si, i, t.slots[i], e, checkNotNull(a.optionIndex), checkNotNull(a.effectiveLoadType), maxOf(2, band.lo), band.hi)
            }
            for (a in slots) { val p = a.slot.supersetWith ?: continue; if (slots.any { it.slot.slotId == p }) a.group = listOf(a.slot.slotId, p).sorted().joinToString("+") }
            AllocSession(si, days[si], tid, budget, slots)
        }
    }

    private fun rebal(a: AllocSlot): List<RebalOption> {
        val cx = SelectionContext(n, n.goal, n.trainingAge, eq, targets, n.tiers, emptyList(), inputs.profile, PlannerFixtures.MONDAY)
        return ExerciseSelector(kb, c).candidates(a.slot, cx).map { RebalOption(it.exercise, it.optionIndex, it.loadType) }
    }

    @Test
    fun allocationNeverExceedsTheMinuteBudgetOrTheCaps() {
        val sessions = e1Sessions()
        alloc.allocate(sessions, targets, n.tiers, TrainingAge.INTERMEDIATE, ::rebal)
        for (s in sessions) {
            assertTrue(alloc.minutes(s) <= 50.0 + 1e-9, "${s.templateId} ${alloc.minutes(s)}")
            assertTrue(s.slots.sumOf { it.sets } <= c.sess.sess02CapDefault)
            assertTrue(alloc.sessionFractional(s).all { it <= c.vol.vol06PerSessionCap + 1e-9 })
            for (a in s.slots) {
                assertTrue(a.sets == 0 || a.sets >= c.vol.vol07MinSetsPerAppearance, "${a.slot.slotId} VOL-07")
                assertTrue(a.sets <= a.hi)
                if (a.required) assertTrue(a.sets >= a.lo || RuleIds.TIME_01 in a.extraRules)
            }
        }
    }

    @Test
    fun rebalMovesStayInsideTheSlotPatternList_invariant15() {
        val sessions = e1Sessions()
        val result = alloc.allocate(sessions, targets, n.tiers, TrainingAge.INTERMEDIATE, ::rebal)
        for (s in sessions) for (a in s.slots) assertTrue(a.exercise.pattern in a.slot.patterns, "${s.templateId}/${a.slot.slotId} ${a.exercise.id}")
        assertTrue(result.explanations.all { it.ruleIds.isNotEmpty() })
    }

    @Test
    fun theSoftRpCapNeverKeepsAMuscleBelowItsTarget_R445() {
        val sessions = e1Sessions(budget = 80.0)
        val r = alloc.allocate(sessions, targets, n.tiers, TrainingAge.INTERMEDIATE, ::rebal)
        for ((m, t) in targets) {
            if (n.tiers[m] != MuscleTier.TIER_1) continue
            val achievable = sessions.any { s -> s.slots.any { it.exercise.weightOf(m) >= 1.0 } }
            if (achievable) assertTrue((r.fractional[m] ?: 0.0) >= t - 1.0, "$m ${r.fractional[m]} vs $t")
        }
    }

    @Test
    fun theRequiredMinimumIsTrimmedWhenTheBudgetIsTooSmall_TIME01() {
        val sessions = e1Sessions(budget = 20.0)
        val r = alloc.allocate(sessions, targets, n.tiers, TrainingAge.INTERMEDIATE, ::rebal)
        for (s in sessions) assertTrue(alloc.minutes(s) <= 20.0 + 1e-9 || s.slots.filter { it.sets > 0 }.all { it.slot.role == com.gymcoach.core.model.SlotRole.PRIMARY })
        assertTrue(r.explanations.any { RuleIds.TIME_01 in it.ruleIds })
    }

    @Test
    fun gainIsTheTierWeightedRelativeDeficit() {
        val ledger = DoubleArray(com.gymcoach.core.model.Muscle.entries.size)
        val g = alloc.gainOf(kb.requireExercise("barbell_bench_press"), ledger, targets, n.tiers)
        assertEquals(1.0 + 0.5 * 0.6, g, 1e-9)                                     // chest 1.0 × 12/12 × 1; triceps 0.5 × 7/7 × 0.6; front delts tier 3
        assertEquals(25, alloc.sessCap(TrainingAge.INTERMEDIATE))
        assertEquals(20, alloc.sessCap(TrainingAge.NOVICE))
        assertEquals(32, alloc.sessCap(TrainingAge.ADVANCED))
    }
}
