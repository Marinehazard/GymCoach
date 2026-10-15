package com.gymcoach.app.ui

import com.gymcoach.app.fakes.FakeRepositories
import com.gymcoach.app.ui.session.KeypadField
import com.gymcoach.app.ui.session.SessionDraftDoc
import com.gymcoach.app.ui.session.SessionPhase
import com.gymcoach.app.ui.session.PlateMath
import com.gymcoach.app.ui.session.SessionViewModel
import com.gymcoach.app.ui.session.SetLogging
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.EnteredWeight
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.LoadSource
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.SessionMode
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetPrescription
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.VolumeSnapshot
import com.gymcoach.core.model.Warmup
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.units.Units
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val expl = Explanation(listOf("ORD-01"), "test")

    private fun block(id: String, slot: String, lt: LoadType, loadKg: Double?, sets: Int = 3, rest: Int = 120): ExerciseBlock = ExerciseBlock(
        blockId = "$slot:$id", slotId = slot, exerciseId = id, optionIndex = 0, effectiveLoadType = lt, slotRole = SlotRole.PRIMARY, order = 1,
        sets = (1..sets).map { SetPrescription(index = it, kind = SetKind.WORKING, reps = Band(6, 10), loadKg = loadKg, rir = 2, restSeconds = rest, loadSource = LoadSource.PROGRESSION_STATE) },
        progressionKey = ProgressionKey.forExercise(id, lt, "gym-1"), explanation = expl,
    )

    private fun plan(vararg blocks: ExerciseBlock) = SessionPlan(
        id = "free@2026-10-15", plannedSessionId = null, templateId = "t", name = "Upper A", date = LocalDate.of(2026, 10, 15), gymId = "gym-1", mode = SessionMode.AS_PLANNED,
        weekIndex = 1, warmup = Warmup(0, null, emptyList(), 0, expl), blocks = blocks.toList(), modifiers = emptyList(), estimatedMinutes = 50.0,
        volume = VolumeSnapshot(emptyMap(), emptyMap()),
    )

    private suspend fun seed(fakes: FakeRepositories, plan: SessionPlan?, status: SessionStatus = SessionStatus.IN_PROGRESS): SessionRecord {
        TestEnv.seedGymAndProfile(fakes)
        val rec = SessionRecord(id = "s1", date = LocalDate.of(2026, 10, 15), gymId = "gym-1", plan = plan, status = status, startedAt = fakes.clock.now().minusSeconds(600))
        fakes.workouts.startSession(rec)
        fakes.events.clear()
        return rec
    }

    private fun TestScope.vmOf(fakes: FakeRepositories) = SessionViewModel("s1", TestEnv.deps(fakes), StandardTestDispatcher(testScheduler), tickMillis = 0)

    @Test
    fun log_set_writes_exactly_the_displayed_values() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan(block("barbell_bench_press", "s1", LoadType.BARBELL, 80.0)))
        val vm = vmOf(fakes)
        runCurrent()
        val prefilled = vm.state.value.draft
        assertEquals("80", prefilled.loadValue); assertEquals("10", prefilled.reps); assertEquals(2, prefilled.rir)   // target load, repHi, target RIR

        vm.onLoadPlus(); vm.onRepsMinus(); vm.onRir(1)
        runCurrent()
        val shown = vm.state.value.draft
        assertEquals(Units.trim(80.0 + vm.state.value.stepKg), shown.loadValue); assertEquals("9", shown.reps); assertEquals(1, shown.rir)

        vm.onLogSet()
        runCurrent()
        val row = fakes.workouts.allSets.single()
        assertEquals(EnteredWeight(shown.loadValue.toDouble(), WeightUnit.KG, shown.loadValue.toDouble()), row.weight)
        assertEquals(9, row.reps); assertEquals(1, row.rir); assertEquals(Side.BOTH, row.side); assertEquals(SetKind.WORKING, row.kind)
        assertEquals(1, row.setIndex); assertEquals("s1:barbell_bench_press", row.blockId); assertEquals(80.0, row.prescribedLoadKg)
        assertEquals(Band(6, 10), row.prescribedReps); assertEquals(2, row.prescribedRir)
        assertEquals(fakes.clock.now(), row.loggedAt)
        assertEquals(1, vm.state.value.currentSetIndex)   // advanced to set 2 (position 1)
        vm.shutdown()
    }

    @Test
    fun log_set_keeps_the_typed_value_and_unit_in_imperial() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan(block("barbell_bench_press", "s1", LoadType.BARBELL, 80.0)))
        fakes.settings.update { it.copy(unitSystem = UnitSystem.IMPERIAL) }
        val vm = vmOf(fakes)
        runCurrent()
        assertEquals(WeightUnit.LB, vm.state.value.draft.loadUnit)
        vm.onKeypadCommit(KeypadField.LOAD, "185")
        runCurrent()
        vm.onLogSet()
        runCurrent()
        val w = assertNotNull(fakes.workouts.allSets.single().weight)
        assertEquals(185.0, w.entered); assertEquals(WeightUnit.LB, w.unit)
        assertEquals(Units.lbToKg(185.0), w.kg)
        vm.shutdown()
    }

    @Test
    fun a_unilateral_exercise_writes_one_row_with_side_both_per_set() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan(block("dumbbell_row", "s1", LoadType.DUMBBELL, 30.0, sets = 2)))
        val vm = vmOf(fakes)
        runCurrent()
        vm.onLogSet(); runCurrent()
        vm.onLogSet(); runCurrent()
        val rows = fakes.workouts.allSets
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.side == Side.BOTH })
        assertEquals(listOf(1, 2), rows.map { it.setIndex })
        vm.shutdown()
    }

    @Test
    fun a_correction_supersedes_and_never_updates_in_place() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan(block("barbell_bench_press", "s1", LoadType.BARBELL, 80.0)))
        val vm = vmOf(fakes)
        runCurrent()
        vm.onLogSet(); runCurrent()
        val original = fakes.workouts.allSets.single()

        vm.onCorrectSet(original.id); runCurrent()
        val draft = assertNotNull(vm.correcting.value).let { SetLogging.draftOf(it, WeightUnit.KG) }
        vm.onSaveCorrection(original.id, draft.copy(reps = "7")); runCurrent()

        val all = fakes.workouts.allSets
        assertEquals(2, all.size)
        val old = all.first { it.id == original.id }
        val new = all.first { it.id != original.id }
        assertEquals(new.id, old.supersededById); assertEquals(original.id, new.supersedesId)
        assertEquals(original.reps, old.reps); assertEquals(original.weight, old.weight)    // old row untouched
        assertEquals(7, new.reps)
        assertEquals(1, fakes.workouts.setsForSession("s1").size)                          // live view: only the correction
        assertEquals(1, fakes.events.count { it == "workouts.supersedeSet" })
        vm.shutdown()
    }

    @Test
    fun the_rest_timer_starts_with_the_prescribed_rest() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan(block("barbell_bench_press", "s1", LoadType.BARBELL, 80.0, rest = 150)))
        val vm = vmOf(fakes)
        runCurrent()
        assertNull(vm.state.value.rest)
        vm.onLogSet(); runCurrent()
        val rest = assertNotNull(vm.state.value.rest)
        assertEquals(150, rest.totalSec); assertEquals(fakes.clock.now().toEpochMilli() + 150_000L, rest.endsAtEpochMs)
        vm.onRestPlus30(); runCurrent()
        assertEquals(180, assertNotNull(vm.state.value.rest).totalSec)
        vm.onRestSkip(); runCurrent()
        assertNull(vm.state.value.rest)
        vm.shutdown()
    }

    @Test
    fun the_unlogged_draft_survives_process_death_and_the_session_comes_back_as_recovered() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan(block("barbell_bench_press", "s1", LoadType.BARBELL, 80.0)))
        val vm1 = vmOf(fakes)
        runCurrent()
        vm1.onLogSet(); runCurrent()
        vm1.onLoadPlus(); vm1.onLoadPlus(); vm1.onRepsMinus(); vm1.onRir(0)
        runCurrent()
        val before = vm1.state.value
        val doc = CoreJson.stored.decodeFromString(SessionDraftDoc.serializer(), fakes.settings.get().sessionDraftJson)
        assertEquals(before.draft, doc.draft); assertEquals(before.currentIndex, doc.currentIndex); assertEquals(before.currentSetIndex, doc.currentSetIndex)
        vm1.shutdown()

        val vm2 = vmOf(fakes)
        runCurrent()
        assertEquals(SessionPhase.RECOVERED, vm2.state.value.phase)
        assertEquals(before.draft, vm2.state.value.draft); assertEquals(before.currentSetIndex, vm2.state.value.currentSetIndex)
        assertTrue(vm2.state.value.progressText.contains("1 sets logged"))
        vm2.onDismissRecovered(); runCurrent()
        assertNotEquals(SessionPhase.RECOVERED, vm2.state.value.phase)
        vm2.shutdown()
    }

    @Test
    fun finishing_clears_the_draft_and_completes_the_record() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan(block("barbell_bench_press", "s1", LoadType.BARBELL, 80.0, sets = 1)))
        val vm = vmOf(fakes)
        runCurrent()
        vm.onLogSet(); runCurrent()
        assertTrue(fakes.settings.get().sessionDraftJson.isNotEmpty())
        vm.onFinishConfirm(8, "felt good", false); runCurrent()
        val rec = assertNotNull(fakes.workouts.getSession("s1"))
        assertEquals(SessionStatus.COMPLETED, rec.status); assertEquals(8, rec.sessionRpe); assertEquals("felt good", rec.notes)
        assertEquals("", fakes.settings.get().sessionDraftJson)
        vm.shutdown()
    }

    @Test
    fun a_session_without_a_plan_is_an_error_state_not_a_crash() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan = null)
        val vm = vmOf(fakes)
        runCurrent()
        assertEquals(SessionPhase.ERROR, vm.state.value.phase)
        assertTrue(assertNotNull(vm.state.value.error).contains("incomplete"))
        vm.onAbandon(); runCurrent()
        assertEquals(SessionStatus.ABANDONED, assertNotNull(fakes.workouts.getSession("s1")).status)
        vm.shutdown()
    }

    @Test
    fun planned_sets_can_be_added_and_removed_but_logged_ones_never() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan(block("barbell_bench_press", "s1", LoadType.BARBELL, 80.0, sets = 2)))
        val vm = vmOf(fakes)
        runCurrent()
        vm.onLogSet(); runCurrent()
        vm.onAddSet(); runCurrent()
        assertEquals(3, assertNotNull(fakes.workouts.getSession("s1")?.plan).blocks.single().sets.size)
        vm.onRemovePlannedSet(); vm.onRemovePlannedSet(); vm.onRemovePlannedSet(); runCurrent()
        assertEquals(1, assertNotNull(fakes.workouts.getSession("s1")?.plan).blocks.single().sets.size)   // set 1 is logged and stays
        assertEquals(1, fakes.workouts.allSets.size)
        vm.shutdown()
    }

    @Test
    fun after_the_last_planned_set_the_session_offers_finish_and_an_added_set_reopens_logging() = runTest {
        val fakes = FakeRepositories()
        seed(fakes, plan(block("barbell_bench_press", "s1", LoadType.BARBELL, 80.0, sets = 1)))
        val vm = vmOf(fakes)
        runCurrent()
        vm.onLogSet(); runCurrent()
        assertEquals(SessionPhase.FINISHING, vm.state.value.phase)
        vm.onAddSet(); runCurrent()
        assertEquals(SessionPhase.WORKING, vm.state.value.phase)
        assertEquals(1, vm.state.value.currentSetIndex)       // the added set is the next target
        assertEquals("s1:barbell_bench_press", vm.state.value.rows.single().block.blockId)
        vm.shutdown()
    }

    @Test
    fun the_stepper_and_prefill_helpers_keep_typed_numbers_exact() {
        assertEquals("176.5", SetLogging.formatLoad(80.0, WeightUnit.LB))   // lb display rounds to 0.5
        assertEquals("82.5", SetLogging.bump("80", 2.5))
        assertEquals("0", SetLogging.bump("1", -5.0))                        // never below zero
        assertEquals("7.5", SetLogging.bump("7,5", 0.0))                     // comma accepted
        assertEquals(82.5, SetLogging.parse("82,5"))
        assertNull(SetLogging.parse("abc"))
        assertEquals(listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25), PlateMath.inventory(1.25, false))
        assertEquals(listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5, 0.25), PlateMath.inventory(1.25, true))
        val r = PlateMath.load(61.0, 20.0, PlateMath.inventory(1.25, false))
        assertEquals(60.0, r.belowKg); assertEquals(62.5, r.aboveKg); assertEquals(false, r.exact)       // 61 kg is not loadable: nearest below / above
        assertEquals(listOf(20.0, 1.25), PlateMath.load(62.5, 20.0, PlateMath.inventory(1.25, false)).perSide)
    }
}
