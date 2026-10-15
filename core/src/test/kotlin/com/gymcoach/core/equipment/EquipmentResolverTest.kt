package com.gymcoach.core.equipment

import com.gymcoach.core.knowledge.KnowledgeFixture
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.SessionEquipment
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Test helpers for the equipment package. */
object Gyms {
    val kb = KnowledgeFixture.kb
    val T0: Instant = Instant.parse("2026-10-01T08:00:00Z")

    fun preset(id: String): GymSet = Presets.gymFromPreset(kb, id, id, "gym_$id", T0)

    fun of(vararg items: Pair<String, Map<String, JsonElement>>): GymSet =
        GymSet(id = "gym_test", name = "test", items = items.map { GymItem(it.first, it.second) }, createdAt = T0)

    fun ids(vararg ids: String): GymSet = of(*ids.map { it to emptyMap<String, JsonElement>() }.toTypedArray())

    fun eq(gym: GymSet, spotter: Boolean = false, extras: List<GymItem> = emptyList(), removed: Set<String> = emptySet()) =
        SessionEquipment(gym, extras, removed, spotter)

    fun num(v: Double): JsonElement = JsonPrimitive(v)
    fun bool(v: Boolean): JsonElement = JsonPrimitive(v)
}

class EquipmentResolverTest {
    private val kb = Gyms.kb
    private val resolver = EquipmentResolver(kb)

    @Test
    fun rackWithoutSafetiesFailsRackOrSpotter() {
        val gym = Gyms.of("power_rack" to mapOf("has_safeties" to Gyms.bool(false)), "barbell" to emptyMap(), "plates" to emptyMap(), "flat_bench" to emptyMap())
        assertFalse(resolver.isItemSatisfied("rack_or_spotter", Gyms.eq(gym)))
        assertTrue(resolver.isItemSatisfied("rack_any", Gyms.eq(gym)))
        assertTrue(resolver.isItemSatisfied("rack_or_spotter", Gyms.eq(Gyms.ids("power_rack"))), "has_safeties defaults to true")
        assertFalse(resolver.isItemSatisfied("rack_or_spotter", Gyms.eq(Gyms.ids("squat_rack"))), "squat stands never qualify")
        assertTrue(resolver.isItemSatisfied("rack_or_spotter", Gyms.eq(Gyms.ids("half_rack"))))
    }

    @Test
    fun spotterFlagSatisfiesRackOrSpotter() {
        val gym = Gyms.ids("barbell", "plates", "flat_bench", "squat_rack")
        assertFalse(resolver.isItemSatisfied("rack_or_spotter", Gyms.eq(gym)))
        assertTrue(resolver.isItemSatisfied("rack_or_spotter", Gyms.eq(gym, spotter = true)))
        assertTrue(resolver.isItemSatisfied("spotter", Gyms.eq(gym, spotter = true)))
        assertFalse(resolver.isItemSatisfied("spotter", Gyms.eq(gym)))
    }

    @Test
    fun benchUnavailableWithoutSafetiesOrSpotter() {
        val gym = Gyms.ids("barbell", "plates", "flat_bench", "squat_rack", "dumbbells")
        val bench = kb.requireExercise("barbell_bench_press")
        val a = resolver.resolve(bench, Gyms.eq(gym))
        assertFalse(a.available)
        assertNull(a.optionIndex)
        assertNull(a.effectiveLoadType)
        assertEquals(listOf("rack_or_spotter"), a.missing)
        val withSpotter = resolver.resolve(bench, Gyms.eq(gym, spotter = true))
        assertTrue(withSpotter.available && withSpotter.safetiesOrSpotter)
        assertEquals(0, withSpotter.optionIndex)
        // the squat family on squat stands stays available, without safeties (§3.1 RIR-floor path)
        val squat = resolver.resolve(kb.requireExercise("back_squat"), Gyms.eq(gym))
        assertTrue(squat.available && !squat.safetiesOrSpotter)
        // close-grip bench falls back to its Smith option, load type smith
        val cg = resolver.resolve(kb.requireExercise("close_grip_bench_press"), Gyms.eq(Gyms.ids("smith_machine", "flat_bench")))
        assertTrue(cg.available)
        assertEquals(1, cg.optionIndex)
        assertEquals(LoadType.SMITH, cg.effectiveLoadType)
    }

