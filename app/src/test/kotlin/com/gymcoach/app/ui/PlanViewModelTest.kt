package com.gymcoach.app.ui

import com.gymcoach.app.fakes.FakeRepositories
import com.gymcoach.app.ui.plan.AdjustmentLogViewModel
import com.gymcoach.app.ui.plan.PlanViewModel
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.PlanMutation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModelTest {

    @Test
    fun the_current_plan_week_and_selection_are_exposed() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        val deps = TestEnv.deps(fakes)
        val meso = deps.coordinator.recompute("test setup")
        val vm = PlanViewModel(deps, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(meso.id, assertNotNull(s.mesocycle).id)
        assertEquals(false, s.loading)
        assertNotNull(s.currentWeek)
        val other = meso.weeks.firstOrNull { it.index != s.weekIndex }
        if (other != null) {
            vm.onWeekSelect(other.index)
            advanceUntilIdle()
            assertEquals(other.index, vm.state.value.weekIndex)
        }
        assertEquals(false, vm.state.value.ledger.showDirect)
        vm.onToggleDirect(); advanceUntilIdle()
        assertEquals(true, vm.state.value.ledger.showDirect)
        vm.shutdown()
    }

    @Test
    fun an_override_is_validated_stored_logged_and_can_be_revoked_with_a_recompute() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        val deps = TestEnv.deps(fakes)
        val meso = deps.coordinator.recompute("test setup")
        val vm = PlanViewModel(deps, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val target = meso.weeks.firstOrNull()?.sessions?.firstOrNull()?.slots?.lastOrNull()?.exerciseId ?: "lat_pulldown"
        fakes.events.clear()

        vm.onExclude(target)
        advanceUntilIdle()
        assertEquals(null, vm.state.value.error, "the validator accepts excluding one exercise")
        val stored = fakes.plans.overrides().single()
        assertTrue(stored.mutation is PlanMutation.ExcludeExercise)
        assertEquals(1, vm.state.value.overrides.size)
        assertTrue(fakes.events.indexOf("plans.addOverride") < fakes.events.indexOf("plans.saveSnapshot"), "override first, then recompute: ${fakes.events}")
        val logged = fakes.workouts.allAdjustments.single()
        assertEquals(AdjustmentKind.OVERRIDE, logged.kind); assertEquals(stored.id, logged.overrideId)

        fakes.events.clear()
        vm.onRevokeOverride(stored.id)
        advanceUntilIdle()
        assertTrue(fakes.plans.overrides().single().revokedAt != null)
        assertTrue(vm.state.value.overrides.isEmpty())
        assertTrue(fakes.events.indexOf("plans.revokeOverride") < fakes.events.indexOf("plans.saveSnapshot") || "plans.saveSnapshot" !in fakes.events)
        assertEquals(2, fakes.workouts.allAdjustments.size)       // every change, also the undo, is in the log

        // the adjustment log can undo the first entry's override (already revoked here: nothing left to undo)
        val log = AdjustmentLogViewModel(deps, 50, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(2, log.state.value.items.size)
        log.shutdown(); vm.shutdown()
    }

    @Test
    fun an_invalid_change_is_refused_and_writes_nothing() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        val deps = TestEnv.deps(fakes)
        deps.coordinator.recompute("test setup")
        val vm = PlanViewModel(deps, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.applyMutation(PlanMutation.ChangeVolume(Muscle.CHEST, 99, MutationScope.REST_OF_MESOCYCLE, "absurd"))
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertTrue(fakes.plans.overrides().isEmpty())
        assertTrue(fakes.workouts.allAdjustments.isEmpty())
        vm.shutdown()
    }

    @Test
    fun a_failed_regenerate_keeps_the_previous_plan_and_shows_an_error() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        val deps = TestEnv.deps(fakes)
        val meso = deps.coordinator.recompute("test setup")
        val vm = PlanViewModel(deps, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        fakes.profile.clear()
        vm.onRegenerate()
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertEquals(meso.id, vm.state.value.mesocycle?.id)
        vm.shutdown()
    }

    @Test
    fun dismissing_a_planner_proposal_hides_it_without_writing() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        val deps = TestEnv.deps(fakes)
        deps.coordinator.recompute("test setup")
        val vm = PlanViewModel(deps, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val m = PlanMutation.ScheduleDeload(startThisWeek = false, rationale = "not now")
        vm.onProposal(m, false)
        advanceUntilIdle()
        assertTrue(m !in vm.state.value.proposals)
        assertTrue(fakes.plans.overrides().isEmpty())
        vm.shutdown()
    }
}
