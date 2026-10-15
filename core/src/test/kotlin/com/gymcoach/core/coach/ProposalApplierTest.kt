package com.gymcoach.core.coach

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanDiff
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.time.FixedClock
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ProposalApplierTest {
    private val clock = FixedClock(CoachFixtures.NOW)
    private val plans = FakePlans()
    private val coach = FakeCoach()
    private val workouts = FakeWorkouts()
    private val applier = ProposalApplier(plans, coach, clock)

    private fun proposal(id: String, m: PlanMutation, status: ProposalStatus = ProposalStatus.PENDING) = Proposal(
        id = id, conversationId = "c1", assistantMessageId = "a1", toolName = "propose_swap_exercise", contractVersion = 1,
        payloadJson = "{}", mutation = m, rationale = m.rationale, status = status, createdAt = CoachFixtures.NOW,
        expiresOn = CoachFixtures.TODAY.plusDays(7),
    ).also { coach.proposals[id] = it }

    private val swapToday = PlanMutation.SwapExercise("barbell_bench_press", "dumbbell_bench_press", null, MutationScope.TODAY, "TRV-04 hotel")
    private val swapMeso = swapToday.copy(scope = MutationScope.REST_OF_MESOCYCLE)

    @Test
    fun todayScopeSetsExpiresOnAndAppendsAChatOverride() = runBlocking {
        val p = proposal("toolu_1", swapToday)
        val o = applier.apply(p, CoachFixtures.TODAY)
        assertEquals(LocalDate.of(2026, 9, 9), o.expiresOn)
        assertEquals(OverrideSource.CHAT, o.source)
        assertEquals("toolu_1", o.proposalId)
        assertEquals(swapToday, o.mutation)
        assertEquals(listOf(o), plans.overrides)
        val stored = coach.proposals["toolu_1"]!!
        assertEquals(ProposalStatus.APPLIED, stored.status)
        assertEquals(o.id, stored.overrideId)
        assertEquals(ProposalApplier.PROVISIONAL_SUMMARY, stored.resultSummary)
    }

    @Test
    fun otherScopesHaveNoExpiry() = runBlocking {
        assertNull(applier.apply(proposal("toolu_2", swapMeso), CoachFixtures.TODAY).expiresOn)
        assertNull(applier.apply(proposal("toolu_3", PlanMutation.ScheduleDeload(true, "DLD-03")), CoachFixtures.TODAY).expiresOn)
    }

    @Test
    fun neverTouchesLoggedSetsAndOnlyAppends() = runBlocking {
        val before = plans.overrides.size
        applier.apply(proposal("toolu_4", swapMeso), CoachFixtures.TODAY)
        applier.reject(proposal("toolu_5", swapMeso), "prefer barbell")
        applier.defer(proposal("toolu_6", swapMeso))
        assertEquals(before + 1, plans.overrides.size, "exactly one override appended, none revoked")
        assertTrue(plans.overrides.all { it.revokedAt == null })
        assertEquals(emptyList(), workouts.writes, "the applier has no access to set logs")
    }

    @Test
    fun invalidOrDecidedProposalsCannotBeApplied() {
        val invalid = proposal("toolu_7", swapMeso).copy(mutation = null, status = ProposalStatus.INVALID)
        assertFailsWith<IllegalArgumentException> { runBlocking { applier.apply(invalid, CoachFixtures.TODAY) } }
        val applied = proposal("toolu_8", swapMeso, ProposalStatus.APPLIED)
        assertFailsWith<IllegalStateException> { runBlocking { applier.apply(applied, CoachFixtures.TODAY) } }
        assertEquals(0, plans.overrides.size)
    }

    @Test
    fun finalizeStoresTheDiffJsonAndRejectDeferFailRecordTheirStatus() = runBlocking {
        val p = proposal("toolu_9", swapMeso)
        val o = applier.apply(p, CoachFixtures.TODAY)
        val diff = PlanDiff(listOf("Mon UPPER_A"), mapOf(Muscle.CHEST to 12.0), mapOf(Muscle.CHEST to 12.0), listOf("dumbbell_bench_press"), listOf("barbell_bench_press"))
        applier.finalize(p.id, diff, o.id)
        val s = coach.proposals[p.id]!!
        assertEquals(ProposalStatus.APPLIED, s.status)
        assertTrue(s.resultSummary!!.startsWith("{\"sessions_changed\":[\"Mon UPPER_A\"]"), s.resultSummary)
        applier.reject(proposal("r", swapMeso), "prefer barbell")
        assertEquals("rejected by user: prefer barbell", coach.proposals["r"]!!.resultSummary)
        assertEquals(ProposalStatus.REJECTED, coach.proposals["r"]!!.status)
        applier.defer(proposal("d", swapMeso))
        assertEquals(ProposalStatus.DEFERRED, coach.proposals["d"]!!.status)
        applier.markFailed("d", "recompute failed")
        assertEquals(ProposalStatus.FAILED, coach.proposals["d"]!!.status)
        assertEquals("recompute failed", coach.proposals["d"]!!.resultSummary)
    }

    @Test
    fun adjustmentForEmitsAnOverrideAdjustmentWithCitedRuleIds() = runBlocking {
        val p = proposal("toolu_10", swapMeso)
        val o = applier.apply(p, CoachFixtures.TODAY)
        val diff = PlanDiff(listOf("Mon UPPER_A"), mapOf(Muscle.CHEST to 12.0), mapOf(Muscle.CHEST to 12.0))
        val a = applier.adjustmentFor(p, o, diff)
        assertEquals(AdjustmentKind.OVERRIDE, a.kind)
        assertEquals(OverrideSource.CHAT, a.source)
        assertEquals(listOf(RuleIds.OVERRIDE, "TRV-04"), a.ruleIds)
        assertEquals("toolu_10", a.proposalId)
        assertEquals(o.id, a.overrideId)
        assertEquals("{\"chest\":12.0}", a.beforeJson)
        assertTrue(a.afterJson.contains("\"type\":\"swap_exercise\""))
        assertTrue(a.summary.contains("swap exercise"))
    }
}