    @Test
    fun pullupBarHonoursPowerRackParam() {
        val noBar = Gyms.of("power_rack" to mapOf("has_pullup_bar" to Gyms.bool(false)))
        assertFalse(resolver.isItemSatisfied("pullup_bar_any", Gyms.eq(noBar)))
        assertFalse(resolver.resolve(kb.requireExercise("pull_up"), Gyms.eq(noBar)).available)
        assertTrue(resolver.isItemSatisfied("pullup_bar_any", Gyms.eq(Gyms.ids("power_rack"))))
        assertTrue(resolver.isItemSatisfied("pullup_bar_any", Gyms.eq(Gyms.ids("pull_up_bar"))))
        assertTrue(resolver.isItemSatisfied("pullup_bar_any", Gyms.eq(Gyms.ids("assisted_pullup_dip_machine"))))
    }

    @Test
    fun emptyOptionIsAlwaysSatisfied() {
        val a = resolver.resolve(kb.requireExercise("push_up"), Gyms.eq(Gyms.ids()))
        assertTrue(a.available)
        assertEquals(0, a.optionIndex)
        assertEquals(LoadType.BODYWEIGHT, a.effectiveLoadType)
        assertTrue(resolver.isItemSatisfied("bb_loaded", Gyms.eq(Gyms.ids("barbell", "plates"))))
        assertFalse(resolver.isItemSatisfied("bb_loaded", Gyms.eq(Gyms.ids("barbell"))))
    }

    @Test
    fun loadTypeOverrideOfTheSatisfiedOption() {
        val preacher = kb.requireExercise("preacher_curl")
        assertEquals(LoadType.BARBELL, resolver.resolve(preacher, Gyms.eq(Gyms.ids("preacher_bench", "ez_bar", "plates", "dumbbells"))).effectiveLoadType)
        val db = resolver.resolve(preacher, Gyms.eq(Gyms.ids("preacher_bench", "dumbbells")))
        assertEquals(1, db.optionIndex)
        assertEquals(LoadType.DUMBBELL, db.effectiveLoadType)
        assertEquals(LoadType.MACHINE, resolver.resolve(preacher, Gyms.eq(Gyms.ids("preacher_curl_machine"))).effectiveLoadType)
        val split = resolver.resolve(kb.requireExercise("split_squat"), Gyms.eq(Gyms.ids()))
        assertEquals(LoadType.BODYWEIGHT, split.effectiveLoadType)
    }

    @Test
    fun removalsAndExtrasApply() {
        val base = Gyms.preset("base_gym")
        val legPress = kb.requireExercise("leg_press_45")
        assertTrue(resolver.resolve(legPress, Gyms.eq(base)).available)
        assertFalse(resolver.resolve(legPress, Gyms.eq(base, removed = setOf("leg_press_45"))).available)
        val hotel = Gyms.preset("hotel_gym")
        val trx = kb.requireExercise("inverted_row")
        assertFalse(resolver.resolve(trx, Gyms.eq(hotel)).available)
        assertTrue(resolver.resolve(trx, Gyms.eq(hotel, extras = listOf(GymItem("suspension_trainer")))).available)
    }

    @Test
    fun hotelMakesAtLeast40AndTravelKitAtLeast25Available() {
        val hotel = resolver.availableExerciseIds(Gyms.eq(Gyms.preset("hotel_gym")))
        assertTrue(hotel.size >= 40, "hotel: ${hotel.size}")
        val kit = resolver.availableExerciseIds(Gyms.eq(Gyms.preset("travel_kit")))
        assertTrue(kit.size >= 25, "travel kit: ${kit.size}")
        val base = resolver.availableExerciseIds(Gyms.eq(Gyms.preset("base_gym")))
        assertEquals(base.toList(), kb.exerciseOrder.filter { it in base }, "knowledge order")
        assertTrue("barbell_bench_press" in base && "pendulum_squat" !in base)
    }

    @Test
    fun missingListsTheBestOption() {
        val a = resolver.resolve(kb.requireExercise("hip_thrust"), Gyms.eq(Gyms.ids("barbell", "flat_bench")))
        assertFalse(a.available)
        assertEquals(listOf("bb_loaded"), a.missing)
    }
}
