package com.gymcoach.core.progression

import com.gymcoach.core.model.E1rmConfidence
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §4.10 / §10.2: E1RM-01..03 rows (106.7, 125.4, 121.7), LOW above 10 reps, interpolation at every table row. */
class E1rmTest {

    private val date = LocalDate.of(2026, 9, 5)

    private fun assertClose(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue(abs(expected - actual) < eps, "expected $expected but was $actual")

    // --- E1RM-01 formulas ---
    @Test fun epleyFormula() {
        assertClose(110.0, E1rm.epley(100.0, 3))
        assertClose(100.0, E1rm.epley(100.0, 0))
    }

    @Test fun brzyckiFormula() {
        assertClose(100.0, E1rm.brzycki(100.0, 1))
        assertClose(100.0 * 36.0 / 27.0, E1rm.brzycki(100.0, 10))
    }

    @Test fun epleyAndBrzyckiCoincideAtTenReps() {
        assertClose(E1rm.epley(80.0, 10), E1rm.brzycki(80.0, 10))
        assertClose(106.6666666667, E1rm.epley(80.0, 10), 1e-6)
    }

    // --- §4.10 worked rows (E1RM-01..03) ---
    @Test fun row80x10at0Is106_7High() {
        val e = assertNotNull(E1rm.estimate(80.0, 10, 0, date))
        assertClose(106.7, e.kg)
        assertEquals(E1rmConfidence.HIGH, e.confidence)
        assertEquals(80.0, e.fromWeightKg)
        assertEquals(10, e.fromReps)
        assertEquals(0, e.fromRir)
        assertEquals(date, e.date)
        assertEquals("E1RM-01", e.formulaId)
    }

    @Test fun row80x8at3CapsREffAtTenAndIs106_7High() {
        // r_eff = min(8 + 3, 10) = 10 → same estimate as 10 reps @ 0 (E1RM-03 cap).
        val e = assertNotNull(E1rm.estimate(80.0, 8, 3, date))
        assertClose(106.7, e.kg)
        assertEquals(E1rmConfidence.HIGH, e.confidence)
    }

    @Test fun row100x6at2Is125_4() {
        // r_eff 8 → Epley 126.67, Brzycki 124.14 → mean 125.4
        val e = assertNotNull(E1rm.estimate(100.0, 6, 2, date))
        assertClose(125.4, e.kg)
        assertEquals(E1rmConfidence.HIGH, e.confidence)
    }

    @Test fun row100x5at2Is121_7() {
        // r_eff 7 → Epley 123.33, Brzycki 120.00 → mean 121.7
        val e = assertNotNull(E1rm.estimate(100.0, 5, 2, date))
        assertClose(121.7, e.kg)
        assertEquals(E1rmConfidence.HIGH, e.confidence)
    }

    @Test fun row60x12at1IsLowConfidence() {
        // reps > 10 → LOW (E1RM-02); r_eff capped at 10 → 80.0
        val e = assertNotNull(E1rm.estimate(60.0, 12, 1, date))
        assertEquals(E1rmConfidence.LOW, e.confidence)
        assertClose(80.0, e.kg)
    }

    // --- E1RM-02 confidence boundary ---
    @Test fun tenRepsIsHighElevenIsLow() {
        assertEquals(E1rmConfidence.HIGH, assertNotNull(E1rm.estimate(60.0, 10, 0, date)).confidence)
        assertEquals(E1rmConfidence.LOW, assertNotNull(E1rm.estimate(60.0, 11, 0, date)).confidence)
        assertEquals(E1rmConfidence.HIGH, assertNotNull(E1rm.estimate(60.0, 1, 0, date)).confidence)
    }

    @Test fun maxRepsParameterMovesTheBoundary() {
        assertEquals(E1rmConfidence.HIGH, assertNotNull(E1rm.estimate(60.0, 11, 0, date, maxReps = 12)).confidence)
        assertEquals(E1rmConfidence.LOW, assertNotNull(E1rm.estimate(60.0, 7, 0, date, maxReps = 6)).confidence)
    }

    @Test fun rirIsAddedToRepsBeforeTheCap() {
        val a = assertNotNull(E1rm.estimate(100.0, 5, 0, date))
        val b = assertNotNull(E1rm.estimate(100.0, 5, 3, date))
        assertTrue(b.kg > a.kg)
        assertEquals(3, b.fromRir)
        // 5 + 3 = 8 → identical to 8 reps @ 0
        assertClose(assertNotNull(E1rm.estimate(100.0, 8, 0, date)).kg, b.kg)
    }

    @Test fun nullRirCountsAsZero() {
        val a = assertNotNull(E1rm.estimate(80.0, 10, null, date))
        val b = assertNotNull(E1rm.estimate(80.0, 10, 0, date))
        assertClose(b.kg, a.kg)
        assertNull(a.fromRir)
    }

    @Test fun invalidInputsReturnNull() {
        assertNull(E1rm.estimate(80.0, 0, 0, date))
        assertNull(E1rm.estimate(80.0, -1, 0, date))
        assertNull(E1rm.estimate(0.0, 5, 0, date))
        assertNull(E1rm.estimate(-20.0, 5, 0, date))
    }

    @Test fun estimateIsRoundedToOneDecimal() {
        for (w in listOf(52.5, 67.5, 82.5, 97.5)) for (r in 1..10) {
            val kg = assertNotNull(E1rm.estimate(w, r, 1, date)).kg
            assertClose(Math.round(kg * 10.0) / 10.0, kg, 1e-9)
        }
    }

    // --- E1RM-03 percentage table ---
    @Test fun pctTableRows() {
        val rows = mapOf(1 to 1.00, 2 to 0.94, 3 to 0.91, 4 to 0.88, 5 to 0.86, 6 to 0.83, 8 to 0.79, 10 to 0.75, 12 to 0.71, 15 to 0.67)
        for ((reps, pct) in rows) assertClose(pct, E1rm.pctOfMaxForReps(reps), 1e-9)
    }

    @Test fun pctInterpolatesLinearlyBetweenRows() {
        assertClose(0.81, E1rm.pctOfMaxForReps(7))            // between 6:83 and 8:79
        assertClose(0.77, E1rm.pctOfMaxForReps(9))            // between 8:79 and 10:75
        assertClose(0.73, E1rm.pctOfMaxForReps(11))           // between 10:75 and 12:71
        assertClose(0.71 - 4.0 / 3.0 / 100.0, E1rm.pctOfMaxForReps(13), 1e-9)   // 12:71 → 15:67, one third
        assertClose(0.71 - 8.0 / 3.0 / 100.0, E1rm.pctOfMaxForReps(14), 1e-9)
    }

    @Test fun pctClampsOutsideTheTable() {
        assertClose(1.0, E1rm.pctOfMaxForReps(0))
        assertClose(1.0, E1rm.pctOfMaxForReps(-3))
        assertClose(0.67, E1rm.pctOfMaxForReps(15))
        assertClose(0.67, E1rm.pctOfMaxForReps(20))
        assertClose(0.67, E1rm.pctOfMaxForReps(100))
    }

    @Test fun pctIsMonotonicallyNonIncreasing() {
        var last = 2.0
        for (reps in 0..30) {
            val p = E1rm.pctOfMaxForReps(reps)
            assertTrue(p <= last + 1e-12, "pct rose at $reps reps")
            assertTrue(p in 0.67..1.0)
            last = p
        }
    }

    @Test fun loadForUsesRepsPlusRir() {
        assertClose(75.0, E1rm.loadFor(100.0, 8, 2))          // pct(10) = 0.75
        assertClose(86.0, E1rm.loadFor(100.0, 5, 0))          // pct(5) = 0.86
        assertClose(106.7 * 0.83, E1rm.loadFor(106.7, 4, 2))  // pct(6) = 0.83
    }

    @Test fun loadForIsLowerForMoreRepsAtTheSameE1rm() {
        assertTrue(E1rm.loadFor(100.0, 10, 2) < E1rm.loadFor(100.0, 5, 2))
    }
}
