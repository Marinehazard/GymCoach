package com.gymcoach.core.planner

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeekMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MesocycleBuilderTest {
    private val kb = PlannerFixtures.kb
    private val c = PlannerConstants.DEFAULT
    private val b = MesocycleBuilder(c)
    private val planner = Planner(kb, c)
    private val day = PlannerFixtures.MONDAY

    @Test
    fun accumulationWeeksByTrainingAge_DLD01() {
        assertEquals(12 to null, b.accumulationWeeks(TrainingAge.NOVICE, firstMesocycle = true))
        assertEquals(6 to 7, b.accumulationWeeks(TrainingAge.NOVICE, firstMesocycle = false))
        assertEquals(6 to 7, b.accumulationWeeks(TrainingAge.INTERMEDIATE, firstMesocycle = true))
        assertEquals(5 to 6, b.accumulationWeeks(TrainingAge.ADVANCED, firstMesocycle = false))
    }

    @Test
    fun weekModesAndRirOffsets_RIR04() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1())
        val s = b.build(inputs, planner.normalize(inputs, day), day)
        assertEquals(7, s.weeks.size)
        assertEquals(listOf(1, 0, 0, 0, 0, -1, 0), s.weeks.map { it.rirOffset })
        assertEquals(WeekMode.DELOAD, s.weeks.last().mode)
        assertEquals(1, s.currentWeekIndex)
        assertEquals(day.plusWeeks(3), s.weeks[3].startDate)
    }

    @Test
    fun noviceFirstMesocycleHasNoScheduledDeloadButTheSecondOneDoes() {
        val first = PlannerFixtures.inputs(PlannerFixtures.p2())
        val s1 = b.build(first, planner.normalize(first, day), day)
        assertEquals(12, s1.weeks.size)
        assertNull(s1.deloadWeekIndex)
        assertTrue(s1.weeks.none { it.mode == WeekMode.DELOAD })
        val later = PlannerFixtures.inputs(PlannerFixtures.p2(), mesocyclesCompleted = 1)
        assertEquals(7, b.build(later, planner.normalize(later, day), day).deloadWeekIndex)
    }

    @Test
    fun currentWeekIndexFollowsTheAnchorAndPausesForTravel() {
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1(), anchor = day).copy(travelWeeks = listOf(day.plusWeeks(1)))
        val today = day.plusWeeks(3)
        val s = b.build(inputs, planner.normalize(inputs, today), today)
        assertEquals(WeekMode.TRAVEL_MAINTENANCE, s.weeks[1].mode)
        assertEquals(2, s.weeks[1].index)
        assertEquals(2, s.weeks[2].index)
        assertEquals(3, s.currentWeekIndex)                     // 3 weeks after the anchor − 1 travel week + 1
        assertEquals(8, s.weeks.size)
    }

    @Test
    fun reentryAfterADeloadAndAStaleAnchorStartsANewMesocycle_DLD04() {
        val anchor = day
        val reentry = PlannerFixtures.inputs(PlannerFixtures.p1(), anchor = anchor).copy(lastDeloadWeekStart = anchor.minusDays(7))
        val s = b.build(reentry, planner.normalize(reentry, day), day)
        assertEquals(WeekMode.REENTRY, s.weeks.first().mode)
        assertEquals(1, s.weeks.first().rirOffset)
        assertTrue(s.explanations.any { RuleIds.DLD_04 in it.ruleIds })
        val stale = PlannerFixtures.inputs(PlannerFixtures.p1(), anchor = day.minusWeeks(10))
        val st = b.build(stale, planner.normalize(stale, day), day)
        assertEquals(day, st.anchor)
        assertEquals(1, st.currentWeekIndex)
    }

    @Test
    fun anAcceptedReactiveDeloadEndsTheMesocycle_DLD03() {
        val ov = PlanOverride("d", Instant.parse("2026-09-23T08:00:00Z"), OverrideSource.USER, null, PlanMutation.ScheduleDeload(true, "x"))
        val inputs = PlannerFixtures.inputs(PlannerFixtures.p1(), anchor = day).copy(overrides = listOf(ov))
        val today = day.plusWeeks(2).plusDays(2)
        val s = b.build(inputs, planner.normalize(inputs, today), today)
        assertEquals(3, s.deloadWeekIndex)
        assertEquals(3, s.weeks.size)
        assertEquals(WeekMode.DELOAD, s.weeks.last().mode)
    }
}
