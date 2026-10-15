package com.gymcoach.app.ui.session

import com.gymcoach.app.ui.today.ScopedViewModel
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.app.ui.today.uiDispatcher
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.SetPrescription
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.units.Units
import com.gymcoach.core.util.IdGen
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SessionViewModel(
    private val sessionId: String,
    private val deps: TrainingDeps,
    dispatcher: CoroutineDispatcher = uiDispatcher(),
    private val tickMillis: Long = 1000L,
) : ScopedViewModel(dispatcher) {

    private val emptyDraft = SetDraft("", WeightUnit.KG, "", null, "", "")
    private val _state = MutableStateFlow(SessionUiState(SessionPhase.LOADING, null, null, "", 0, "", false, emptyList(), 0, 0, emptyDraft, 1.0, UnitSystem.METRIC, null, emptyList(), null, null, null))
    val state: StateFlow<SessionUiState> = _state
    private val _swap = MutableStateFlow(SwapUiState(loading = false, candidates = emptyList(), reason = SwapReason.BUSY, error = null))
    val swap: StateFlow<SwapUiState> = _swap
    private val _info = MutableStateFlow<InfoUiState?>(null)
    val info: StateFlow<InfoUiState?> = _info
    private val _keypad = MutableStateFlow(KeypadField.LOAD)
    val keypadField: StateFlow<KeypadField> = _keypad
    private val _correcting = MutableStateFlow<SetLog?>(null)
    val correcting: StateFlow<SetLog?> = _correcting
    private val _findLoad = MutableStateFlow<Double?>(null)
    val findLoadOffer: StateFlow<Double?> = _findLoad
    private val _stale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = _stale
    private val navChannel = Channel<SessionNav>(Channel.BUFFERED)
    val navigation: Flow<SessionNav> = navChannel.receiveAsFlow()

    private var record: SessionRecord? = null
    private var plan: SessionPlan? = null
    private var gym: GymSet? = null
    private var profile: Profile? = null
    private var equipment: SessionEquipment? = null
    private var trainingAge: TrainingAge = TrainingAge.INTERMEDIATE
    private var states: List<ProgressionState> = emptyList()
    private var unit: WeightUnit = WeightUnit.KG
    private var system: UnitSystem = UnitSystem.METRIC
    private var allSets: List<SetLog> = emptyList()
    private var skipped: Set<String> = emptySet()
    private var curBlock = 0
    private var curSet = 0
    private var draft: SetDraft = emptyDraft
    private var rest: RestTimerState? = null
    private var warmupDone = false
    private var sheet: SessionSheet? = null
    private var phase = SessionPhase.LOADING
    private var error: String? = null
    private var stepKg = 1.0; private var elapsedSec = 0
    private var lastTime: Map<String, String> = emptyMap()
    private val backoffs = HashMap<String, Pair<Double, Explanation>>()
    private val liveSets: List<SetLog> get() = allSets.filter { it.supersededById == null }

    init {
        scope.launch { load() }
        if (tickMillis > 0) scope.launch { while (isActive) { delay(tickMillis); tick() } }
    }

    private suspend fun load() {
        val rec = deps.workouts.getSession(sessionId)
        if (rec == null) { fail("Session not found"); return }
        record = rec
        plan = rec.plan
        profile = deps.profile.get()
        gym = deps.gyms.get(rec.gymId)
        val s = deps.settings.get()
        system = s.unitSystem
        unit = Units.weightUnitFor(system)
        states = deps.progression.getAll()
        val ctx = try { CoreJson.stored.decodeFromString(TodayContext.serializer(), s.todayContextJson) } catch (e: IllegalArgumentException) { null }
        val sameDay = ctx?.takeIf { it.date == rec.date && it.gymId == rec.gymId }
        gym?.let { equipment = SessionEquipment(it, sameDay?.extraItems ?: emptyList(), sameDay?.removedEquipmentIds?.toSet() ?: emptySet(), sameDay?.spotter ?: false) }
        trainingAge = safely { profile?.let { deps.plannerFactory(deps.coordinator.constants()).trainingAgeOf(it, rec.date) } } ?: TrainingAge.INTERMEDIATE
        allSets = deps.workouts.setsForSession(sessionId, true)
        lastTime = SessionRows.lastTimes(rec, unit, deps.clock.zone()) { id -> safely { deps.workouts.setsForExercise(id, 6) } }
        val p = plan
        if (p == null) { fail("Session data incomplete — finish as-is or abandon"); return }
        val doc = try { CoreJson.stored.decodeFromString(SessionDraftDoc.serializer(), s.sessionDraftJson) } catch (e: IllegalArgumentException) { null }
        if (doc != null && doc.sessionId == sessionId) {
            curBlock = doc.currentIndex.coerceIn(0, maxOf(0, p.blocks.size - 1)); curSet = doc.currentSetIndex; draft = doc.draft
            warmupDone = doc.warmupDone; skipped = doc.skippedBlocks.toSet()
            phase = SessionPhase.RECOVERED
        } else {
            warmupDone = liveSets.isNotEmpty() || (p.warmup.drills.isEmpty() && p.warmup.raiseMinutes == 0)
            nextTarget(0)?.let { (b, i) -> curBlock = b; curSet = i }
            prefillDraft()
            phase = if (warmupDone) SessionPhase.WORKING else SessionPhase.WARMUP
        }
        val started = rec.startedAt
        _stale.value = started != null && Duration.between(started, deps.clock.now()).toHours() >= 6
        refreshStep()
        publish()
        deps.workouts.observeSets(sessionId).collect {
            allSets = deps.workouts.setsForSession(sessionId, true)
            publish()
        }
    }

    private fun blocks(): List<ExerciseBlock> = plan?.blocks ?: emptyList()

    private fun nextTarget(from: Int): Pair<Int, Int>? = SetLogging.nextTarget(blocks(), from, skipped, liveSets)

    private fun prefillDraft() {
        val b = blocks().getOrNull(curBlock) ?: return
        val p = b.sets.getOrNull(curSet) ?: return
        draft = SetLogging.prefill(b, p, liveSets.filter { it.exerciseId == b.exerciseId }, unit)
    }

    private suspend fun refreshStep() {
        val b = blocks().getOrNull(curBlock) ?: return
        stepKg = safely { SetLogging.stepKg(deps.knowledge, b, b.sets.getOrNull(curSet)?.loadKg, equipment, profile, trainingAge) } ?: 1.0
    }

    private fun persistDraft() {
        val doc = SessionDraftDoc(sessionId, draft, curBlock, curSet, warmupDone, skipped.toList().sorted())
        scope.launch {
            val json = CoreJson.stored.encodeToString(SessionDraftDoc.serializer(), doc)
            deps.settings.update { it.copy(sessionDraftJson = json) }
        }
    }

    private fun setDraft(d: SetDraft) { draft = d; persistDraft(); publish() }
    fun onLoadMinus() = setDraft(draft.copy(loadValue = SetLogging.bump(draft.loadValue, -SetLogging.stepDisplay(stepKg, unit))))
    fun onLoadPlus() = setDraft(draft.copy(loadValue = SetLogging.bump(draft.loadValue, SetLogging.stepDisplay(stepKg, unit))))
    fun onRepsMinus() = setDraft(draft.copy(reps = SetLogging.bump(draft.reps, -1.0)))
    fun onRepsPlus() = setDraft(draft.copy(reps = SetLogging.bump(draft.reps, 1.0)))
    fun onSecondsMinus() = setDraft(draft.copy(seconds = SetLogging.bump(draft.seconds, -5.0)))
    fun onSecondsPlus() = setDraft(draft.copy(seconds = SetLogging.bump(draft.seconds, 5.0)))
    fun onRir(rir: Int?) = setDraft(draft.copy(rir = rir))
    fun onKeypadCommit(field: KeypadField, text: String) { setDraft(SetLogging.withField(draft, field, text)); openSheet(null) }
    fun onOpenKeypad(field: KeypadField = KeypadField.LOAD) { _keypad.value = field; openSheet(SessionSheet.KEYPAD) }
    fun onOpenPlates() = openSheet(SessionSheet.PLATES)
    fun onUsePlateLoad(kg: Double) { setDraft(draft.copy(loadValue = SetLogging.formatLoad(kg, unit))); openSheet(null) }
    fun openSheet(s: SessionSheet?) { sheet = s; if (s == null && phase == SessionPhase.FINISHING && nextTarget(curBlock) != null) phase = SessionPhase.WORKING; publish() }
    fun onDismissRecovered() { if (phase == SessionPhase.RECOVERED) { phase = if (warmupDone) SessionPhase.WORKING else SessionPhase.WARMUP; publish() } }

    /** Writes exactly the displayed draft values (HR-17, HR-20), starts the rest timer and advances. */
    fun onLogSet() {
        val b = blocks().getOrNull(curBlock) ?: return
        val p = b.sets.getOrNull(curSet) ?: return
        val d = draft
        scope.launch {
            val now = deps.clock.now()
            val set = SetLogging.build(sessionId, b, p, d, now, IdGen.newId())
            deps.workouts.addSet(set)
            allSets = deps.workouts.setsForSession(sessionId, true)
            afterLogged(b, p, set)
        }
    }

    fun onLogRamp(setIndex: Int) {
        val b = blocks().getOrNull(curBlock) ?: return
        val p = b.sets.firstOrNull { it.index == setIndex && SetLogging.isWarmupRamp(b, it) } ?: return
        scope.launch {
            val d = SetLogging.prefill(b, p, emptyList(), unit)
            deps.workouts.addSet(SetLogging.build(sessionId, b, p, d.copy(rir = null), deps.clock.now(), IdGen.newId()))
            allSets = deps.workouts.setsForSession(sessionId, true)
            publish()
        }
    }

    private suspend fun afterLogged(b: ExerciseBlock, p: SetPrescription, set: SetLog) {
        if (p.kind == SetKind.RAMP && SetLogging.isFindLoad(b)) {
            val ok = safely { deps.engineFactory(deps.coordinator.constants()).findLoadDecision(b, set) } ?: false
            _findLoad.value = if (ok) set.weight?.kg else null
        } else if (p.kind == SetKind.WORKING && liveSets.count { it.blockId == b.blockId && it.kind == SetKind.WORKING } == 1) {
            val eq = equipment
            val hit = if (eq == null) null else safely { deps.engineFactory(deps.coordinator.constants()).inSessionBackoff(b, set, GymParams(deps.knowledge, eq)) }
            if (hit != null) backoffs[b.blockId] = hit
        }
        val next = nextTarget(curBlock)
        val sameSuperset = next != null && next.first != curBlock && blocks()[next.first].supersetGroup == b.supersetGroup && b.supersetGroup != null
        if (next != null) { curBlock = next.first; curSet = next.second } else phase = SessionPhase.FINISHING
        rest = if (sameSuperset) null else RestTimerState(deps.clock.now().toEpochMilli() + p.restSeconds * 1000L, p.restSeconds, b.blockId)
        prefillDraft(); refreshStep(); persistDraft(); navChannel.trySend(SessionNav.SetLogged); publish()
    }

    fun onCorrectSet(setId: String) { _correcting.value = allSets.firstOrNull { it.id == setId && it.supersededById == null }; openSheet(SessionSheet.CORRECT_SET) }

    /** Inserts a NEW row that supersedes the old one; the old row stays (struck through, "edited"). */
    fun onSaveCorrection(setId: String, d: SetDraft) {
        val old = allSets.firstOrNull { it.id == setId && it.supersededById == null } ?: return
        scope.launch {
            deps.workouts.supersedeSet(setId, SetLogging.buildCorrection(old, d, deps.clock.now(), IdGen.newId()))
            allSets = deps.workouts.setsForSession(sessionId, true)
            _correcting.value = null
            openSheet(null)
        }
    }

    private suspend fun adjust(kind: AdjustmentKind, rules: List<String>, summary: String, exerciseId: String? = null) =
        deps.workouts.recordAdjustment(Adjustment(id = IdGen.newId(), sessionId = sessionId, exerciseId = exerciseId, kind = kind, ruleIds = rules,
            summary = summary, beforeJson = "{}", afterJson = "{}", source = OverrideSource.USER, createdAt = deps.clock.now()))

    private suspend fun savePlan(newPlan: SessionPlan, summary: String, kind: AdjustmentKind, rules: List<String>) {
        val rec = record ?: return
        plan = newPlan
        record = rec.copy(plan = newPlan)
        deps.workouts.updateSession(rec.copy(plan = newPlan))
        adjust(kind, rules, summary)
        val cur = blocks().getOrNull(curBlock)
        val open = cur?.let { SetLogging.firstUnlogged(it, liveSets) }
        if (open != null) curSet = open else nextTarget(curBlock)?.let { (b, i) -> curBlock = b; curSet = i }
        if (phase == SessionPhase.FINISHING && nextTarget(curBlock) != null) phase = SessionPhase.WORKING
        prefillDraft(); publish()
    }

    fun onAddSet() { val b = blocks().getOrNull(curBlock) ?: return
        scope.launch { val p = plan ?: return@launch; savePlan(PlanEdits.mapBlock(p, b.blockId, PlanEdits::addSet), "Added a set to ${nameOf(b)}", AdjustmentKind.USER, listOf(RuleIds.OVERRIDE)) } }

    fun onRemovePlannedSet() { val b = blocks().getOrNull(curBlock) ?: return
        scope.launch { val p = plan ?: return@launch; savePlan(PlanEdits.mapBlock(p, b.blockId) { PlanEdits.removeLastUnlogged(it, liveSets) }, "Removed a planned set from ${nameOf(b)}", AdjustmentKind.USER, listOf(RuleIds.OVERRIDE)) } }

    fun onSkipExercise() { val b = blocks().getOrNull(curBlock) ?: return
        skipped = skipped + b.blockId
        scope.launch {
            nextTarget(curBlock)?.let { (nb, i) -> curBlock = nb; curSet = i }
            adjust(AdjustmentKind.USER, listOf(RuleIds.OVERRIDE), "Skipped ${nameOf(b)}", b.exerciseId)
            prefillDraft(); refreshStep(); persistDraft(); publish()
        } }

    fun onJumpTo(index: Int) {
        val b = blocks().getOrNull(index) ?: return
        skipped = skipped - b.blockId
        curBlock = index; curSet = SetLogging.firstUnlogged(b, liveSets) ?: 0
        scope.launch { prefillDraft(); refreshStep(); persistDraft(); publish() }
    }

    fun onWarmupDone() { warmupDone = true; if (phase == SessionPhase.WARMUP) phase = SessionPhase.WORKING; persistDraft(); publish() }
    fun onSkipWarmup(reason: String) { onWarmupDone()
        scope.launch { adjust(AdjustmentKind.USER, listOf(RuleIds.WU_01), "Skipped the warm-up: $reason") } }

    /** "Use N kg as your working load?" — never automatic (§4.9.2): converts the remaining RAMP sets to WORKING at that load. */
    fun onFindLoadAccept() { val kg = _findLoad.value ?: return; val p = plan ?: return; val b = blocks().getOrNull(curBlock) ?: return
        _findLoad.value = null
        scope.launch { savePlan(PlanEdits.mapBlock(p, b.blockId) { PlanEdits.retarget(it, liveSets, kg, true) },
            "Working load for ${nameOf(b)} set to ${SetLogging.formatLoad(kg, unit)} ${if (unit == WeightUnit.KG) "kg" else "lb"} (find-load)", AdjustmentKind.USER, listOf(RuleIds.TRV_04)) } }

    /** PROG-04 chip → Adjustment(AUTOREGULATION, USER); the logged set 1 stays as entered. */
    fun onApplyBackoff() { val b = blocks().getOrNull(curBlock) ?: return; val hit = backoffs.remove(b.blockId) ?: return; val p = plan ?: return
        scope.launch { savePlan(PlanEdits.mapBlock(p, b.blockId) { PlanEdits.retarget(it, liveSets, hit.first, false) }, hit.second.message, AdjustmentKind.AUTOREGULATION, listOf(RuleIds.PROG_04)) } }

    fun onDismissBackoff() { blocks().getOrNull(curBlock)?.let { backoffs.remove(it.blockId) }; publish() }

    fun onAddExercise(exerciseId: String) { val ex = deps.knowledge.exercise(exerciseId) ?: return; val p = plan ?: return
        scope.launch {
            val block = PlanEdits.freeBlock(deps.knowledge, ex, profile?.goal ?: Goal.GENERAL_FITNESS, states, record?.gymId ?: "", p.blocks.count { it.blockId.startsWith("free") } + 1, p.blocks.size + 1)
            savePlan(p.copy(blocks = PlanEdits.withOrder(p.blocks + block)), "Added ${ex.name}", AdjustmentKind.USER, listOf(RuleIds.OVERRIDE))
        } }

    fun onSwap() { openSheet(SessionSheet.SWAP); onSwapReason(_swap.value.reason) }

    fun onSwapReason(reason: SwapReason) {
        val b = blocks().getOrNull(curBlock) ?: return
        _swap.value = SwapUiState(loading = true, candidates = emptyList(), reason = reason, error = null)
        scope.launch {
            _swap.value = try {
                SwapLogic.rank(deps, record, gym, equipment, b, states, profile, reason)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                SwapUiState(loading = false, candidates = emptyList(), reason = reason, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun onPickSubstitute(exerciseId: String, reason: SwapReason, scopeChoice: SwapScope, limitation: LimitationRegion? = null) {
        val p = plan ?: return; val b = blocks().getOrNull(curBlock) ?: return; val rec = record ?: return
        val cand = _swap.value.candidates.firstOrNull { it.candidate.exerciseId == exerciseId } ?: return
        scope.launch {
            val newBlock = SwapLogic.makeBlock(deps, b, cand.candidate, rec.gymId, reason, SetLogging.workPrescriptions(b).count { !SetLogging.hasLogged(b, it, liveSets) })
            val blocksNew = PlanEdits.splice(p.blocks, b, newBlock, liveSets)
            val ruleIds = if (reason == SwapReason.PAIN) listOf(RuleIds.INJ_00) else listOf(RuleIds.TRV_04)
            val now = deps.clock.now()
            savePlan(p.copy(blocks = PlanEdits.withOrder(blocksNew)), "Swapped ${nameOf(b)} for ${cand.name} (${reason.name.lowercase()})", AdjustmentKind.EQUIPMENT, ruleIds)
            if (scopeChoice == SwapScope.REST_OF_PROGRAM) {
                deps.plans.addOverride(PlanOverride(id = IdGen.newId(), createdAt = now, source = OverrideSource.USER,
                    mutation = PlanMutation.SwapExercise(b.exerciseId, exerciseId, null, com.gymcoach.core.model.MutationScope.REST_OF_MESOCYCLE, "Swapped in the session (${reason.name.lowercase()}).")))
                safely { deps.coordinator.recompute("swap") }
            }
            if (limitation != null) {
                val lim = com.gymcoach.core.model.Limitation(id = IdGen.newId(), region = limitation, pain0to10 = 4, since = rec.date)
                deps.plans.addOverride(PlanOverride(id = IdGen.newId(), createdAt = now, source = OverrideSource.USER, mutation = PlanMutation.SetLimitation(lim, "Pain during ${nameOf(b)}.")))
            }
            curBlock = blocks().indexOfFirst { it.blockId == newBlock.blockId }.coerceAtLeast(0)
            curSet = SetLogging.firstUnlogged(blocks()[curBlock], liveSets) ?: 0
            prefillDraft(); refreshStep(); persistDraft(); openSheet(null)
        }
    }

    fun onInfo() {
        val b = blocks().getOrNull(curBlock) ?: return
        openSheet(SessionSheet.INFO)
        scope.launch { _info.value = safely { InfoLogic.build(deps, b, equipment, unit, record?.gymId ?: "") } }
    }

    fun sessionEquipment(): SessionEquipment? = equipment
    fun onSomethingWrong() = openSheet(SessionSheet.RED_FLAG)
    fun onFinish() { phase = SessionPhase.FINISHING; sheet = SessionSheet.FINISH; publish() }
    fun onPause() { navChannel.trySend(SessionNav.Back) }
    fun onSaveNote(text: String) { val rec = record ?: return; scope.launch { record = rec.copy(notes = text); deps.workouts.updateSession(rec.copy(notes = text)); openSheet(null) } }
    fun onAbandon() { close(SessionStatus.ABANDONED, null, "") }

    /** Red-flag stop (RED-01): ends the session, records Adjustment(SAFETY, RED-01). */
    fun onRedFlagStop(itemText: String) { scope.launch {
        adjust(AdjustmentKind.SAFETY, listOf(RuleIds.RED_01), "Session stopped: $itemText")
        close(SessionStatus.ABANDONED, null, "") } }

    fun onFinishConfirm(sessionRpe: Int?, note: String, doMobility: Boolean) = close(SessionStatus.COMPLETED, sessionRpe, note, doMobility)

    private fun close(status: SessionStatus, rpe: Int?, note: String, mobility: Boolean = false) {
        val rec = record ?: return
        scope.launch {
            val now = deps.clock.now()
            val minutes = rec.startedAt?.let { Duration.between(it, now).toMinutes().toInt() }
            val done = rec.copy(status = status, finishedAt = now, sessionRpe = rpe, durationMinutes = minutes, notes = if (note.isBlank()) rec.notes else note)
            deps.workouts.updateSession(done); record = done
            if (mobility) adjust(AdjustmentKind.USER, listOf(RuleIds.MOB_02), "Post-session mobility block done")
            deps.settings.update { it.copy(sessionDraftJson = "") }
            if (status == SessionStatus.COMPLETED) {
                try { deps.coordinator.onSessionClosed(sessionId) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
            }
            phase = SessionPhase.DONE; sheet = null; publish()
            navChannel.trySend(if (status == SessionStatus.COMPLETED) SessionNav.Summary(sessionId) else SessionNav.Back)
        }
    }

    fun onRestPlus30() { rest = rest?.let { it.copy(endsAtEpochMs = it.endsAtEpochMs + 30_000L, totalSec = it.totalSec + 30) }; publish() }
    fun onRestSkip() { rest = null; publish() }

    /** One clock tick: elapsed time and rest expiry (called by the ticker; tests call it directly). */
    fun tick() {
        val rec = record ?: return
        val now = deps.clock.now()
        elapsedSec = rec.startedAt?.let { Duration.between(it, now).seconds.toInt().coerceAtLeast(0) } ?: 0
        val r = rest
        if (r != null && now.toEpochMilli() >= r.endsAtEpochMs) { rest = null; navChannel.trySend(SessionNav.RestOver) }
        publish()
    }

    private fun nameOf(b: ExerciseBlock): String = deps.knowledge.exercise(b.exerciseId)?.name ?: b.exerciseId

    private fun fail(message: String) { error = message; phase = SessionPhase.ERROR; publish() }

    private fun publish() {
        val p = plan
        val live = liveSets
        val rows = SessionRows.build(deps.knowledge, p, allSets, curBlock, skipped, lastTime, stepKg, unit)
        val work = p?.blocks?.sumOf { SetLogging.workPrescriptions(it).size } ?: 0
        val done = p?.blocks?.sumOf { b -> SetLogging.workPrescriptions(b).count { SetLogging.hasLogged(b, it, live) } } ?: 0
        val chip = blocks().getOrNull(curBlock)?.let { backoffs[it.blockId] }
        val msg = if (phase == SessionPhase.RECOVERED) "Session restored — ${live.size} sets logged" + (live.maxByOrNull { it.loggedAt }?.let { ", last at " + it.loggedAt.atZone(deps.clock.zone()).toLocalTime().withSecond(0).withNano(0) } ?: "")
        else "$done of $work sets"
        _state.value = SessionUiState(phase, record, p, gym?.name ?: "", elapsedSec, msg, warmupDone, rows, curBlock, curSet, draft, stepKg, system, rest,
            p?.modifiers ?: emptyList(), chip, sheet, error)
    }

    private suspend fun <T> safely(f: suspend () -> T): T? =
        try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}
