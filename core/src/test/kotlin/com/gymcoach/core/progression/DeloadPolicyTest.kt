package com.gymcoach.core.progression

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.CheckinAnswer
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.E1rmEstimate
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionSummary
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.model.WeeklyCheckin
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §4.11 DLD-03: onset/resolved dates, ≥ 2 triggers AND ≥ 5 days, fires once, stale triggers ignored. */
class DeloadPolicyTest {
    private val policy = DeloadPolicy(PF.kb, PF.c)
    private val d0: LocalDate = LocalDate.of(2026, 9, 1)

    private fun rc(date: LocalDate, band: ReadinessBand = ReadinessBand.GREEN, quality: Int? = 4, soreness: Int? = 2, motivation: Int? = 4, joint: Int = 0) =
        ReadinessCheckin(
            id = "r-$date", date = date, sleepHours = 7.5, sleepQuality = quality, soreness = soreness, stress = 2, energy = 4,
            motivation = motivation, jointPain0to10 = joint, score = 80, band = band, explanation = Explanation(emptyList(), ""),
            loggedAt = date.atTime(7, 0).toInstant(ZoneOffset.UTC),
        )

    private fun inputs(readiness: List<ReadinessCheckin> = emptyList(), checkins: List<WeeklyCheckin> = emptyList(), states: List<ProgressionState> = emptyList()) =
        PlanInputs(
            profile = PF.p1, gyms = listOf(PF.home), defaultGymId = PF.home.id, overrides = emptyList(), progressionStates = states,
            recentSessions = emptyList(), recentSets = emptyList(), bodyMetrics = emptyList(), weeklyCheckins = checkins, readinessHistory = readiness,
        )

    /** Red and poor sleep quality every day from d0 for `days` days. */
    private fun badDays(days: Int) = (0 until days).map { rc(d0.plusDays(it.toLong()), band = ReadinessBand.RED, quality = 2) }

    @Test fun redDaysAndSleepQualityOnsetDates() {
        val today = d0.plusDays(9)
        val s = policy.signals(inputs(badDays(10)), emptyList(), today)
        val red = s.triggers.single { it.kind == DeloadPolicy.KIND_RED_DAYS }
        assertEquals(d0.plusDays(2), red.onset)            // the 3rd Red
        assertNull(red.resolved)
        val sleep = s.triggers.single { it.kind == DeloadPolicy.KIND_SLEEP_QUALITY }
        assertEquals(d0.plusDays(3), sleep.onset)          // the 4th poor night
        assertEquals(RuleIds.DLD_03, red.ruleId)
    }

    @Test fun twoTriggersActiveForFiveDaysFireReactive() {
        val today = d0.plusDays(9)
        val s = policy.signals(inputs(badDays(10)), emptyList(), today)
        assertEquals(2, s.active(today, PF.c.plat.dld03MinDays).size)
        val e = policy.reactive(s, today, emptySet())
        assertNotNull(e)
        assertEquals(listOf(RuleIds.DLD_03), e.ruleIds)
        assertTrue(e.message.startsWith("Deload this week? 2 of 6 triggers"), e.message)
    }

    @Test fun onsetsYoungerThanFiveDaysDoNotFire() {
        val today = d0.plusDays(7)                         // red onset d0+2 (5 days), sleep onset d0+3 (4 days)
        val s = policy.signals(inputs(badDays(8)), emptyList(), today)
        assertEquals(listOf(DeloadPolicy.KIND_RED_DAYS), s.active(today, PF.c.plat.dld03MinDays).map { it.kind })
        assertNull(policy.reactive(s, today, emptySet()))
    }

    @Test fun firesOnceThenOnlyANewOnsetTriggersAgain() {
        val today = d0.plusDays(9)
        val s = policy.signals(inputs(badDays(10)), emptyList(), today)
        val proposed = s.active(today, PF.c.plat.dld03MinDays).map { "${it.kind}@${it.onset}" }.toSet()
        assertEquals(setOf("red_days@${d0.plusDays(2)}", "sleep_quality@${d0.plusDays(3)}"), proposed)
        assertNull(policy.reactive(s, today, proposed))
        assertNotNull(policy.reactive(s, today, setOf("red_days@${d0.plusDays(2)}")))
    }

