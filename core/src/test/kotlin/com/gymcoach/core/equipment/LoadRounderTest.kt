package com.gymcoach.core.equipment

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.LoadType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** SPEC §3.9 rounding table; achievableMaxKg × implements; > 5 % → ROUNDING. */
class LoadRounderTest {
    private val kb = Gyms.kb
    private val rounder = LoadRounder(kb)
    private val base = GymParams(kb, Gyms.eq(Gyms.preset("base_gym")))
    private val hotel = GymParams(kb, Gyms.eq(Gyms.preset("hotel_gym")))
    private val home = GymParams(kb, Gyms.eq(Gyms.preset("home_gym")))

    private fun r(id: String, kg: Double, lt: LoadType, p: GymParams = base) = rounder.round(kg, kb.requireExercise(id), lt, p)

    @Test
    fun barbellBarPlusPlatePairs() {
        val a = r("barbell_bench_press", 82.4, LoadType.BARBELL)
        assertEquals(82.5, a.kg); assertEquals("82.5 kg", a.label)
        assertEquals(80.0, r("barbell_bench_press", 81.25, LoadType.BARBELL).kg, "tie → lower")
        assertEquals("80 kg", r("barbell_bench_press", 81.25, LoadType.BARBELL).label)
        assertEquals(20.0, r("barbell_bench_press", 10.0, LoadType.BARBELL).kg, "never below the bar")
        val micro = GymParams(kb, Gyms.eq(Gyms.of("barbell" to emptyMap(), "plates" to mapOf("has_microplates" to Gyms.bool(true)))))
        assertEquals(81.0, r("barbell_bench_press", 81.3, LoadType.BARBELL, micro).kg)
        val trap = GymParams(kb, Gyms.eq(Gyms.ids("trap_bar", "plates")))
        assertEquals(25.0, r("trap_bar_deadlift", 20.0, LoadType.BARBELL, trap).kg, "trap bar 25 kg")
        val ez = GymParams(kb, Gyms.eq(Gyms.ids("ez_bar", "plates", "preacher_bench")))
        assertEquals(12.5, r("preacher_curl", 13.0, LoadType.BARBELL, ez).kg, "EZ bar 10 + 2.5")
        val smith = GymParams(kb, Gyms.eq(Gyms.ids("smith_machine", "plates", "flat_bench")))
        assertEquals(60.0, r("smith_bench_press", 59.5, LoadType.SMITH, smith).kg)
    }

    @Test
    fun dumbbellPairAndSingle() {
        val pair = r("dumbbell_bench_press", 45.0, LoadType.DUMBBELL)
        assertEquals(44.0, pair.kg); assertEquals("2×22 kg", pair.label); assertTrue(!pair.perHand)
        val single = r("dumbbell_row", 23.0, LoadType.DUMBBELL)
        assertEquals(22.0, single.kg); assertEquals("22 kg", single.label); assertTrue(single.perHand)
        val capped = r("dumbbell_bench_press", 60.0, LoadType.DUMBBELL, hotel)
        assertEquals(40.0, capped.kg); assertEquals("2×20 kg", capped.label)
        assertTrue(capped.roundingErrorPct > LoadRounder.ROUNDING_WARN_PCT)
        assertEquals(2.0, r("dumbbell_lateral_raise", 1.0, LoadType.DUMBBELL).kg / 2, "never below the lightest dumbbell")
    }

    @Test
    fun kettlebellNearestSize() {
        val a = r("goblet_squat", 20.0, LoadType.KETTLEBELL, home)
        assertEquals(16.0, a.kg, "home kettlebells 16/24, tie → lower"); assertEquals("16 kg", a.label)
        assertEquals(24.0, r("goblet_squat", 22.0, LoadType.KETTLEBELL, home).kg)
        val pair = r("farmers_carry", 44.0, LoadType.KETTLEBELL, home)
        assertEquals(48.0, pair.kg); assertEquals("2×24 kg", pair.label)
    }

