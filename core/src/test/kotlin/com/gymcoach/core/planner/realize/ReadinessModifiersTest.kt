package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadinessModifiersTest {
    private val c = PlannerConstants.DEFAULT
    private val rm = ReadinessModifiers(c)
    private val day = PlannerFixtures.MONDAY

    private fun ck(date: LocalDate, band: ReadinessBand, score: Int, sleep: Double? = 7.5, stress: Int? = 2, soreness: Int? = 2, region: Muscle? = null) =
        ReadinessCheckin("r-$date", date, sleepHours = sleep, stress = stress, soreness = soreness, sorenessRegion = region, score = score, band = band,
            explanation = Explanations.of(RuleIds.RDY_01, "fixture"), loggedAt = PlannerFixtures.NOW)

    @Test
    fun greenWithoutCheckinIsAsPlanned() {
        val e = rm.evaluate(TodayContext(day, "g"), emptyList())
        assertEquals(ReadinessBand.GREEN, e.band)
        assertEquals(0, e.rirOffset)
        assertNull(e.setDropAfter)
        assertFalse(e.alternativeOffered)
    }

    @Test
    fun amberAndRedModifiers_RDY02() {
        val amber = rm.evaluate(TodayContext(day, "g", readiness = ck(day, ReadinessBand.AMBER, 58)), emptyList())
        assertEquals(c.rdy.rdy02AmberRirOffset, amber.rirOffset)
        assertEquals(c.rdy.rdy02AmberSetDropAfter, amber.setDropAfter)
        val red = rm.evaluate(TodayContext(day, "g", readiness = ck(day, ReadinessBand.RED, 32)), emptyList())
        assertEquals(c.rdy.rdy02RedRirOffset, red.rirOffset)
        assertEquals(c.rdy.rdy02RedSetFactor, red.setFactor)
        assertTrue(red.replaceHeavyBarbell && red.alternativeOffered)
    }

    @Test
    fun shortSleepAndAlcoholFloorTheBandAtAmber_SLP03_NUT13() {
        val sleep = rm.evaluate(TodayContext(day, "g", readiness = ck(day, ReadinessBand.GREEN, 80, sleep = 5.5)), emptyList())
        assertEquals(ReadinessBand.AMBER, sleep.band)
        val drinks = rm.evaluate(TodayContext(day, "g", readiness = ck(day, ReadinessBand.GREEN, 80), alcoholDrinksLastNight = 3), emptyList())
        assertEquals(ReadinessBand.AMBER, drinks.band)
        assertTrue(drinks.explanations.any { RuleIds.NUT_13 in it.ruleIds })
    }

    @Test
    fun twoShortNightsBlockMaxStrengthAndALowWeeklyAverageTrimsHeavySets() {
        val history = (1..6).map { ck(day.minusDays(it.toLong()), ReadinessBand.GREEN, 75, sleep = 5.8) }
        val e = rm.evaluate(TodayContext(day, "g", readiness = ck(day, ReadinessBand.AMBER, 60, sleep = 5.9)), history)
        assertTrue(e.noMaxStrength)
        assertEquals(c.rdy.slp03SetFactor, e.sleepHeavySetFactor)
        assertEquals(c.rdy.slp03RirOffset, e.sleepHeavyRirOffset)
    }

    @Test
    fun stressOnTheLastTwoCheckinsAndRegionalSoreness_RDY05_RDY04() {
        val history = listOf(ck(day.minusDays(1), ReadinessBand.GREEN, 72, stress = 4))
        val e = rm.evaluate(TodayContext(day, "g", readiness = ck(day, ReadinessBand.GREEN, 71, stress = 5, soreness = 4, region = Muscle.QUADS)), history)
        assertEquals(c.rdy.rdy05SetFactor, e.stressSetFactor)
        assertEquals(c.rdy.rdy05RirOffset, e.stressMainLiftRirOffset)
        assertEquals(setOf(Muscle.QUADS), e.soreMuscles)
    }
}
