package com.gymcoach.core.json

import com.gymcoach.core.model.Band
import com.gymcoach.core.model.GoalPrescription
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class SerializersTest {

    private val json = Json

    // --- LocalDateIsoSerializer ---

    @Test
    fun localDateEncodesAsIsoString() {
        assertEquals("\"2026-09-05\"", json.encodeToString(LocalDateIsoSerializer, LocalDate.of(2026, 9, 5)))
    }

    @Test
    fun localDateDecodesFromIsoString() {
        assertEquals(LocalDate.of(2026, 9, 5), json.decodeFromString(LocalDateIsoSerializer, "\"2026-09-05\""))
    }

    @Test
    fun localDateRoundTripsAtYearBoundaries() {
        for (d in listOf(LocalDate.of(2024, 2, 29), LocalDate.of(1999, 12, 31), LocalDate.of(2030, 1, 1))) {
            assertEquals(d, json.decodeFromString(LocalDateIsoSerializer, json.encodeToString(LocalDateIsoSerializer, d)))
        }
    }

    @Test
    fun localDateRejectsGarbage() {
        assertFails { json.decodeFromString(LocalDateIsoSerializer, "\"05.09.2026\"") }
    }

    @Test
    fun localDateDescriptorIsPrimitiveString() {
        assertEquals("java.time.LocalDate", LocalDateIsoSerializer.descriptor.serialName)
    }

    // --- InstantIsoSerializer ---

    @Test
    fun instantEncodesAsUtcIsoString() {
        assertEquals("\"2026-09-05T06:40:00Z\"", json.encodeToString(InstantIsoSerializer, Instant.parse("2026-09-05T06:40:00Z")))
    }

    @Test
    fun instantDecodesFromIsoString() {
        assertEquals(Instant.parse("2026-09-05T06:40:00Z"), json.decodeFromString(InstantIsoSerializer, "\"2026-09-05T06:40:00Z\""))
    }

    @Test
    fun instantRoundTripsWithMillis() {
        val i = Instant.ofEpochMilli(1_788_950_400_123L)
        assertEquals(i, json.decodeFromString(InstantIsoSerializer, json.encodeToString(InstantIsoSerializer, i)))
    }

    @Test
    fun instantWithOffsetIsNormalisedToUtcOnDecode() {
        // Instant.parse accepts an offset and normalises it to UTC.
        val decoded = json.decodeFromString(InstantIsoSerializer, "\"2026-09-05T08:40:00+02:00\"")
        assertEquals(Instant.parse("2026-09-05T06:40:00Z"), decoded)
    }

    @Test
    fun instantDescriptorIsPrimitiveString() {
        assertEquals("java.time.Instant", InstantIsoSerializer.descriptor.serialName)
    }

    // --- BandSerializer ---

    @Test
    fun bandEncodesAsTwoElementArray() {
        assertEquals("[3,6]", json.encodeToString(BandSerializer, Band(3, 6)))
    }

    @Test
    fun bandDecodesFromTwoElementArray() {
        assertEquals(Band(3, 6), json.decodeFromString(BandSerializer, "[3,6]"))
    }

    @Test
    fun bandDecodesWithWhitespace() {
        assertEquals(Band(8, 12), json.decodeFromString(BandSerializer, "[ 8 , 12 ]"))
    }

    @Test
    fun bandAllowsLoEqualHi() {
        assertEquals(Band(5, 5), json.decodeFromString(BandSerializer, "[5,5]"))
    }

    @Test
    fun bandRejectsOneElement() {
        assertFails { json.decodeFromString(BandSerializer, "[3]") }
    }

    @Test
    fun bandRejectsThreeElements() {
        assertFails { json.decodeFromString(BandSerializer, "[3,6,9]") }
    }

    @Test
    fun bandRejectsLoGreaterThanHi() {
        assertFails { json.decodeFromString(BandSerializer, "[6,3]") }
    }

    @Test
    fun bandRejectsObjectForm() {
        assertFails { json.decodeFromString(BandSerializer, "{\"lo\":3,\"hi\":6}") }
    }

    @Test
    fun bandIsUsedAutomaticallyForBandTypedFields() {
        val encoded = CoreJson.assets.encodeToString(GoalPrescription.serializer(), GoalPrescription(range = Band(6, 10), rir = Band(1, 3)))
        assertTrue(encoded.contains("\"range\":[6,10]"), encoded)
        assertTrue(encoded.contains("\"rir\":[1,3]"), encoded)
        assertFalse(encoded.contains("\"lo\""), encoded)
    }

    @Test
    fun nullableBandFieldIsOmittedWhenNull() {
        val encoded = CoreJson.stored.encodeToString(GoalPrescription.serializer(), GoalPrescription(range = Band(6, 10), rir = Band(1, 3)))
        assertFalse(encoded.contains("rir_last_week"), encoded)
        assertFalse(encoded.contains("\"sets\""), encoded)
    }

    @Test
    fun nullableBandFieldDecodesWhenPresent() {
        val decoded = CoreJson.assets.decodeFromString(
            GoalPrescription.serializer(),
            "{\"range\":[6,10],\"rir\":[1,3],\"rir_last_week\":[0,2],\"sets\":[3,5]}",
        )
        assertEquals(Band(0, 2), decoded.rirLastWeek)
        assertEquals(Band(3, 5), decoded.sets)
    }
}
