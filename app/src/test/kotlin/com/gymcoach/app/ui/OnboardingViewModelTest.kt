package com.gymcoach.app.ui

import com.gymcoach.app.fakes.FakeRepositories
import com.gymcoach.app.ui.onboarding.OnboardingEvent
import com.gymcoach.app.ui.onboarding.OnboardingViewModel
import com.gymcoach.app.ui.onboarding.ProfileDraft
import com.gymcoach.app.ui.profile.DraftRules
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.ClasspathKnowledgeSource
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeLoader
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.PlannedSlot
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.seed.SeedProfile
import com.gymcoach.core.seed.SeedProfiles
import java.time.LocalDate
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** OnboardingViewModel: draft persistence, validation, preset gym persisted before the editor, plan built from the stored gym. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val kb: KnowledgeBase by lazy { KnowledgeLoader(CoreJson.assets).load(ClasspathKnowledgeSource()) }

    private class Harness(val repos: FakeRepositories, val vm: OnboardingViewModel, val builds: IntArray)

    private fun TestScope.scope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private fun meso(): Mesocycle = Mesocycle(
        id = "meso-test", startDate = LocalDate.of(2026, 10, 12), goal = Goal.GENERAL_FITNESS, trainingAge = TrainingAge.NOVICE,
        split = SplitKind.FULL_BODY_3, gymId = "g", inputsHash = "hash",
        weeks = listOf(
            MesoWeek(
                index = 1, startDate = LocalDate.of(2026, 10, 12), mode = WeekMode.NORMAL, rirOffset = 1, targets = emptyMap(),
                sessions = listOf(1, 3, 5).map { day ->
                    PlannedSession(
                        id = "s$day", templateId = "FULL_A", name = "Full body", weekday = day,
                        slots = listOf(PlannedSlot("a", SlotRole.PRIMARY, MovementPattern.SQUAT, "back_squat", 0, 3, true, null, Explanation(listOf("SEL"), "squat"))),
                        estimatedMinutes = 50.0, volumeFractional = emptyMap(), volumeDirect = emptyMap(),
                    )
                },
            ),
        ),
    )

    private fun TestScope.harness(repos: FakeRepositories = FakeRepositories(), seed: SeedProfile? = null): Harness {
        val builds = IntArray(1)
        val vm = OnboardingViewModel(
            profiles = repos.profile, gyms = repos.gyms, metrics = repos.metrics, settings = repos.settings, knowledge = kb, clock = repos.clock,
            buildPlan = { builds[0]++; meso() }, scopeOverride = scope(), computeContext = EmptyCoroutineContext,
            seed = seed, workouts = repos.workouts, progression = repos.progression,
        )
        return Harness(repos, vm, builds)
    }

    private val joe: SeedProfile by lazy { assertNotNull(SeedProfiles.classpath()) }

    @Test fun seed_prefillsEveryPage_andAsksForTheUnknownWeight() = runTest {
        val h = harness(seed = joe)
        val d = h.vm.state.value.draft
        assertEquals(SeedProfiles.JOE_V1, d.seedId)
        assertEquals(1978, d.birthYear)
        assertEquals(Goal.HYPERTROPHY, d.goal)
        assertEquals(5, d.daysPerWeek)
        assertEquals(listOf(1, 2, 4, 5, 6), d.weekdays)
        assertEquals("joe_5day", d.splitId)
        assertEquals("joe_5day", d.seedSplitId)
        assertEquals(120, d.trainingMonths)
        assertEquals(3, d.limitations.size)
        assertEquals("90", d.knownLoads["back_squat"])
        assertEquals("", d.weightValue, "no weight in the seed: page 2 must ask")
        assertEquals("Joe", h.vm.extras.value.seedName)
        assertTrue(h.vm.extras.value.seedHints.isNotEmpty())
        assertEquals("gym-joe-home", h.vm.state.value.gymDraft?.id)
        h.vm.onNext()                                                  // 1 -> 2
        h.vm.onNext()                                                  // blocked: weight missing
        assertEquals(2, h.vm.state.value.page)
    }

    @Test fun seed_createsBothGymsImportsTheHistoryAndKeepsTheProfileFacts() = runTest {
        val h = harness(seed = joe)
        h.vm.onDraftChange(h.vm.state.value.draft.copy(weightValue = "92"))
        repeat(3) { h.vm.onNext() }                                     // 2, 3, 4
        h.vm.onNext()                                                  // 5
        h.vm.onNext()                                                  // build
        assertEquals(6, h.vm.state.value.page)
        val gyms = h.repos.gyms.getAll()
        assertEquals(setOf("gym-joe-home", "gym-joe-bigger"), gyms.map { it.id }.toSet())
        assertEquals("gym-joe-home", gyms.single { it.isDefault }.id)
        val p = assertNotNull(h.repos.profile.get())
        assertEquals("joe_5day", p.splitId)
        assertEquals(2, p.barbellRirFloor)
        assertEquals(180.0, p.strengthTargetsKg?.get("conventional_deadlift"))
        assertTrue(p.coachNotes.orEmpty().isNotEmpty())
        assertEquals(joe.history.sets.size, h.repos.workouts.allSets.size)
        assertEquals(90.0, h.repos.progression.get(com.gymcoach.core.model.ProgressionKey("back_squat"))?.workingLoadKg)
        assertEquals(80.0, h.repos.progression.get(com.gymcoach.core.model.ProgressionKey("back_squat", null, "backoff"))?.workingLoadKg)
        // Building again (back + next) never duplicates the imported rows.
        h.vm.onBack()
        h.vm.onNext()
        assertEquals(joe.history.sets.size, h.repos.workouts.allSets.size)
    }

    @Test fun seed_canBeDeclinedForTheGenericPath() = runTest {
        val h = harness(seed = joe)
        h.vm.onUseGenericProfile()
        assertEquals(ProfileDraft(), h.vm.state.value.draft)
        assertNull(h.vm.extras.value.seedName)
        val again = harness(h.repos, seed = joe)
        assertNull(again.vm.state.value.draft.seedId, "a declined seed is not offered again")
        // the split toggle turns the personal split off without losing it
        val h2 = harness(seed = joe)
        h2.vm.onDraftChange(h2.vm.state.value.draft.copy(splitId = null))
        assertEquals("joe_5day", h2.vm.state.value.draft.seedSplitId)
    }

    @Test fun everyChangeIsPersistedAndRestoredByANewViewModel() = runTest {
        val h = harness()
        h.vm.onDraftChange(h.vm.state.value.draft.copy(goal = Goal.STRENGTH, daysPerWeek = 4))
        h.vm.onNext()
        val json = h.repos.settings.get().onboardingDraftJson
        assertTrue(json.isNotBlank())

        val again = harness(h.repos)
        assertEquals(Goal.STRENGTH, again.vm.state.value.draft.goal)
        assertEquals(4, again.vm.state.value.draft.daysPerWeek)
        assertEquals(2, again.vm.state.value.page)
    }

    @Test fun invalidBodyValuesBlockPageTwo() = runTest {
        val h = harness()
        h.vm.onNext()                                  // 1 -> 2
        h.vm.onDraftChange(h.vm.state.value.draft.copy(heightValue = "100"))
        assertTrue(!h.vm.extras.value.heightOk)
        h.vm.onNext()
        assertEquals(2, h.vm.state.value.page)
        assertNotNull(h.vm.state.value.error)
        h.vm.onDraftChange(h.vm.state.value.draft.copy(heightValue = "180"))
        h.vm.onNext()
        assertEquals(3, h.vm.state.value.page)
    }

    @Test fun reviewEquipment_persistsTheDefaultPresetGymBeforeNavigating() = runTest {
        val h = harness()
        h.vm.onPresetSelected("hotel_gym")
        assertTrue(h.repos.gyms.getAll().isEmpty(), "nothing is stored before Review equipment")
        h.vm.onReviewEquipment()
        val event = h.vm.events.first()
        val stored = h.repos.gyms.getAll().single()
        assertTrue(stored.isDefault)
        assertEquals("hotel_gym", stored.presetOrigin)
        assertEquals(OnboardingEvent.OpenGymEditor(stored.id), event)

        // the editor saves a change; the ViewModel re-reads the stored gym (no result channel)
        h.repos.gyms.upsert(stored.copy(name = "Hotel Ulm"))
        assertEquals("Hotel Ulm", h.vm.state.value.gymDraft?.name)
        assertEquals("Hotel Ulm", h.vm.extras.value.gymName)
    }

    @Test fun finishing_savesProfileWeightAndBuildsThePlanOnce() = runTest {
        val h = harness()
        h.vm.onNext()                                                  // 2
        h.vm.onNext()                                                  // 3
        h.vm.onNext()                                                  // 4
        h.vm.onSkipPage()                                              // 5
        h.vm.onDraftChange(h.vm.state.value.draft.copy(goal = Goal.HYPERTROPHY, daysPerWeek = 3, knownLoads = mapOf("back_squat" to "100")))
        h.vm.onPresetSelected("home_gym")
        h.vm.onNext()                                                  // build
        assertEquals(6, h.vm.state.value.page)
        assertEquals(1, h.builds[0])
        assertNotNull(h.vm.state.value.builtSummary)
        assertEquals(7, h.vm.built.value?.days?.size)
        assertEquals(9, h.vm.built.value?.totalSets)

        val profile = assertNotNull(h.repos.profile.get())
        assertEquals(Goal.HYPERTROPHY, profile.goal)
        assertEquals(100.0, profile.knownLoadsKg["back_squat"])
        val gym = h.repos.gyms.getAll().single()
        assertTrue(gym.isDefault)
        val today = h.repos.clock.today()
        assertEquals(80.0, h.repos.metrics.bodyMetricsBetween(today, today).single().weight?.kg)

        h.vm.onFinish()
        assertEquals(1, h.builds[0], "finish does not rebuild an existing plan")
        val settings = h.repos.settings.get()
        assertTrue(settings.onboardingDone)
        assertEquals("", settings.onboardingDraftJson)
        assertEquals(OnboardingEvent.Finished, h.vm.events.first())
    }

    @Test fun skippingTheGymPageStillStoresAGymAndBuilds() = runTest {
        val h = harness()
        repeat(3) { h.vm.onNext() }
        h.vm.onSkipPage()      // 4 -> 5
        h.vm.onSkipPage()      // 5 -> build with the default commercial preset
        assertEquals(6, h.vm.state.value.page)
        assertEquals("base_gym", h.repos.gyms.getAll().single().presetOrigin)
    }

    @Test fun draftRules_clampOnSave_andConvertImperialInput() {
        val repos = FakeRepositories()
        val now = repos.clock.now()
        val today = repos.clock.today()
        val wild = ProfileDraft(heightValue = "300", birthYear = 1800, sessionMinutes = 500, daysPerWeek = 9)
        val p = DraftRules.toProfile(wild, null, now, today)
        assertEquals(230.0, p.height.cm)
        assertEquals(1920, p.birthYear)
        assertEquals(120, p.sessionMinutes)
        assertEquals(6, p.daysPerWeek)

        val imperial = ProfileDraft(unitSystem = UnitSystem.IMPERIAL, heightValue = "70", weightValue = "176", knownLoads = mapOf("back_squat" to "225"))
        val q = DraftRules.toProfile(imperial, null, now, today)
        assertEquals(70.0, q.height.entered)
        assertEquals(177.8, q.height.cm, 0.01)
        assertEquals(102.06, q.knownLoadsKg.getValue("back_squat"), 0.01)
        assertEquals(79.83, DraftRules.weightKg(imperial) ?: 0.0, 0.01)

        val back = DraftRules.switchUnits(ProfileDraft(heightValue = "178", weightValue = "80"), UnitSystem.IMPERIAL)
        assertEquals(UnitSystem.IMPERIAL, back.unitSystem)
        assertEquals("70.1", back.heightValue)
        assertNull(DraftRules.parseDateOrNull("not a date"))
    }
}
