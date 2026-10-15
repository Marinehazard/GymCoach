package com.gymcoach.app.ui.plan

import com.gymcoach.app.ui.today.ScopedViewModel
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.app.ui.today.uiDispatcher
import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MuscleBandRow
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.mutation.OverrideResolver
import com.gymcoach.core.util.IdGen
import com.gymcoach.core.volume.MuscleTiers
import com.gymcoach.core.volume.WeeklyLedger
import com.gymcoach.core.mutation.ValidationResult
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rows are the §2.14 MuscleBandRow produced by WeeklyLedger.rows(tiers, kb); WP-07-owned UI type (§8.10). */
data class WeeklyLedgerView(val rows: List<MuscleBandRow>, val carryover: Map<Muscle, Double>, val showDirect: Boolean)

data class PlanUiState(
    val mesocycle: Mesocycle?, val currentWeek: MesoWeek?, val weekIndex: Int, val ledger: WeeklyLedgerView,
    val overrides: List<PlanOverride>, val underTarget: List<Explanation>, val proposals: List<PlanMutation>, val loading: Boolean, val error: String?,
)

/** Per-exercise extras for the session screens: current working loads and the alternates available in the plan gym. */
data class PlanExtras(val states: List<ProgressionState>, val alternates: Map<String, List<Exercise>>, val gym: GymSet?)