    @Test fun staleResolvedTriggersDoNotFire() {
        val bad = (0 until 4).map { rc(d0.plusDays(it.toLong()), band = ReadinessBand.RED, quality = 2) }
        val good = (4 until 20).map { rc(d0.plusDays(it.toLong())) }
        val today = d0.plusDays(19)
        val s = policy.signals(inputs(bad + good), emptyList(), today)
        assertTrue(s.triggers.all { it.resolved != null }, s.triggers.toString())
        val red = s.triggers.single { it.kind == DeloadPolicy.KIND_RED_DAYS }
        assertEquals(d0.plusDays(2), red.onset)
        assertEquals(d0.plusDays(8), red.resolved)        // first day whose trailing 7 days hold < 3 Red
        assertTrue(s.active(today, PF.c.plat.dld03MinDays).isEmpty())
        assertNull(policy.reactive(s, today, emptySet()))
    }

    @Test fun singleTriggerNeverFires() {
        val list = (0 until 10).map { rc(d0.plusDays(it.toLong()), band = ReadinessBand.RED) }
        val today = d0.plusDays(9)
        assertNull(policy.reactive(policy.signals(inputs(list), emptyList(), today), today, emptySet()))
    }

    @Test fun sorenessPersistingBeyond72HoursAndJointPainFromCheckin() {
        val list = (0 until 6).map { rc(d0.plusDays(it.toLong()), soreness = 4) } + rc(d0.plusDays(6), soreness = 2)
        val wk = WeeklyCheckin("w1", d0, listOf(CheckinAnswer("Q7", text = "left knee")))
        val s = policy.signals(inputs(list, listOf(wk)), emptyList(), d0.plusDays(6))
        val sore = s.triggers.single { it.kind == DeloadPolicy.KIND_SORENESS }
        assertEquals(d0.plusDays(3), sore.onset)
        assertEquals(d0.plusDays(6), sore.resolved)
        val joint = s.triggers.single { it.kind == DeloadPolicy.KIND_JOINT_PAIN }
        assertEquals(d0, joint.onset)
        // jointPain0to10 = 0 on the same day's readiness entry resolves it at the first later entry.
        assertEquals(d0, joint.onset)
    }

    @Test fun motivationFromQ11AndRegressingKey() {
        val wk = listOf(
            WeeklyCheckin("w1", d0, listOf(CheckinAnswer("Q11", number = 2.0))),
            WeeklyCheckin("w2", d0.plusDays(7), listOf(CheckinAnswer("Q11", number = 1.0))),
        )
        fun sum(d: LocalDate, kg: Double) = SessionSummary(d, kg, 8, 2, false, e1rm = E1rmEstimate(kg, kg, 8, 2, E1rmConfidence.HIGH, d))
        val st = PF.state("barbell_bench_press", 82.5, Band(5, 10), 2, status = ProgressionStatus.REGRESSING).copy(history = listOf(
            sum(d0.plusDays(8), 100.0), sum(d0.plusDays(4), 100.0), sum(d0, 110.0),
        ))
        val today = d0.plusDays(14)
        val s = policy.signals(inputs(checkins = wk, states = listOf(st)), listOf(st), today)
        val mot = s.triggers.single { it.kind == DeloadPolicy.KIND_MOTIVATION }
        assertEquals(d0, mot.onset)
        assertNull(mot.resolved)
        val reg = s.triggers.single { it.kind == DeloadPolicy.KIND_REGRESSING }
        assertEquals(d0.plusDays(8), reg.onset)
        assertNotNull(policy.reactive(s, today, emptySet()))
    }

    @Test fun scheduledAndReentryExplanations() {
        val week = MesoWeek(index = 7, startDate = d0, mode = WeekMode.DELOAD, rirOffset = 0, targets = emptyMap(), sessions = emptyList())
        val e = policy.scheduledExplanation(week)
        assertEquals(listOf(RuleIds.DLD_02), e.ruleIds)
        assertTrue(e.message.contains("0.85") && e.message.contains("RIR 4"), e.message)
        val r = policy.reentryExplanation(week.copy(index = 1, mode = WeekMode.REENTRY))
        assertEquals(listOf(RuleIds.DLD_04), r.ruleIds)
        assertTrue(r.message.contains("0.95"), r.message)
    }
}
