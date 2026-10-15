package com.gymcoach.app.ui

import com.gymcoach.app.fakes.FakeRepositories
import com.gymcoach.app.ui.gyms.GymEditViewModel
import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.ClasspathKnowledgeSource
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeLoader
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.TodayContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** GymEditViewModel over FakeRepositories and the real knowledge base: presets, live availability, DE/EN search, params, save. */
@OptIn(ExperimentalCoroutinesApi::class)
class GymEditViewModelTest {

    private val kb: KnowledgeBase by lazy { KnowledgeLoader(CoreJson.assets).load(ClasspathKnowledgeSource()) }

    private fun TestScope.scope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private fun TestScope.newVm(repos: FakeRepositories, gymId: String = "", preset: String = ""): GymEditViewModel =
        GymEditViewModel(gymId, preset, repos.gyms, repos.settings, kb, repos.clock, scope())

    private fun available(items: List<GymItem>, repos: FakeRepositories): Int =
        EquipmentResolver(kb).availableExerciseIds(SessionEquipment(GymSet("x", "x", items = items, createdAt = repos.clock.now()))).size

    @Test fun hotelPreset_prefillsItemsMinutesAndLiveCount() = runTest {
        val repos = FakeRepositories()
        val vm = newVm(repos, preset = Presets.HOTEL_GYM)
        val s = vm.state.value
        assertTrue(s.isNew)
        assertEquals(Presets.HOTEL_GYM, s.gym.presetOrigin)
        assertEquals(45, s.gym.defaultSessionMinutes)
        assertTrue(s.gym.items.any { it.equipmentId == "dumbbells" })
        assertEquals(available(s.gym.items, repos), s.availableCount)
        assertEquals(kb.exercises.size, s.totalExercises)
        assertTrue(s.availableCount >= 40, "hotel gym: at least 40 exercises (R4)")
    }

    @Test fun emptyGym_showsOnlyBodyweightAndBandCount() = runTest {
        val repos = FakeRepositories()
        val vm = newVm(repos)
        val s = vm.state.value
        assertTrue(s.gym.items.isEmpty())
        assertEquals(available(emptyList(), repos), s.availableCount)
        assertTrue(s.availableCount > 0 && s.availableCount < s.totalExercises)
    }

    @Test fun toggleItem_updatesAvailabilityAndDirty() = runTest {
        val repos = FakeRepositories()
        val vm = newVm(repos)
        val before = vm.state.value.availableCount
        vm.onToggleItem("dumbbells")
        val afterAdd = vm.state.value
        assertTrue(afterAdd.dirty)
        assertTrue(afterAdd.availableCount > before)
        vm.onToggleItem("dumbbells")
        assertEquals(before, vm.state.value.availableCount)
    }

    @Test fun aliasSearch_findsGermanAndEnglishNames() = runTest {
        val repos = FakeRepositories()
        val vm = newVm(repos)
        vm.onQuery("Latzug")
        assertTrue(vm.visibleEquipment().any { it.id == "lat_pulldown_machine" })
        vm.onQuery("Kurzhanteln")
        assertTrue(vm.visibleEquipment().any { it.id == "dumbbells" })
        vm.onQuery("pulldown")
        assertTrue(vm.visibleEquipment().any { it.id == "lat_pulldown_machine" })
        vm.onQuery("")
        assertTrue(vm.visibleEquipment().none { it.sessionFlag })
    }

    @Test fun param_isStoredAndJsonNullClearsIt() = runTest {
        val repos = FakeRepositories()
        val vm = newVm(repos, preset = Presets.HOTEL_GYM)
        vm.onParam("dumbbells", "max_kg", JsonPrimitive(30.0))
        assertEquals(JsonPrimitive(30.0), vm.state.value.gym.items.first { it.equipmentId == "dumbbells" }.params["max_kg"])
        vm.onParam("dumbbells", "max_kg", JsonNull)
        assertTrue("max_kg" !in vm.state.value.gym.items.first { it.equipmentId == "dumbbells" }.params)
    }

    @Test fun travelKit_mergesPackableItems() = runTest {
        val repos = FakeRepositories()
        val vm = newVm(repos, preset = Presets.HOTEL_GYM)
        assertTrue(vm.state.value.gym.items.none { it.equipmentId == "resistance_bands" })
        vm.onToggleTravelKit()
        assertTrue(vm.state.value.gym.travelKitMerged)
        assertTrue(vm.state.value.gym.items.any { it.equipmentId == "resistance_bands" })
    }

    @Test fun save_persistsFirstGymAsDefault_andBlankNameIsRejected() = runTest {
        val repos = FakeRepositories()
        val vm = newVm(repos, preset = Presets.HOME_GYM)
        vm.onName("")
        vm.onSave()
        assertEquals(GymEditViewModel.ERROR_NAME, vm.state.value.error)
        assertTrue(repos.gyms.getAll().isEmpty())

        vm.onName("Garage")
        vm.onSave()
        val stored = repos.gyms.getAll().single()
        assertEquals("Garage", stored.name)
        assertTrue(stored.isDefault)
        assertTrue(!vm.state.value.dirty)
    }

    @Test fun existingGym_loadsAndKeepsDraftWhenEditing() = runTest {
        val repos = FakeRepositories()
        val gym = Presets.gymFromPreset(kb, Presets.HOME_GYM, "Home", "g-home", repos.clock.now())
        repos.gyms.upsert(gym)
        val vm = newVm(repos, gymId = "g-home")
        assertTrue(vm.loaded.value)
        assertEquals("Home", vm.state.value.gym.name)
        assertTrue(!vm.state.value.dirty)
        vm.onColor(3)
        assertEquals(3, vm.state.value.gym.colorIndex)
        assertTrue(vm.state.value.dirty)
    }

    @Test fun addToday_writesOneOffIntoTodayContextOnly() = runTest {
        val repos = FakeRepositories()
        val gym = Presets.gymFromPreset(kb, Presets.HOTEL_GYM, "Hotel Ulm", "g-hotel", repos.clock.now())
        repos.gyms.upsert(gym)
        val vm = newVm(repos, gymId = "g-hotel")
        vm.onAddToday("resistance_bands")
        val json = repos.settings.get().todayContextJson
        val ctx = CoreJson.stored.decodeFromString(TodayContext.serializer(), json)
        assertEquals(repos.clock.today(), ctx.date)
        assertEquals("g-hotel", ctx.gymId)
        assertTrue(ctx.extraItems.any { it.equipmentId == "resistance_bands" })
        assertNotNull(repos.gyms.get("g-hotel"))
        assertTrue(repos.gyms.get("g-hotel")?.items?.none { it.equipmentId == "resistance_bands" } == true)
    }
}