class PlanViewModel(
    private val deps: TrainingDeps,
    dispatcher: CoroutineDispatcher = uiDispatcher(),
) : ScopedViewModel(dispatcher) {
    private val _state = MutableStateFlow(
        PlanUiState(null, null, 0, WeeklyLedgerView(emptyList(), emptyMap(), false), emptyList(), emptyList(), emptyList(), true, null),
    )
    val state: StateFlow<PlanUiState> = _state
    private val _extras = MutableStateFlow(PlanExtras(emptyList(), emptyMap(), null))
    val extras: StateFlow<PlanExtras> = _extras
    private val navChannel = Channel<String>(Channel.BUFFERED)
    /** Planned-session ids to open (`PlanSessionRoute`). */
    val openSession: Flow<String> = navChannel.receiveAsFlow()

    private val tick = Channel<Unit>(Channel.CONFLATED)
    private var selectedWeek: Int? = null
    private var showDirect = false
    private var error: String? = null
    private val dismissed = HashSet<PlanMutation>()

    init {
        scope.launch {
            merge(tick.receiveAsFlow(), deps.plans.observeCurrent().map { }, deps.plans.observeOverrides().map { }).conflate().collectLatest { safeReload() }
        }
        tick.trySend(Unit)
    }

    fun onWeekSelect(index: Int) { selectedWeek = index; tick.trySend(Unit) }
    fun onToggleDirect() { showDirect = !showDirect; tick.trySend(Unit) }
    fun onOpenSession(id: String) { navChannel.trySend(id) }
    fun refresh() { tick.trySend(Unit) }

    /** Regenerate keeps progression states and logs; on failure the previous plan stays and an error card shows (§8.10). */
    fun onRegenerate() = act { deps.coordinator.recompute("regenerate") }

    fun onRevokeOverride(id: String) = act {
        val now = deps.clock.now()
        deps.plans.revokeOverride(id, now)
        deps.coordinator.recompute("revoke_override")
        deps.workouts.recordAdjustment(adjustment("Revoked a plan change", listOf(RuleIds.OVERRIDE), OverrideSource.USER, overrideId = id))
    }

    fun onProposal(mutation: PlanMutation, accept: Boolean) {
        if (!accept) { dismissed.add(mutation); tick.trySend(Unit); return }
        applyMutation(mutation, OverrideSource.PLANNER)
    }

    /** Slot overflow actions (swap for the rest of the program, sets ±, rep-range zone, exclude): each validated, stored as an override with an Adjustment. */
    fun applyMutation(mutation: PlanMutation, source: OverrideSource = OverrideSource.USER) = act {
        val c = deps.coordinator.constants()
        val verdict = deps.validatorFactory(c).validate(mutation, deps.coordinator.buildInputs(), deps.plans.getCurrent(), null)
        if (verdict is ValidationResult.Invalid) throw IllegalArgumentException(verdict.errors.joinToString("; "))
        val now = deps.clock.now()
        val override = PlanOverride(id = IdGen.newId(), createdAt = now, source = source, mutation = mutation)
        deps.plans.addOverride(override)
        deps.coordinator.recompute("plan_edit")
        deps.workouts.recordAdjustment(adjustment(mutation.rationale, listOf(RuleIds.OVERRIDE), source, overrideId = override.id))
    }

    fun onSwapForRestOfProgram(fromExerciseId: String, toExerciseId: String, templateId: String?) = applyMutation(
        PlanMutation.SwapExercise(fromExerciseId, toExerciseId, templateId, MutationScope.REST_OF_MESOCYCLE, "Swapped in the plan."),
    )
    fun onExclude(exerciseId: String) = applyMutation(PlanMutation.ExcludeExercise(exerciseId, null, "Excluded from the plan."))
    fun onRepRange(exerciseId: String, lo: Int, hi: Int) = applyMutation(PlanMutation.ChangeRepRange(exerciseId, lo, hi, MutationScope.REST_OF_MESOCYCLE, "Rep range changed in the plan."))
    fun onSetsDelta(muscle: Muscle, delta: Int) = applyMutation(PlanMutation.ChangeVolume(muscle, delta, MutationScope.REST_OF_MESOCYCLE, "Weekly sets for ${muscle.name.lowercase().replace('_', ' ')} changed in the plan."))

    private fun adjustment(summary: String, rules: List<String>, source: OverrideSource, overrideId: String? = null) = Adjustment(
        id = IdGen.newId(), kind = AdjustmentKind.OVERRIDE, ruleIds = rules, summary = summary, beforeJson = "{}", afterJson = "{}",
        source = source, overrideId = overrideId, createdAt = deps.clock.now(),
    )

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            error = try { block(); null } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            tick.trySend(Unit)
        }
    }

    private suspend fun safeReload() {
        try { reload() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun reload() {
        val today = deps.clock.today()
        val meso = deps.plans.getCurrent()
        val overrides = deps.plans.overrides()
        val profile = deps.profile.get()
        if (meso == null || profile == null) {
            _state.value = _state.value.copy(mesocycle = meso, currentWeek = null, overrides = overrides, loading = false, error = error)
            return
        }
        val current = meso.weeks.firstOrNull { !today.isBefore(it.startDate) && today.isBefore(it.startDate.plusDays(7)) }
            ?: meso.weeks.lastOrNull { !it.startDate.isAfter(today) } ?: meso.weeks.firstOrNull()
        val week = meso.weeks.firstOrNull { it.index == (selectedWeek ?: current?.index) } ?: current
        val states = deps.progression.getAll()
        val gym = deps.gyms.get(meso.gymId)
        val ledger = week?.let { w -> safely { withContext(deps.computeDispatcher) { WeeklyLedger.build(w, weekSets(w), weekSessions(w), null, deps.knowledge) } } }
        val rows = ledger?.rows(MuscleTiers.assign(profile.priorityMuscles), deps.knowledge) ?: emptyList()
        val proposals = (safely { deps.coordinator.plannerProposals(today) } ?: emptyList()).filter { it !in dismissed }
        _extras.value = PlanExtras(states, alternates(meso, gym), gym)
        _state.value = PlanUiState(
            mesocycle = meso, currentWeek = week, weekIndex = week?.index ?: 0,
            ledger = WeeklyLedgerView(rows, ledger?.carryover ?: emptyMap(), showDirect),
            overrides = OverrideResolver.active(overrides, today), underTarget = meso.underTargetReport, proposals = proposals, loading = false, error = error,
        )
    }

    private suspend fun weekSessions(week: MesoWeek) = deps.workouts.sessionsBetween(week.startDate, week.startDate.plusDays(6))
    private suspend fun weekSets(week: MesoWeek) = deps.workouts.setsForSessions(weekSessions(week).map { it.id }).filter { it.supersededById == null }

    /** exerciseId -> the alternates listed in the knowledge base that this gym can do (rest-of-program swap candidates). */
    private fun alternates(meso: Mesocycle, gym: GymSet?): Map<String, List<Exercise>> {
        if (gym == null) return emptyMap()
        val resolver = EquipmentResolver(deps.knowledge)
        val eq = SessionEquipment(gym)
        val ids = meso.weeks.flatMap { w -> w.sessions.flatMap { s -> s.slots.map { it.exerciseId } } }.distinct()
        return ids.associateWith { id ->
            (deps.knowledge.exercise(id)?.substitutions ?: emptyList()).mapNotNull { deps.knowledge.exercise(it) }.filter { resolver.resolve(it, eq).available }.take(5)
        }
    }

    private suspend fun <T> safely(f: suspend () -> T): T? =
        try { f() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    companion object {
        fun weekLabel(meso: Mesocycle, week: MesoWeek): String = "week ${week.index} of ${meso.weeks.size}" +
            (meso.deloadWeekIndex?.let { " · deload week $it" } ?: "") + " · mode ${week.mode.name.lowercase().replace('_', ' ')}"
        fun date(week: MesoWeek, weekday: Int): LocalDate = week.startDate.plusDays((weekday - 1).toLong())
    }
}
