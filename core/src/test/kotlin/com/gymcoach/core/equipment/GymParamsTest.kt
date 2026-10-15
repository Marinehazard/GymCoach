package com.gymcoach.core.equipment

import com.gymcoach.core.model.GymItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GymParamsTest {
    private val kb = Gyms.kb

    @Test
    fun itemParamsOverrideCatalogueDefaults() {
        val hotel = GymParams(kb, Gyms.eq(Gyms.preset("hotel_gym")))
        assertEquals(20.0, hotel.dumbbellMaxKg)
        assertEquals(2.0, hotel.dumbbellMinKg)
        assertEquals(2.0, hotel.dumbbellStepKg)
        assertNull(hotel.smallestPlateKg, "no plates in the hotel")
        assertFalse(hotel.hasMicroplates)
        assertEquals(20.0, hotel.barKg, "fallback when no barbell")
        val base = GymParams(kb, Gyms.eq(Gyms.preset("base_gym")))
        assertEquals(1.25, base.smallestPlateKg)
        assertEquals(listOf(8.0, 12.0, 16.0, 20.0, 24.0, 28.0, 32.0), base.kettlebellsKg, "catalogue default list")
        assertEquals(true, base.bool("power_rack", "has_safeties"))
        assertEquals(5.0, base.stackStepKg("lat_pulldown_machine"))
        assertNull(base.halfStepKg("lat_pulldown_machine"), "half step only when the gym declares one")
        assertEquals(listOf("light", "medium", "heavy", "x-heavy"), base.bandLevels())
        assertNull(base.number("leg_extension_machine", "stack_step_kg"), "not declared in R4")
        assertTrue(base.has("smith_machine"))
        assertFalse(base.has("ghd"))
    }

    @Test
    fun dayExtrasOverrideTheGymAndRemovalsHide() {
        val home = Gyms.preset("home_gym")
        val withLight = Gyms.eq(home, extras = listOf(GymItem("dumbbells", mapOf("max_kg" to Gyms.num(10.0)))))
        assertEquals(10.0, GymParams(kb, withLight).dumbbellMaxKg)
        assertNull(GymParams(kb, Gyms.eq(home, removed = setOf("dumbbells"))).dumbbellMaxKg)
        assertEquals(listOf(16.0, 24.0), GymParams(kb, Gyms.eq(home)).kettlebellsKg)
    }
}
