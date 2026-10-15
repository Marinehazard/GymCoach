package com.gymcoach.app.ui

import com.gymcoach.app.coordinator.PlanCoordinator
import com.gymcoach.app.fakes.FakeRepositories
import com.gymcoach.app.ui.today.TodayViewModel
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.core.coach.ProposalApplier
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.ClasspathKnowledgeSource
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeLoader
import com.gymcoach.core.mobility.MobilityPlanner
import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.mutation.MutationValidator
import com.gymcoach.core.nutrition.SupplementAdvisor
import com.gymcoach.core.planner.Planner
import com.gymcoach.core.progression.DeloadPolicy
import com.gymcoach.core.progression.ProgressSeries
import com.gymcoach.core.progression.ProgressionEngine
import com.gymcoach.core.readiness.ReadinessScorer
import com.gymcoach.core.substitution.SubstitutionEngine
import com.gymcoach.core.time.Clock
import com.gymcoach.core.time.FixedClock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** Shared fixture for the WP-07 ViewModel tests: real knowledge base, real PlanCoordinator, in-memory repositories. */
internal object TestEnv {
    val kb: KnowledgeBase by lazy { KnowledgeLoader(CoreJson.assets).load(ClasspathKnowledgeSource()) }
    val T0: Instant = Instant.parse("2026-10-15T07:00:00Z")   // a Thursday

    fun profile(now: Instant = T0) = Profile(
        sex = Sex.MALE, birthYear = 1990, height = EnteredLength(178.0, LengthUnit.CM, 178.0), trainingMonthsConsistent = 18,
        goal = Goal.HYPERTROPHY, daysPerWeek = 3, sessionMinutes = 60, preferredWeekdays = listOf(1, 3, 5), updatedAt = now,
    )

    /** Dependencies over [fakes]; [clock] may differ from fakes.clock to simulate a later day on the same data. */
    fun deps(fakes: FakeRepositories, clock: Clock = fakes.clock): TrainingDeps {
        val coordinator = PlanCoordinator(
            plans = fakes.plans, profile = fakes.profile, gyms = fakes.gyms, workouts = fakes.workouts, progression = fakes.progression,
            metrics = fakes.metrics, settings = fakes.settings, knowledge = kb,
            plannerFactory = { c -> Planner(kb, c) }, engineFactory = { c -> ProgressionEngine(kb, c) }, deloadPolicyFactory = { c -> DeloadPolicy(kb, c) }, clock = clock,
        )
        return TrainingDeps(
            clock = clock, knowledge = kb, settings = fakes.settings, profile = fakes.profile, gyms = fakes.gyms, plans = fakes.plans,
            workouts = fakes.workouts, progression = fakes.progression, metrics = fakes.metrics, coach = fakes.coach, coordinator = coordinator,
            proposalApplier = ProposalApplier(fakes.plans, fakes.coach, clock), progressSeries = ProgressSeries(kb),
            plannerFactory = { c -> Planner(kb, c) }, engineFactory = { c -> ProgressionEngine(kb, c) },
            substitutionFactory = { c -> SubstitutionEngine(kb, c) }, readinessFactory = { c -> ReadinessScorer(c) },
            supplementFactory = { c -> SupplementAdvisor(kb, c) }, validatorFactory = { c -> MutationValidator(kb, c) },
            mobilityFactory = { c -> MobilityPlanner(kb, c) }, computeDispatcher = Dispatchers.Unconfined,
        )
    }

    suspend fun seedGymAndProfile(fakes: FakeRepositories) {
        fakes.profile.save(profile(fakes.clock.now()))
        fakes.gyms.upsert(Presets.gymFromPreset(kb, Presets.BASE_GYM, "Base gym", "gym-1", fakes.clock.now()).copy(isDefault = true))
    }

