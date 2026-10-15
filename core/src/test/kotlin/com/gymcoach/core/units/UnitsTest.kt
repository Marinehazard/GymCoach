package com.gymcoach.core.units

import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §10.2: kg↔lb at known points (135 lb → 61.235 kg; entered value kept), display formatting (HR-20). */
class UnitsTest {

    private fun assertClose(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue(abs(expected - actual) < eps, "expected $expected but was $actual")

    // --- constants ---
    @Test fun constants() {
        assertEquals(0.45359237, Units.KG_PER_LB)
        assertEquals(2.54, Units.CM_PER_IN)
    }

    // --- conversions ---
    @Test fun lbToKgKnownPoint135() = assertClose(61.23496995, Units.lbToKg(135.0))
    @Test fun lbToKgRoundedIs61_235() = assertEquals("61.23", Units.trim(Units.lbToKg(135.0)))
    @Test fun kgToLbKnownPoint80() = assertClose(176.36980975, Units.kgToLb(80.0), 1e-6)
    @Test fun kgLbRoundTrip() {
        for (kg in listOf(0.0, 2.5, 61.235, 100.0, 250.0)) assertClose(kg, Units.lbToKg(Units.kgToLb(kg)))
    }
    @Test fun inToCmKnownPoint() = assertClose(177.8, Units.inToCm(70.0))
    @Test fun cmToInKnownPoint() = assertClose(70.0, Units.cmToIn(177.8))
    @Test fun cmInRoundTrip() {
        for (cm in listOf(150.0, 177.8, 200.5)) assertClose(cm, Units.inToCm(Units.cmToIn(cm)))
    }

    // --- entered values (sacred) ---
    @Test fun enteredWeightKgKeepsValueExactly() {
        val w = Units.enteredWeight(82.5, WeightUnit.KG)
        assertEquals(EnteredWeight(82.5, WeightUnit.KG, 82.5), w)
    }

    @Test fun enteredWeightLbKeepsTypedValueAndConvertsExactly() {
        val w = Units.enteredWeight(135.0, WeightUnit.LB)
        assertEquals(135.0, w.entered)
        assertEquals(WeightUnit.LB, w.unit)
        assertClose(61.23496995, w.kg)
    }

    @Test fun enteredWeightConversionIsNotRounded() {
        val w = Units.enteredWeight(1.0, WeightUnit.LB)
        assertEquals(0.45359237, w.kg)
    }

    @Test fun enteredLengthCmKeepsValue() {
        assertEquals(180.0, Units.enteredLength(180.0, LengthUnit.CM).cm)
        assertEquals(180.0, Units.enteredLength(180.0, LengthUnit.CM).entered)
    }

    @Test fun enteredLengthInConverts() {
        val l = Units.enteredLength(70.0, LengthUnit.IN)
        assertEquals(70.0, l.entered)
        assertEquals(LengthUnit.IN, l.unit)
        assertClose(177.8, l.cm)
    }

    // --- unit selection ---
    @Test fun unitsForSystem() {
        assertEquals(WeightUnit.KG, Units.weightUnitFor(UnitSystem.METRIC))
        assertEquals(WeightUnit.LB, Units.weightUnitFor(UnitSystem.IMPERIAL))
        assertEquals(LengthUnit.CM, Units.lengthUnitFor(UnitSystem.METRIC))
        assertEquals(LengthUnit.IN, Units.lengthUnitFor(UnitSystem.IMPERIAL))
    }

    @Test fun unitLabels() {
        assertEquals("kg", Units.unitLabel(WeightUnit.KG))
        assertEquals("lb", Units.unitLabel(WeightUnit.LB))
    }

    // --- trim ---
    @Test fun trimDropsTrailingZeros() {
        assertEquals("80", Units.trim(80.0))
        assertEquals("82.5", Units.trim(82.5))
        assertEquals("0", Units.trim(0.0))
        assertEquals("100", Units.trim(100.0))
        assertEquals("10", Units.trim(10.0))
    }

    @Test fun trimKeepsTwoDecimalsMax() {
        assertEquals("61.23", Units.trim(61.23496995))
        assertEquals("1.25", Units.trim(1.25))
        assertEquals("0.5", Units.trim(0.5))
        assertEquals("176.37", Units.trim(176.36980975))
    }

    @Test fun trimUsesDotDecimalSeparatorRegardlessOfLocale() {
        assertEquals("2.5", Units.trim(2.5))
        assertTrue(!Units.trim(2.5).contains(","))
    }

    @Test fun trimNegative() = assertEquals("-2.5", Units.trim(-2.5))

    // --- formatting (HR-20) ---
    @Test fun formatWeightSameUnitShowsEnteredVerbatim() {
        assertEquals("80 kg", Units.formatWeight(EnteredWeight(80.0, WeightUnit.KG, 80.0), WeightUnit.KG))
        assertEquals("82.5 kg", Units.formatWeight(EnteredWeight(82.5, WeightUnit.KG, 82.5), WeightUnit.KG))
        assertEquals("135 lb", Units.formatWeight(Units.enteredWeight(135.0, WeightUnit.LB), WeightUnit.LB))
    }

    @Test fun formatWeightSameUnitIgnoresTheKgFieldEntirely() {
        // The typed value wins even if the derived kg were inconsistent: sacred display.
        assertEquals("135 lb", Units.formatWeight(EnteredWeight(135.0, WeightUnit.LB, 999.0), WeightUnit.LB))
    }

    @Test fun formatWeightDifferentUnitIsPrefixedWithApprox() {
        assertEquals("≈ 176.37 lb", Units.formatWeight(EnteredWeight(80.0, WeightUnit.KG, 80.0), WeightUnit.LB))
        assertEquals("≈ 61.23 kg", Units.formatWeight(Units.enteredWeight(135.0, WeightUnit.LB), WeightUnit.KG))
    }

    @Test fun formatKg() {
        assertEquals("82.5 kg", Units.formatKg(82.5, WeightUnit.KG))
        assertEquals("176.37 lb", Units.formatKg(80.0, WeightUnit.LB))
        assertEquals("0 kg", Units.formatKg(0.0, WeightUnit.KG))
    }
}
