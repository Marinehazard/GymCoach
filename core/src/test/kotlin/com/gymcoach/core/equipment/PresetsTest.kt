package com.gymcoach.core.equipment

import com.gymcoach.core.model.GymItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class PresetsTest {
    private val kb = Gyms.kb

    @Test
    fun presetsCarryR4ItemListsParamsAndMinutes() {
        val hotel = Gyms.preset(Presets.HOTEL_GYM)
        assertEquals(listOf("dumbbells", "adjustable_bench", "mat", "treadmill", "stationary_bike", "elliptical"), hotel.items.map { it.equipmentId })
        assertEquals(mapOf("max_kg" to JsonPrimitive(20), "min_kg" to JsonPrimitive(2), "step_kg" to JsonPrimitive(2)), hotel.items[0].params)
        assertEquals(45, hotel.defaultSessionMinutes)
        assertEquals("hotel_gym", hotel.presetOrigin)
        assertEquals(Gyms.T0, hotel.createdAt)
        assertEquals(60, Gyms.preset(Presets.BASE_GYM).defaultSessionMinutes)
        assertEquals(60, Gyms.preset(Presets.HOME_GYM).defaultSessionMinutes)
        assertNull(Gyms.preset(Presets.TRAVEL_KIT).defaultSessionMinutes)
        val home = Gyms.preset(Presets.HOME_GYM)
        assertEquals(JsonArray(listOf(JsonPrimitive(16), JsonPrimitive(24))), home.items.single { it.equipmentId == "kettlebells" }.params["available_kg"])
        assertEquals(JsonPrimitive(true), home.items.single { it.equipmentId == "power_rack" }.params["has_pullup_bar"])
        assertEquals(JsonPrimitive(50), Gyms.preset(Presets.BASE_GYM).items.single { it.equipmentId == "dumbbells" }.params["max_kg"])
        assertEquals(41, Gyms.preset(Presets.BASE_GYM).items.size)
    }

    @Test
    fun presetItemsMatchTheDefaultInColumns() {
        for (p in kb.presets.values) {
            val marked = kb.equipment.values.filter { p.id in it.defaultIn }.map { it.id }.toSet()
            assertEquals(marked, p.items.map { it.equipmentId }.toSet(), p.id)
        }
        assertEquals(setOf("suspension_trainer", "jump_rope", "resistance_bands", "band_door_anchor", "lifting_straps"), kb.equipment.values.filter { it.packable }.map { it.id }.toSet())
        assertEquals(listOf("spotter"), kb.equipment.values.filter { it.sessionFlag }.map { it.id })
    }

    @Test
    fun unknownPresetFails() {
        assertFailsWith<IllegalArgumentException> { Presets.gymFromPreset(kb, "castle_gym", "x", "y", Gyms.T0) }
    }

    @Test
    fun mergeTravelKitIsIdempotentAndKeepsExistingParams() {
        val home = Gyms.preset(Presets.HOME_GYM).copy(items = Gyms.preset(Presets.HOME_GYM).items.map {
            if (it.equipmentId == "resistance_bands") GymItem("resistance_bands", mapOf("levels" to JsonArray(listOf(JsonPrimitive("red"))))) else it
        })
        val merged = Presets.mergeTravelKit(kb, home)
        assertTrue(merged.travelKitMerged)
        val ids = merged.items.map { it.equipmentId }
        assertEquals(ids.toSet().size, ids.size, "no duplicates")
        assertTrue(ids.containsAll(listOf("band_door_anchor", "suspension_trainer", "lifting_straps", "jump_rope", "resistance_bands")))
        assertEquals(JsonArray(listOf(JsonPrimitive("red"))), merged.items.single { it.equipmentId == "resistance_bands" }.params["levels"])
        assertEquals(merged, Presets.mergeTravelKit(kb, merged))
        val hotelPlusKit = Presets.mergeTravelKit(kb, Gyms.preset(Presets.HOTEL_GYM))
        val resolver = EquipmentResolver(kb)
        assertTrue(resolver.availableExerciseIds(Gyms.eq(hotelPlusKit)).size > resolver.availableExerciseIds(Gyms.eq(Gyms.preset(Presets.HOTEL_GYM))).size)
    }
}
