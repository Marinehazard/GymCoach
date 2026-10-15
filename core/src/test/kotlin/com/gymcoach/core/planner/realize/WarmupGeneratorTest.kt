package com.gymcoach.core.planner.realize

import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.LoadZone
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarmupGeneratorTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val gen = WarmupGenerator(kb, c)
    private val homeEq = SessionEquipment(PlannerFixtures.home)
    private val params = GymParams(kb, homeEq)

    private fun loads(id: String, lt: LoadType, kg: Double, zone: LoadZone, first: Boolean = true, warm: Boolean = false, wu06: Boolean = false) =
        gen.rampSets(kb.requireExercise(id), lt, kg, zone, first, warm, wu06, params)

    @Test
    fun wu02HeavyRampFromTheFirstWorkingLoad() {
        val r = loads("barbell_bench_press", LoadType.BARBELL, 80.0, LoadZone.HEAVY)
        assertEquals(listOf(32.5, 45.0, 55.0, 65.0), r.map { it.loadKg })
        assertEquals(listOf(8, 5, 3, 2), r.map { it.reps?.lo })
        assertTrue(r.all { it.kind == SetKind.RAMP && it.restSeconds == c.wu.wu02RampRestSec })
    }

    @Test
    fun wu02ModerateLightIsolationAndWarmPatternRows() {
        assertEquals(listOf(35.0, 50.0), loads("barbell_row", LoadType.BARBELL, 70.0, LoadZone.MODERATE).map { it.loadKg })   // 50 % / 70 %
        assertEquals(listOf(24.0), loads("dumbbell_romanian_deadlift", LoadType.DUMBBELL, 40.0, LoadZone.LIGHT).map { it.loadKg })   // 60 %
        assertEquals(listOf(45.0), loads("barbell_row", LoadType.BARBELL, 70.0, LoadZone.MODERATE, first = false).map { it.loadKg }) // 65 % warm pattern
        assertEquals(1, loads("dumbbell_curl", LoadType.DUMBBELL, 24.0, LoadZone.MODERATE, first = false).size)                       // isolation 1 × 8 at 55 %
        assertTrue(loads("dumbbell_curl", LoadType.DUMBBELL, 24.0, LoadZone.MODERATE, first = false, warm = true).isEmpty())         // muscle already warm
        assertTrue(gen.rampSets(kb.requireExercise("pull_up"), LoadType.BODYWEIGHT, null, LoadZone.MODERATE, true, false, false, params).isEmpty())
        // Never below the empty bar.
        assertTrue(loads("barbell_bench_press", LoadType.BARBELL, 30.0, LoadZone.HEAVY).all { (it.loadKg ?: 0.0) >= 20.0 })
    }

    @Test
    fun wu06AddsAnExtraRampSetAndTwoMinutes() {
        val r = loads("barbell_bench_press", LoadType.BARBELL, 80.0, LoadZone.HEAVY, wu06 = true)
        assertEquals(5, r.size)
        val young = TodayContext(PlannerFixtures.MONDAY, PlannerFixtures.HOME_ID)
        assertTrue(!gen.wu06(young, 38))
        assertTrue(gen.wu06(young, 40))
        assertTrue(gen.wu06(young.copy(ambientCold = true), 30))
        assertTrue(gen.wu06(young.copy(minutesSinceWaking = 45), 30))
        val w = gen.generate(emptyList(), 60, young, 45, emptyList(), homeEq, params, emptyMap())
        assertEquals(12, w.totalMinutes)
        assertTrue(RuleIds.WU_06 in w.explanation.ruleIds)
    }

    @Test
    fun wu05NoRaisePhaseAtOrBelow45MinutesAndTwoDrills() {
        val today = TodayContext(PlannerFixtures.MONDAY, PlannerFixtures.HOME_ID)
        val trimmed = gen.generate(emptyList(), 45, today, 30, emptyList(), homeEq, params, emptyMap())
        assertEquals(0, trimmed.raiseMinutes)
        assertEquals(5, trimmed.totalMinutes)
        assertTrue(RuleIds.WU_05 in trimmed.explanation.ruleIds)
        val full = gen.generate(emptyList(), 60, today, 30, emptyList(), homeEq, params, emptyMap())
        assertEquals(10, full.totalMinutes)
        assertEquals(5, full.raiseMinutes)
        assertEquals("jump_rope", full.raiseExerciseId)                         // home: no bike/rower/treadmill
        assertEquals("stationary_bike", gen.raiseExercise(SessionEquipment(PlannerFixtures.base)))
    }

    @Test
    fun drillSelectionFollowsTheSection57ListsInFileOrder_MOB01_MOB07() {
        fun ids(vararg p: MovementPattern) = gen.drills(p.toList(), 4, emptyList(), homeEq).map { it.drillId }
        assertEquals(listOf("bodyweight_squat", "hip_90_90", "knee_to_wall_pulses", "worlds_greatest_stretch"), ids(MovementPattern.SQUAT))
        assertEquals(listOf("cat_cow", "glute_bridge", "band_lateral_walk", "worlds_greatest_stretch"), ids(MovementPattern.HINGE))
        assertEquals(listOf("band_pull_apart", "shoulder_pass_through", "wall_slide"), ids(MovementPattern.HORIZONTAL_PUSH).take(3))
        assertEquals(listOf("shoulder_pass_through", "wall_slide", "thoracic_rotation"), ids(MovementPattern.VERTICAL_PUSH).take(3))
        assertEquals(listOf("band_pull_apart", "thoracic_rotation", "dead_hang"), ids(MovementPattern.HORIZONTAL_PULL).take(3))
        // Full body (squat + push): squat drill, push drill alternating.
        assertEquals(listOf("bodyweight_squat", "band_pull_apart", "hip_90_90", "shoulder_pass_through"), ids(MovementPattern.SQUAT, MovementPattern.HORIZONTAL_PUSH))
        // dead_hang is dosed in seconds; a limitation joint adds one drill.
        val hang = gen.drills(listOf(MovementPattern.HORIZONTAL_PULL), 3, emptyList(), homeEq).first { it.drillId == "dead_hang" }
        assertEquals(PlannerFixtures.band(20, 30), hang.seconds)
        val withKnee = gen.drills(listOf(MovementPattern.HORIZONTAL_PUSH), 2, listOf(PlannerFixtures.lim(LimitationRegion.KNEE, 2)), homeEq)
        assertEquals(3, withKnee.size)
        assertEquals("knee_to_wall_pulses", withKnee.last().drillId)
        // Band drills need bands: none at the hotel.
        assertTrue(gen.drills(listOf(MovementPattern.HORIZONTAL_PUSH), 4, emptyList(), SessionEquipment(PlannerFixtures.hotel)).none { it.drillId == "band_pull_apart" })
    }
}
