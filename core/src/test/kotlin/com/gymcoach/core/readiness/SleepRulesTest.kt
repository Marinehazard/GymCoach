package com.gymcoach.core.readiness

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.planner.PlannerConstants
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SLP-03 arithmetic: < 6 h → at least Amber, two nights → no max strength, 7-day mean < 7 h → sets × 0.9, RIR +1. */
class SleepRulesTest {
    private val rules = SleepRules(PlannerConstants.DEFAULT)
    private val today = LocalDate.of(2026, 9, 14)

    private fun rc(daysAgo: Int, h: Double?) = ReadinessCheckin(
        id = "s$daysAgo", date = today.minusDays(daysAgo.toLong()), sleepHours = h, score = 60, band = ReadinessBand.AMBER,
        explanation = Explanation(emptyList(), ""), loggedAt = today.minusDays(daysAgo.toLong()).atTime(7, 0).toInstant(ZoneOffset.UTC),
    )

    @Test fun goodWeekNoModifiers() {
        val m = rules.evaluate(rc(0, 8.0), (1..6).map { rc(it, 7.5) })
        assertFalse(m.atLeastAmber)
        assertFalse(m.noMaxStrength)
        assertEquals(1.0, m.setFactor)
        assertEquals(0, m.heavyRirOffset)
        assertTrue(m.explanations.isEmpty())
    }

    @Test fun weeklyAverageBelowSevenCutsSetsAndAddsRir() {
        val m = rules.evaluate(rc(0, 7.0), (1..6).map { rc(it, 6.5) })   // mean 6.57
        assertEquals(0.9, m.setFactor)
        assertEquals(1, m.heavyRirOffset)
        assertFalse(m.atLeastAmber)
        assertTrue(m.explanations.all { RuleIds.SLP_03 in it.ruleIds })
    }

    @Test fun shortNightAndTwoShortNights() {
        val one = rules.evaluate(rc(0, 5.5), (1..6).map { rc(it, 8.0) })
        assertTrue(one.atLeastAmber)
        assertFalse(one.noMaxStrength)
        val two = rules.evaluate(rc(0, 5.5), listOf(rc(1, 5.0)) + (2..6).map { rc(it, 8.0) })
        assertTrue(two.atLeastAmber)
        assertTrue(two.noMaxStrength)
    }

    @Test fun entriesOlderThanSevenDaysIgnored() {
        val m = rules.evaluate(rc(0, 8.0), listOf(rc(9, 3.0), rc(10, 3.0)))
        assertEquals(1.0, m.setFactor)
    }
}
