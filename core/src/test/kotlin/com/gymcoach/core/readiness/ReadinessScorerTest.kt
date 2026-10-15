package com.gymcoach.core.readiness

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.planner.PlannerConstants
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §5.1 / G2: RDY-01 points 58 / 50 / 32 / 56 / 100 / 0, RDY-02 band edges 44/45/69/70, overrides. */
class ReadinessScorerTest {
    private val scorer = ReadinessScorer(PlannerConstants.DEFAULT)
    private val day = LocalDate.of(2026, 9, 7)
    private val now = Instant.parse("2026-09-07T06:00:00Z")

    private fun full(sleep: Double?, q: Int?, sore: Int?, stress: Int?, energy: Int?, mot: Int?, rhr: Int? = null, base: Double? = null,
                     alcohol: Int = 0, elevated: Int = 0, illness: Boolean = false, redFlag: Boolean = false) =
        ReadinessInput(day, false, sleep, q, sore, null, stress, energy, mot, rhr, base,
            alcoholDrinksLastNight = alcohol, restingHrElevatedDays = elevated, illness = illness, redFlagReported = redFlag)

    @Test fun g2FullForm58Amber() {
        val r = scorer.score(full(6.5, 3, 2, 3, 3, 4), "r1", now)
        assertEquals(58, r.score)
        assertEquals(ReadinessBand.AMBER, r.band)
        assertTrue(r.assumedFields.isEmpty())
        assertEquals("10/20", r.explanation.inputs["sleep_hours"])
        assertTrue(RuleIds.RDY_01 in r.explanation.ruleIds && RuleIds.RDY_02 in r.explanation.ruleIds)
    }

    @Test fun g2RestingHrPlusSixGives50() {
        val r = scorer.score(full(6.5, 3, 2, 3, 3, 4, rhr = 64, base = 58.0), "r2", now)
        assertEquals(50, r.score)
        assertEquals(ReadinessBand.AMBER, r.band)
    }

    @Test fun g2Red32() {
        val r = scorer.score(full(5.5, 2, 3, 4, 2, 3), "r3", now)
        assertEquals(32, r.score)
        assertEquals(ReadinessBand.RED, r.band)
    }

    @Test fun g2QuickMode56WithAssumedFields() {
        val r = scorer.score(ReadinessInput(day, true, 6.5, null, 2, null, null, 3, 3, null, null), "r4", now)
        assertEquals(56, r.score)
        assertEquals(ReadinessBand.AMBER, r.band)
        assertEquals(listOf("sleep_quality", "stress"), r.assumedFields)
        // Feel fills motivation when the caller only set energy.
        assertEquals(56, scorer.score(ReadinessInput(day, true, 6.5, null, 2, null, null, 3, null, null, null), "r5", now).score)
        assertEquals(6.5, ReadinessScorer.QUICK_SLEEP_MIDPOINTS[2])
    }

    @Test fun g2BestIs100WorstIs0() {
        assertEquals(100, scorer.score(full(8.0, 5, 1, 1, 5, 5), "b", now).score)
        assertEquals(ReadinessBand.GREEN, scorer.score(full(8.0, 5, 1, 1, 5, 5), "b", now).band)
        assertEquals(0, scorer.score(full(4.0, 1, 5, 5, 1, 1), "w", now).score)
        assertEquals(0, scorer.score(full(4.0, 1, 5, 5, 1, 1, rhr = 80, base = 58.0), "w2", now).score)
    }

    @Test fun rdy02BandEdges() {
        assertEquals(ReadinessBand.RED, scorer.bandOf(44))
        assertEquals(ReadinessBand.AMBER, scorer.bandOf(45))
        assertEquals(ReadinessBand.AMBER, scorer.bandOf(69))
        assertEquals(ReadinessBand.GREEN, scorer.bandOf(70))
        // The same edges reached through the RDY-01 points (renormalised where an item is not answered).
        val s44 = scorer.score(full(5.5, 3, 3, 3, 3, 3), "e44", now)                 // 4 + 5 × 8 = 44 / 100
        assertEquals(44, s44.score); assertEquals(ReadinessBand.RED, s44.band)
        val s45 = scorer.score(full(null, 3, 3, 3, 3, 2), "e45", now)                // 36 / 80
        assertEquals(45, s45.score); assertEquals(ReadinessBand.AMBER, s45.band)
        val s69 = scorer.score(full(null, 4, 2, 2, 3, null), "e69", now)             // 44 / 64 = 68.75
        assertEquals(69, s69.score); assertEquals(ReadinessBand.AMBER, s69.band)
        val s70 = scorer.score(full(null, 4, 2, 2, 4, 3), "e70", now)                // 56 / 80
        assertEquals(70, s70.score); assertEquals(ReadinessBand.GREEN, s70.band)
    }

    @Test fun sleepPointTable() {
        assertEquals(20, scorer.sleepPoints(7.5))
        assertEquals(16, scorer.sleepPoints(7.25))
        assertEquals(16, scorer.sleepPoints(7.0))
        assertEquals(10, scorer.sleepPoints(6.0))
        assertEquals(4, scorer.sleepPoints(5.9))
        assertEquals(0, scorer.sleepPoints(4.9))
    }

    @Test fun renormalisationOverAnsweredItems() {
        val r = scorer.score(full(null, null, 1, null, null, null), "n", now)
        assertEquals(100, r.score)
        assertEquals(listOf("sleep_hours", "sleep_quality", "stress", "energy", "motivation"), r.assumedFields)
    }

    @Test fun overridesSleepRhrAlcoholIllnessRedFlag() {
        val green = full(8.0, 5, 1, 1, 5, 5)
        assertEquals(ReadinessBand.AMBER, scorer.score(green.copy(sleepHours = 5.9), "o1", now).band)
        assertTrue(RuleIds.SLP_03 in scorer.score(green.copy(sleepHours = 5.9), "o1", now).explanation.ruleIds)
        val rhr = scorer.score(green.copy(restingHrElevatedDays = 2), "o2", now)
        assertEquals(ReadinessBand.AMBER, rhr.band)
        assertTrue(RuleIds.RDY_03 in rhr.explanation.ruleIds)
        assertEquals(ReadinessBand.RED, scorer.score(green.copy(restingHr = 68, restingHrBaseline7d = 58.0), "o3", now).band)
        val alc = scorer.score(green.copy(alcoholDrinksLastNight = 3), "o4", now)
        assertEquals(ReadinessBand.AMBER, alc.band)
        assertTrue(RuleIds.NUT_13 in alc.explanation.ruleIds)
        assertEquals(ReadinessBand.RED, scorer.score(green.copy(illness = true), "o5", now).band)
        val flag = scorer.score(green.copy(redFlagReported = true), "o6", now)
        assertEquals(ReadinessBand.RED, flag.band)
        assertTrue(RuleIds.RED_01 in flag.explanation.ruleIds)
        assertTrue(RuleIds.INJ_00 in scorer.score(green.copy(jointPain0to10 = 5), "o7", now).explanation.ruleIds)
    }
}
