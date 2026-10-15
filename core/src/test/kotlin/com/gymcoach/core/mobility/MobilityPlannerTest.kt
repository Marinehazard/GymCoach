package com.gymcoach.core.mobility

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.DrillKind
import com.gymcoach.core.model.Joint
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.PlannerFixtures
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobilityPlannerTest {
    private val kb = PlannerFixtures.kb
    private val mp = MobilityPlanner(kb, PlannerConstants.DEFAULT)
    private val hips = PlannerFixtures.lim(LimitationRegion.HIP, 2, limitedRange = true)

    @Test
    fun chronicDoseAsCooldownHoldsAndRestDayCard_MOB02_MOB04() {
        assertEquals(listOf(Joint.HIPS), mp.chronicJoints(listOf(hips, PlannerFixtures.lim(LimitationRegion.KNEE, 2))))
        val cool = mp.cooldown(listOf(hips), listOf(MovementPattern.SQUAT), afterHiit = false)
        assertEquals(2, cool.size)
        assertTrue(cool.all { d -> kb.drills.first { it.id == d.drillId }.kind == DrillKind.STATIC })
        assertTrue(cool.all { it.seconds == PlannerFixtures.band(30, 60) && it.sets == 2 })
        assertTrue(cool.all { RuleIds.MOB_04 in it.explanation.ruleIds })
        assertEquals(2, mp.restDayCard(listOf(hips)).size)
        assertTrue(mp.cooldown(emptyList(), listOf(MovementPattern.SQUAT), false).isEmpty())
    }

    @Test
    fun easyCooldownMovementAfterHiit_MOB06() {
        val after = mp.cooldown(emptyList(), listOf(MovementPattern.SQUAT), afterHiit = true)
        assertEquals(1, after.size)
        assertTrue(RuleIds.MOB_06 in after.single().explanation.ruleIds)
    }

    @Test
    fun weeklyMinutesIncludeTheMob03Credit() {
        val at = Instant.parse("2026-09-08T18:00:00Z")
        val sets = (1..4).map { SetLog("g$it", "s", null, "goblet_squat", it, SetKind.WORKING, reps = 10, loggedAt = at) } +
            listOf(SetLog("h1", "s", null, "hip_90_90", 1, SetKind.WORKING, seconds = 120, loggedAt = at), SetLog("r", "s", null, "goblet_squat", 9, SetKind.RAMP, reps = 8, loggedAt = at))
        val m = mp.weeklyMinutes(listOf(hips), sets, PlannerFixtures.MONDAY)
        assertEquals(mapOf(Joint.HIPS to 4), m)   // 4 × 30 s MOB-03 + 120 s drill = 240 s
        assertTrue(RuleIds.MOB_03 in RuleIds.ALL)
    }
}
