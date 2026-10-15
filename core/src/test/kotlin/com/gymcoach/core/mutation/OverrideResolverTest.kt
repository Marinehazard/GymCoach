package com.gymcoach.core.mutation

import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.planner.PlannerFixtures
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverrideResolverTest {
    private val day = PlannerFixtures.MONDAY
    private fun o(id: String, at: String, m: PlanMutation, revoked: Instant? = null, expires: java.time.LocalDate? = null) =
        PlanOverride(id, Instant.parse(at), OverrideSource.USER, null, m, revoked, expires)

    @Test
    fun laterCreatedAtWinsPerTarget() {
        val a = o("a", "2026-09-01T10:00:00Z", PlanMutation.ChangeGoal(Goal.STRENGTH, false, "x"))
        val b = o("b", "2026-09-02T10:00:00Z", PlanMutation.ChangeGoal(Goal.FAT_LOSS, false, "x"))
        assertEquals(listOf("b"), OverrideResolver.active(listOf(b, a), day).map { it.id })
        val pins = listOf(o("p1", "2026-09-01T10:00:00Z", PlanMutation.PinExercise("dumbbell_bench_press", "UPPER_A", "x")), o("p2", "2026-09-01T11:00:00Z", PlanMutation.PinExercise("dumbbell_row", "UPPER_B", "x")))
        assertEquals(listOf("p1", "p2"), OverrideResolver.active(pins, day).map { it.id })
    }

    @Test
    fun revokedAndExpiredOverridesAreInactive() {
        val revoked = o("r", "2026-09-01T10:00:00Z", PlanMutation.ChangeGoal(Goal.STRENGTH, false, "x"), revoked = Instant.parse("2026-09-03T10:00:00Z"))
        val expired = o("e", "2026-09-01T10:00:00Z", PlanMutation.SwapExercise("barbell_row", "dumbbell_row", null, MutationScope.TODAY, "x"), expires = day.minusDays(1))
        val today = o("t", "2026-09-01T10:00:00Z", PlanMutation.SwapExercise("barbell_row", "dumbbell_row", null, MutationScope.TODAY, "x"), expires = day)
        val until = o("u", "2026-09-01T10:00:00Z", PlanMutation.ExcludeExercise("dumbbell_fly", day.minusDays(2), "x"))
        assertEquals(listOf("t"), OverrideResolver.active(listOf(revoked, expired, today, until), day).map { it.id })
    }

    @Test
    fun excludeBeatsPinForTheSameExercise() {
        val pin = o("p", "2026-09-02T10:00:00Z", PlanMutation.PinExercise("dumbbell_bench_press", null, "x"))
        val ex = o("x", "2026-09-01T10:00:00Z", PlanMutation.ExcludeExercise("dumbbell_bench_press", null, "x"))
        val active = OverrideResolver.active(listOf(pin, ex), day)
        assertEquals(listOf("x"), active.map { it.id })
        assertTrue(RuleIds.OVERRIDE in RuleIds.ALL)
    }

    @Test
    fun addAndRemoveCardioShareTheirWeekdayTarget() {
        val add = o("a", "2026-09-01T10:00:00Z", PlanMutation.AddCardio(3, 30, null, com.gymcoach.core.model.CardioKind.ZONE2, "x"))
        val rem = o("r", "2026-09-02T10:00:00Z", PlanMutation.RemoveCardio(3, "x"))
        assertEquals(listOf("r"), OverrideResolver.active(listOf(add, rem), day).map { it.id })
    }
}
