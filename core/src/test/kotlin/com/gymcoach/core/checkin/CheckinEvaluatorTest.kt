package com.gymcoach.core.checkin

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeFixture
import com.gymcoach.core.model.CheckinAnswer
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.planner.PlannerConstants
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §5.2 trigger table — one test per CHK-01 question (Q1..Q15), plus the quiet case. */
class CheckinEvaluatorTest {
    private val ev = CheckinEvaluator(KnowledgeFixture.kb, PlannerConstants.DEFAULT)
    private val week = LocalDate.of(2026, 9, 14)

    private fun wc(vararg a: CheckinAnswer, start: LocalDate = week) = WeeklyCheckin("w-$start", start, a.toList())
    private fun n(id: String, v: Double) = CheckinAnswer(id, number = v)
    private fun yes(id: String, text: String? = null) = CheckinAnswer(id, yes = true, text = text)
    private fun prev(vararg a: CheckinAnswer) = listOf(wc(*a, start = week.minusDays(7)))
    private fun rules(o: CheckinOutcome) = o.triggered.flatMap { it.ruleIds }.toSet()

    @Test fun quietCheckinTriggersNothing() {
        val o = ev.evaluate(wc(n("Q1", 7.5), n("Q2", 4.0), n("Q3", 6.0), n("Q5", 9000.0), n("Q6", 2.0), CheckinAnswer("Q7", text = "no"),
            n("Q8", 100.0), n("Q9", 2.0), n("Q10", 0.0), n("Q11", 4.0), CheckinAnswer("Q12", yes = false), CheckinAnswer("Q13", yes = false),
            n("Q14", 4.0), n("Q15", 3.0)), emptyList(), inDeficit = false)
        assertTrue(o.triggered.isEmpty(), o.triggered.toString())
        assertTrue(o.proposals.isEmpty() && o.pendingModifiers.isEmpty() && o.holds.isEmpty())
    }

    @Test fun q1AndQ2TriggerSlp03() {
        val o = ev.evaluate(wc(n("Q1", 6.2)), emptyList(), false)
        assertEquals(setOf(RuleIds.SLP_03), rules(o))
        assertEquals(1, o.pendingModifiers.size)
        assertTrue(o.triggered[0].message.contains("Craven 2022"))
        assertEquals(setOf(RuleIds.SLP_03), rules(ev.evaluate(wc(n("Q2", 2.0)), emptyList(), false)))
    }

    @Test fun q3TriggersNut01() = assertEquals(setOf(RuleIds.NUT_01), rules(ev.evaluate(wc(n("Q3", 4.0)), emptyList(), false)))

    @Test fun q4InDeficitWithoutLossTriggersNut16() {
        assertEquals(setOf(RuleIds.NUT_16), rules(ev.evaluate(wc(n("Q4", 0.3)), emptyList(), true)))
        assertTrue(ev.evaluate(wc(n("Q4", -0.8)), emptyList(), true).triggered.isEmpty())
    }

    @Test fun q5BelowStepTargetTriggersStp02() {
        val r = rules(ev.evaluate(wc(n("Q5", 5200.0)), emptyList(), false))
        assertEquals(setOf(RuleIds.STP_02, RuleIds.STP_01), r)
    }

    @Test fun q6TwiceTriggersRdy05AndHoldsEveryRamp() {
        assertTrue(ev.evaluate(wc(n("Q6", 4.0)), emptyList(), false).triggered.isEmpty())
        val o = ev.evaluate(wc(n("Q6", 4.0)), prev(n("Q6", 5.0)), false)
        assertTrue(RuleIds.RDY_05 in rules(o) && RuleIds.STR_02 in rules(o))
        assertEquals(Muscle.values().toSet(), o.holds)
        assertEquals(1, o.pendingModifiers.size)
    }

    @Test fun q7LimitationCardAndRed02OnTheSameRegionTwice() {
        val o = ev.evaluate(wc(CheckinAnswer("Q7", text = "right knee on squats")), emptyList(), false)
        assertEquals(setOf(RuleIds.INJ_00), rules(o))
        val p = o.proposals.single() as PlanMutation.SetLimitation
        assertEquals(LimitationRegion.KNEE, p.limitation.region)
        assertTrue(p.limitation.pain0to10 > 3)
        val twice = ev.evaluate(wc(CheckinAnswer("Q7", text = "knee")), prev(CheckinAnswer("Q7", text = "left knee")), false)
        assertTrue(RuleIds.RED_02 in rules(twice))
        val other = ev.evaluate(wc(CheckinAnswer("Q7", text = "knee")), prev(CheckinAnswer("Q7", text = "shoulder")), false)
        assertTrue(RuleIds.RED_02 !in rules(other))
    }

    @Test fun q8Below70PercentTriggersTime01() {
        val o = ev.evaluate(wc(n("Q8", 50.0)), emptyList(), false)
        assertEquals(setOf(RuleIds.TIME_01, RuleIds.SPLIT_01), rules(o))
    }

    @Test fun q9AlcoholTriggersNut13() = assertEquals(setOf(RuleIds.NUT_13), rules(ev.evaluate(wc(n("Q9", 9.0)), emptyList(), false)))

    @Test fun q10TravelDaysProposeTravelWeek() {
        val o = ev.evaluate(wc(n("Q10", 5.0)), emptyList(), false)
        assertEquals(setOf(RuleIds.TRV_01), rules(o))
        val m = o.proposals.single() as PlanMutation.SetTravelDays
        assertEquals(week.plusDays(7), m.from)
        assertEquals(week.plusDays(11), m.to)
        assertTrue(ev.evaluate(wc(n("Q10", 2.0)), emptyList(), false).proposals.isEmpty())
    }

    @Test fun q11LowMotivationIsADeloadTrigger() = assertEquals(setOf(RuleIds.DLD_03), rules(ev.evaluate(wc(n("Q11", 2.0)), emptyList(), false)))

    @Test fun q12YesTwicePausesTheRampForTheNamedMuscles() {
        assertTrue(ev.evaluate(wc(yes("Q12", "quads")), emptyList(), false).holds.isEmpty())
        val o = ev.evaluate(wc(yes("Q12", "quads and glutes")), prev(yes("Q12")), false)
        assertEquals(setOf(RuleIds.VOL_08), rules(o))
        assertEquals(setOf(Muscle.QUADS, Muscle.GLUTES), o.holds)
        assertEquals(Muscle.values().toSet(), ev.evaluate(wc(yes("Q12")), prev(yes("Q12")), false).holds)
    }

    @Test fun q13CaffeineTriggersSup02() = assertEquals(setOf(RuleIds.SUP_02), rules(ev.evaluate(wc(yes("Q13")), emptyList(), false)))

    @Test fun q14RirAuditTriggersProg04() {
        val o = ev.evaluate(wc(n("Q14", 2.0)), emptyList(), false)
        assertTrue(RuleIds.PROG_04 in rules(o))
        assertTrue(RuleIds.RIR_05 in rules(o))
        assertEquals(1, o.pendingModifiers.size)
    }

    @Test fun q15HungerTwoWeeksInADeficitTriggersNut17() {
        assertEquals(setOf(RuleIds.NUT_17), rules(ev.evaluate(wc(n("Q15", 4.0)), prev(n("Q15", 5.0)), true)))
        assertTrue(ev.evaluate(wc(n("Q15", 4.0)), prev(n("Q15", 5.0)), false).triggered.isEmpty())
        assertTrue(ev.evaluate(wc(n("Q15", 4.0)), emptyList(), true).triggered.isEmpty())
    }
}
