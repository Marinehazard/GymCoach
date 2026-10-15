package com.gymcoach.core.planner

import com.gymcoach.core.model.LoadType
import kotlin.test.Test
import kotlin.test.assertEquals

/** §4.6 SESS-01 time model rows (E1 numbers). */
class TimeModelTest {
    private val kb = PlannerFixtures.kb
    private val t = TimeModel(PlannerConstants.DEFAULT)
    private fun cost(id: String, lt: LoadType, heavy: Boolean = false, level: Int = 0) = t.costPerSet(kb.requireExercise(id), lt, heavy, level)

    @Test
    fun costPerSetRows() {
        assertEquals(4.0, cost("back_squat", LoadType.BARBELL, heavy = true))
        assertEquals(4.0, cost("romanian_deadlift", LoadType.BARBELL))
        assertEquals(3.5, cost("barbell_bench_press", LoadType.BARBELL, heavy = true))
        assertEquals(2.5, cost("barbell_bench_press", LoadType.BARBELL, heavy = false))
        assertEquals(2.5, cost("pull_up", LoadType.BODYWEIGHT))
        assertEquals(2.5, cost("hip_thrust", LoadType.BARBELL))                       // HIP_EXTENSION_ISOLATION = moderate compound
        assertEquals(1.75, cost("dumbbell_lateral_raise", LoadType.DUMBBELL))
        assertEquals(4.25, cost("bulgarian_split_squat", LoadType.DUMBBELL), 1e-9)      // 2.5 × 1.7 (UNI-02)
        assertEquals(2.975, cost("single_leg_calf_raise", LoadType.BODYWEIGHT), 1e-9)   // 1.75 × 1.7
        assertEquals(1.5, cost("dumbbell_lateral_raise", LoadType.DUMBBELL, level = 4)) // concession level 4
    }

    @Test
    fun supersetPairsAndLeftoversRest03() {
        // UPPER_A s5 ⟂ s6: lateral raise 4 ⟂ face pull 2 = 2 pairs × 2.5 + 2 singles × 1.75 = 8.5.
        assertEquals(8.5, t.pairMinutes(1.75, 4, 1.75, 2), 1e-9)
        // LOWER_A calf 2 ⟂ dead bug 2: max(2.5, 0.7 × (2.975 + 1.75)) = 3.3075 per pair.
        assertEquals(6.615, t.pairMinutes(2.975, 2, 1.75, 2), 1e-9)
        val items = listOf(TimeModel.Item(3.5, 4, null), TimeModel.Item(1.75, 4, "g"), TimeModel.Item(1.75, 2, "g"))
        assertEquals(14.0 + 8.5, t.minutesOf(items), 1e-9)
    }

    @Test
    fun warmupMinutesWu01Wu05Wu06() {
        assertEquals(10, t.warmupMinutes(60, false))
        assertEquals(5, t.warmupMinutes(45, false))
        assertEquals(12, t.warmupMinutes(60, true))
        assertEquals(7, t.warmupMinutes(30, true))
    }
}