    /** One RED, poor-sleep check-in per day for the last [days] days: two DLD-03 triggers (red_days, sleep_quality) active since well over 5 days. */
    suspend fun seedDeloadTriggers(fakes: FakeRepositories, days: Int = 10) {
        val today = fakes.clock.today()
        for (i in days downTo 0) {
            val d = today.minusDays(i.toLong())
            fakes.metrics.addReadiness(ReadinessCheckin(
                id = "r$i", date = d, sleepHours = 5.0, sleepQuality = 1, soreness = 3, stress = 4, energy = 2, motivation = 2, score = 30, band = ReadinessBand.RED,
                explanation = Explanation(listOf("RDY-01"), "test"), loggedAt = fakes.clock.now().minusSeconds(i * 86_400L),
            ))
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    private fun TestScope.vmOf(fakes: FakeRepositories, clock: Clock = fakes.clock): TodayViewModel =
        TodayViewModel(TestEnv.deps(fakes, clock), StandardTestDispatcher(testScheduler))

    @Test
    fun day_context_is_persisted_restored_and_cleared_when_the_date_changes() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        val vm1 = vmOf(fakes)
        advanceUntilIdle()
        assertNull(vm1.state.value.sessionPlan)
        vm1.onToggleSpotter(); vm1.onMinutesToday(45)
        advanceUntilIdle()
        val stored = CoreJson.stored.decodeFromString(TodayContext.serializer(), fakes.settings.get().todayContextJson)
        assertTrue(stored.spotter); assertEquals(45, stored.minutesOverride); assertEquals(LocalDate.of(2026, 10, 15), stored.date)
        vm1.shutdown()

        val vm2 = vmOf(fakes)                       // "next launch of the same date"
        advanceUntilIdle()
        assertTrue(vm2.state.value.spotter); assertEquals(45, vm2.state.value.minutesToday)
        vm2.shutdown()

        val tomorrow = FixedClock(Instant.parse("2026-10-16T07:00:00Z"))
        val vm3 = vmOf(fakes, tomorrow)             // date changed: restored nothing, cleared the stored context
        advanceUntilIdle()
        assertFalse(vm3.state.value.spotter); assertNull(vm3.state.value.minutesToday)
        assertEquals("", fakes.settings.get().todayContextJson)
        vm3.shutdown()
    }

    @Test
    fun travel_kit_and_removed_equipment_toggle_into_the_context() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        val vm = vmOf(fakes)
        advanceUntilIdle()
        vm.onRemoveEquipmentToday(setOf("leg_press"))
        advanceUntilIdle()
        assertEquals(setOf("leg_press"), vm.state.value.removedEquipment)
        vm.onToggleTravelKit()
        advanceUntilIdle()
        val on = vm.state.value.travelKit
        vm.onToggleTravelKit()
        advanceUntilIdle()
        assertEquals(!on, vm.state.value.travelKit)
        vm.shutdown()
    }

    @Test
    fun accept_deload_writes_the_override_then_records_the_decision_then_recomputes() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        TestEnv.seedDeloadTriggers(fakes)
        val deps = TestEnv.deps(fakes)
        deps.coordinator.recompute("test setup")
        val vm = TodayViewModel(deps, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertNotNull(vm.state.value.deloadSuggestion, "two DLD-03 triggers active for 5+ days -> the Today card")
        fakes.events.clear()

        vm.onAcceptDeload()
        advanceUntilIdle()

        val override = fakes.events.indexOf("plans.addOverride")
        val decision = fakes.events.indexOf("settings.deloadTriggersProposed")
        val recompute = fakes.events.indexOf("plans.saveSnapshot")
        assertTrue(override >= 0 && decision >= 0 && recompute >= 0, "events were ${fakes.events}")
        assertTrue(override < decision && decision < recompute, "order must be override -> recordDeloadDecision -> recompute, was ${fakes.events}")
        val written = fakes.plans.overrides().single()
        assertTrue(written.mutation is PlanMutation.ScheduleDeload)
        assertEquals(com.gymcoach.core.model.OverrideSource.USER, written.source)
        assertNull(vm.state.value.deloadSuggestion, "the onsets are recorded, the card is gone")
        vm.shutdown()
    }

    @Test
    fun dismiss_deload_only_records_the_decision() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        TestEnv.seedDeloadTriggers(fakes)
        val deps = TestEnv.deps(fakes)
        deps.coordinator.recompute("test setup")
        val vm = TodayViewModel(deps, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertNotNull(vm.state.value.deloadSuggestion)
        fakes.events.clear()

        vm.onDismissDeload()
        advanceUntilIdle()

        assertEquals(listOf("settings.deloadTriggersProposed"), fakes.events.filter { it.startsWith("plans.") || it.startsWith("settings.") })
        assertTrue(fakes.plans.overrides().isEmpty())
        assertNull(vm.state.value.deloadSuggestion)
        vm.shutdown()
    }

    @Test
    fun start_session_freezes_the_plan_and_records_every_modifier() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        val deps = TestEnv.deps(fakes)
        deps.coordinator.recompute("test setup")
        val vm = TodayViewModel(deps, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val plan = assertNotNull(vm.state.value.sessionPlan)
        vm.onStartSession()
        advanceUntilIdle()
        val open = assertNotNull(deps.workouts.observeOpenSession().first())
        assertEquals(SessionStatus.IN_PROGRESS, open.status)
        assertEquals(plan, open.plan)
        assertEquals(plan.modifiers.size, fakes.workouts.adjustmentsForSession(open.id).size)
        vm.shutdown()
    }

    @Test
    fun quick_weight_and_cardio_log_store_exactly_what_was_typed() = runTest {
        val fakes = FakeRepositories()
        TestEnv.seedGymAndProfile(fakes)
        fakes.settings.update { it.copy(unitSystem = UnitSystem.IMPERIAL) }
        val vm = vmOf(fakes)
        advanceUntilIdle()
        vm.onQuickWeight("176,4")
        vm.onLogCardio()
        vm.onCardioDraft(assertNotNull(vm.cardioDraft.value).copy(minutes = "35", avgHr = "128", rpe = 12))
        vm.onCardioSave()
        advanceUntilIdle()
        val w = fakes.metrics.bodyMetricsBetween(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15)).single().weight
        assertEquals(176.4, assertNotNull(w).entered); assertEquals(WeightUnit.LB, w.unit)
        val log = fakes.metrics.cardioLogs.single()
        assertEquals(35, log.minutes); assertEquals(128, log.avgHr); assertEquals(12, log.rpe)
        vm.shutdown()
    }
}