    @Test
    fun stackHalfStepPlateLoadedAndAssist() {
        val s = r("lat_pulldown", 47.0, LoadType.MACHINE)
        assertEquals(45.0, s.kg); assertEquals("stack 45 kg", s.label)
        val half = GymParams(kb, Gyms.eq(Gyms.of("lat_pulldown_machine" to mapOf("half_step_kg" to Gyms.num(2.5)))))
        assertEquals(47.5, r("lat_pulldown", 47.0, LoadType.MACHINE, half).kg)
        assertEquals(5.0, r("cable_triceps_pushdown", 1.0, LoadType.CABLE).kg, "one stack plate minimum")
        val sled = r("leg_press_45", 83.0, LoadType.MACHINE)
        assertEquals(82.5, sled.kg); assertEquals("82.5 kg on the sled", sled.label)
        val sledKg = GymParams(kb, Gyms.eq(Gyms.of("leg_press_45" to mapOf("sled_kg" to Gyms.num(40.0)), "plates" to emptyMap())))
        assertEquals("82.5 kg on the sled (+40 kg sled)", r("leg_press_45", 83.0, LoadType.MACHINE, sledKg).label)
        val assist = r("assisted_pull_up", 23.0, LoadType.MACHINE)
        assertEquals(25.0, assist.kg); assertEquals("assist 25 kg", assist.label)
    }

    @Test
    fun bodyweightExternalLoadAndBandLevel() {
        val dip = r("chest_dip", 9.0, LoadType.BODYWEIGHT)
        assertEquals(10.0, dip.kg); assertEquals("BW+10 kg", dip.label)
        assertEquals("BW", r("chest_dip", 0.0, LoadType.BODYWEIGHT).label)
        assertEquals("band: medium", r("band_pull_apart", 1.2, LoadType.BAND).label)
        assertEquals("band: x-heavy", r("band_pull_apart", 9.0, LoadType.BAND).label)
        val levels = GymParams(kb, Gyms.eq(Gyms.of("resistance_bands" to mapOf("levels" to JsonArray(listOf(JsonPrimitive("yellow"), JsonPrimitive("red")))))))
        assertEquals("band: red", r("band_pull_apart", 1.0, LoadType.BAND, levels).label)
    }

    @Test
    fun achievableMaxTimesImplements() {
        assertEquals(40.0, rounder.achievableMaxKg(kb.requireExercise("dumbbell_bench_press"), LoadType.DUMBBELL, hotel))
        assertEquals(20.0, rounder.achievableMaxKg(kb.requireExercise("dumbbell_row"), LoadType.DUMBBELL, hotel))
        assertEquals(20.0, rounder.achievableMaxKg(kb.requireExercise("goblet_squat"), LoadType.DUMBBELL, hotel))
        assertEquals(24.0, rounder.achievableMaxKg(kb.requireExercise("goblet_squat"), LoadType.KETTLEBELL, home))
        assertEquals(48.0, rounder.achievableMaxKg(kb.requireExercise("farmers_carry"), LoadType.KETTLEBELL, home))
        assertNull(rounder.achievableMaxKg(kb.requireExercise("barbell_bench_press"), LoadType.BARBELL, base), "plates: unlimited")
        assertNull(rounder.achievableMaxKg(kb.requireExercise("lat_pulldown"), LoadType.MACHINE, base))
        val fixed = GymParams(kb, Gyms.eq(Gyms.ids("fixed_weight_barbells")))
        assertEquals(40.0, rounder.achievableMaxKg(kb.requireExercise("barbell_curl"), LoadType.BARBELL, fixed))
        assertEquals(32.5, rounder.round(33.0, kb.requireExercise("barbell_curl"), LoadType.BARBELL, fixed).kg)
    }

    @Test
    fun roundingAbove5PercentIsReportedWithTheRoundingRule() {
        val a = r("dumbbell_lateral_raise", 15.0, LoadType.DUMBBELL)
        assertEquals(16.0, a.kg)
        assertTrue(a.roundingErrorPct > LoadRounder.ROUNDING_WARN_PCT)
        val e = Explanations.of(RuleIds.ROUNDING, "load rounded", "desired" to "15", "loaded" to a.label, confidence = Confidence.WEAK)
        assertEquals(listOf(RuleIds.ROUNDING), e.ruleIds)
        assertTrue(kb.rules.get(RuleIds.ROUNDING) != null)
        assertTrue(r("barbell_bench_press", 82.4, LoadType.BARBELL).roundingErrorPct < 1.0)
    }
}
