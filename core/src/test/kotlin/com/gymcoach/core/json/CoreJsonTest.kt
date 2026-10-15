package com.gymcoach.core.json

import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.EquipmentOption
import com.gymcoach.core.model.Fixtures
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.TokenUsage
import com.gymcoach.core.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the three Json configurations of §2.1 (HR-9). */
class CoreJsonTest {

    private val weightWithExtraKey = "{\"entered\":135.0,\"unit\":\"lb\",\"kg\":61.23496995,\"extra\":true}"

    @Test
    fun threeDistinctInstances() {
        assertNotSame(CoreJson.stored, CoreJson.assets)
        assertNotSame(CoreJson.stored, CoreJson.wire)
        assertNotSame(CoreJson.assets, CoreJson.wire)
    }

    @Test
    fun storedIgnoresUnknownKeys() {
        val w = CoreJson.stored.decodeFromString(EnteredWeight.serializer(), weightWithExtraKey)
        assertEquals(EnteredWeight(135.0, WeightUnit.LB, 61.23496995), w)
    }

    @Test
    fun assetsRejectsUnknownKeys() {
        assertFails { CoreJson.assets.decodeFromString(EnteredWeight.serializer(), weightWithExtraKey) }
    }

    @Test
    fun wireIgnoresUnknownKeys() {
        val u = CoreJson.wire.decodeFromString(TokenUsage.serializer(), "{\"input_tokens\":5,\"brand_new_field\":1}")
        assertEquals(5, u.inputTokens)
    }

    @Test
    fun storedOmitsNulls() {
        val encoded = CoreJson.stored.encodeToString(EquipmentOption.serializer(), EquipmentOption(allOf = listOf("barbell")))
        assertFalse(encoded.contains("load_type"), encoded)
        assertFalse(encoded.contains("null"), encoded)
    }

    @Test
    fun assetsOmitsNulls() {
        val encoded = CoreJson.assets.encodeToString(EquipmentOption.serializer(), EquipmentOption(allOf = listOf("barbell")))
        assertFalse(encoded.contains("note"), encoded)
    }

    @Test
    fun wireOmitsNulls() {
        val encoded = CoreJson.wire.encodeToString(TokenUsage.serializer(), TokenUsage())
        assertFalse(encoded.contains("thinking_tokens"), encoded)
    }

    @Test
    fun missingNullableFieldDecodesAsNull() {
        val decoded = CoreJson.assets.decodeFromString(EquipmentOption.serializer(), "{\"all_of\":[\"barbell\"]}")
        assertNull(decoded.loadType)
        assertNull(decoded.note)
    }

    @Test
    fun storedEncodesDefaults() {
        val encoded = CoreJson.stored.encodeToString(EquipmentOption.serializer(), EquipmentOption(allOf = listOf("barbell")))
        assertTrue(encoded.contains("\"preferred\":false"), encoded)
    }

    @Test
    fun assetsEncodesDefaults() {
        val encoded = CoreJson.assets.encodeToString(EquipmentOption.serializer(), EquipmentOption(allOf = listOf("barbell"), loadType = LoadType.SMITH))
        assertTrue(encoded.contains("\"preferred\":false"), encoded)
        assertTrue(encoded.contains("\"load_type\":\"smith\""), encoded)
    }

    @Test
    fun wireEncodesDefaults() {
        val encoded = CoreJson.wire.encodeToString(TokenUsage.serializer(), TokenUsage())
        assertEquals("{\"input_tokens\":0,\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}", encoded)
    }

    @Test
    fun storedUsesTypeAsClassDiscriminator() {
        val encoded = CoreJson.stored.encodeToString(PlanOverride.serializer(), Fixtures.planOverride)
        assertTrue(encoded.contains("\"mutation\":{\"type\":\"swap_exercise\""), encoded)
    }

    @Test
    fun sealedMutationEncodesDiscriminatorFirst() {
        val encoded = CoreJson.stored.encodeToString(PlanMutation.serializer(), Fixtures.changeVolume)
        assertTrue(encoded.startsWith("{\"type\":\"change_volume\""), encoded)
    }

    @Test
    fun sealedMutationDecodesEverySubclassByDiscriminator() {
        for (m in Fixtures.mutations) {
            val encoded = CoreJson.stored.encodeToString(PlanMutation.serializer(), m)
            val decoded = CoreJson.stored.decodeFromString(PlanMutation.serializer(), encoded)
            assertEquals(m, decoded)
            assertEquals(m::class, decoded::class)
        }
    }

    @Test
    fun unknownDiscriminatorFails() {
        assertFails { CoreJson.stored.decodeFromString(PlanMutation.serializer(), "{\"type\":\"teleport\",\"rationale\":\"x\"}") }
    }

    @Test
    fun noPrettyPrint() {
        val encoded = CoreJson.stored.encodeToString(EnteredWeight.serializer(), EnteredWeight(80.0, WeightUnit.KG, 80.0))
        assertFalse(encoded.contains("\n"), encoded)
        assertFalse(encoded.contains(": "), encoded)
    }
}
