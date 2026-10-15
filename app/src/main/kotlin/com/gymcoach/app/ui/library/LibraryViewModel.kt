package com.gymcoach.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.core.equipment.Availability
import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.ChartSeries
import com.gymcoach.core.model.ContentStatus
import com.gymcoach.core.model.Difficulty
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.ExerciseContent
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LoadSource
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetPrescription
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.ProgressionRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.util.IdGen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Filter keys of `LibraryUiState.flagFilters` (ExerciseFlags must be false). */
object LibraryFlags {
    const val NO_SPINAL = "no_spinal_loading"
    const val KNEE_FRIENDLY = "knee_friendly"
    const val SHOULDER_FRIENDLY = "shoulder_friendly"
    const val BACK_FRIENDLY = "low_back_friendly"
    const val NO_SPOTTER = "no_spotter_needed"
    val ALL: List<String> = listOf(NO_SPINAL, KNEE_FRIENDLY, SHOULDER_FRIENDLY, BACK_FRIENDLY, NO_SPOTTER)
}

data class LibraryRow(val exercise: Exercise, val availableInGym: Boolean)

data class LibraryUiState(
    val query: String,
    val pattern: MovementPattern?,
    val muscle: Muscle?,
    val gymId: String?,
    val availableOnly: Boolean,
    val difficulty: Difficulty?,
    val flagFilters: Set<String>,
    val results: List<LibraryRow>,
)

/** §8.12 library: names + EN/DE aliases, filter chips, availability against the active (else default) gym. */
class LibraryViewModel(
    private val knowledge: KnowledgeBase,
    private val gyms: GymRepository,
    private val settings: SettingsRepository,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val resolver = EquipmentResolver(knowledge)
    private var gym: GymSet? = null
    private val _state = MutableStateFlow(LibraryUiState("", null, null, null, false, null, emptySet(), emptyList()))
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** Gym name for the "available in [gym]" chip. */
    val gymName: String? get() = gym?.name

    init {
        scope.launch {
            val all = gyms.getAll()
            val active = settings.get().activeGymId
            gym = all.firstOrNull { it.id == active } ?: all.firstOrNull { it.isDefault } ?: all.firstOrNull()
            _state.update { refresh(it.copy(gymId = gym?.id)) }
        }
        _state.update { refresh(it) }
    }

    fun onQuery(q: String) = _state.update { refresh(it.copy(query = q)) }
    fun onPattern(p: MovementPattern?) = _state.update { refresh(it.copy(pattern = p)) }
    fun onMuscle(m: Muscle?) = _state.update { refresh(it.copy(muscle = m)) }
    fun onAvailableOnly(v: Boolean) = _state.update { refresh(it.copy(availableOnly = v)) }
    fun onDifficulty(d: Difficulty?) = _state.update { refresh(it.copy(difficulty = d)) }
    fun onFlag(flag: String) = _state.update { refresh(it.copy(flagFilters = if (flag in it.flagFilters) it.flagFilters - flag else it.flagFilters + flag)) }
    fun onClearFilters() = _state.update { refresh(it.copy(query = "", pattern = null, muscle = null, availableOnly = false, difficulty = null, flagFilters = emptySet())) }

    private fun refresh(s: LibraryUiState): LibraryUiState {
        val availableIds: Set<String>? = gym?.let { resolver.availableExerciseIds(SessionEquipment(it)) }
        val rows = knowledge.searchByNameOrAlias(s.query)
            .filter { s.pattern == null || it.pattern == s.pattern }
            .filter { s.muscle == null || it.muscles.any { m -> m.muscle == s.muscle && m.weight >= 0.5 } }
            .filter { s.difficulty == null || it.difficulty == s.difficulty }
            .filter { e -> s.flagFilters.all { passesFlag(e, it) } }
            .map { LibraryRow(it, availableIds?.contains(it.id) ?: false) }
            .filter { !s.availableOnly || it.availableInGym }
        return s.copy(results = rows)
    }

    private fun passesFlag(e: Exercise, flag: String): Boolean = when (flag) {
        LibraryFlags.NO_SPINAL -> !e.flags.spinalLoading
        LibraryFlags.KNEE_FRIENDLY -> !e.flags.kneeSensitive
        LibraryFlags.SHOULDER_FRIENDLY -> !e.flags.shoulderSensitive
        LibraryFlags.BACK_FRIENDLY -> !e.flags.lowBackSensitive
        LibraryFlags.NO_SPOTTER -> !e.flags.requiresSpotterOrSafeties
        else -> true
    }
}

data class ExerciseDetailUiState(
    val exercise: Exercise?,
    val availableInGym: Boolean,
    val missing: List<String>,
    val history: ChartSeries?,
    val content: ExerciseContent?,
    val contentStatus: ContentStatus,
    val state: ProgressionState?,
    val canAddToday: Boolean,
    val error: String?,
)

/** §8.12 detail: content overlay (or "not written yet"), availability, e1RM chart, "Add to today's session". */
class ExerciseDetailViewModel(
    private val exerciseId: String,
    private val knowledge: KnowledgeBase,
    private val gyms: GymRepository,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val progression: ProgressionRepository,
    private val workouts: WorkoutRepository,
    private val clock: Clock,
    private val e1rmSeries: (List<ProgressionState>, String) -> ChartSeries,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val exercise: Exercise? = knowledge.exercise(exerciseId)
    private var openSession: SessionRecord? = null
    private var availability: Availability? = null

    private val _state = MutableStateFlow(
        ExerciseDetailUiState(exercise, false, emptyList(), null, exercise?.content, exercise?.contentStatus ?: ContentStatus.TODO, null, false, if (exercise == null) ERROR_UNKNOWN else null),
    )
    val state: StateFlow<ExerciseDetailUiState> = _state.asStateFlow()

    init {
        if (exercise != null) scope.launch { load(exercise) }
    }

    private suspend fun load(e: Exercise) {
        try {
            val all = gyms.getAll()
            val active = settings.get().activeGymId
            val gym = all.firstOrNull { it.id == active } ?: all.firstOrNull { it.isDefault } ?: all.firstOrNull()
            val avail = gym?.let { EquipmentResolver(knowledge).resolve(e, SessionEquipment(it)) }
            availability = avail
            val states = progression.getForExercise(e.id)
            val chosen = states.firstOrNull { it.key.gymScope == gym?.id } ?: states.firstOrNull()
            val missing = avail?.missing?.map { id -> knowledge.equipment[id]?.name ?: id } ?: emptyList()
            openSession = workouts.observeOpenSession().first()
            val inPlan = openSession?.plan?.blocks?.any { it.exerciseId == e.id } ?: false
            _state.update {
                it.copy(
                    availableInGym = avail?.available ?: false,
                    missing = missing,
                    history = if (states.isEmpty()) null else e1rmSeries(states, e.id),
                    state = chosen,
                    canAddToday = (avail?.available ?: false) && openSession?.plan != null && !inPlan,
                )
            }
        } catch (ex: Exception) {
            _state.update { it.copy(error = ex.message ?: ERROR_LOAD) }
        }
    }

    /** Inserts a block with 2 sets (default profile prescription) into the open session's frozen plan + an Adjustment(USER). */
    fun onAddToday() {
        val e = exercise ?: return
        val record = openSession ?: return
        val plan = record.plan ?: return
        val avail = availability ?: return
        scope.launch {
            try {
                val goal = profiles.get()?.goal ?: Goal.HYPERTROPHY
                val prescription = knowledge.prescription(e, goal)
                val st = _state.value.state
                val loadType = avail.effectiveLoadType ?: e.loadType
                val freeCount = plan.blocks.count { it.slotId.startsWith("free") }
                val slotId = "free" + (freeCount + 1)
                val sets = (1..2).map { i ->
                    SetPrescription(
                        index = i, kind = SetKind.WORKING, reps = prescription.range, loadKg = st?.workingLoadKg, rir = prescription.rir.hi,
                        restSeconds = DEFAULT_REST_SECONDS, loadSource = if (st?.workingLoadKg != null) LoadSource.PROGRESSION_STATE else LoadSource.NONE,
                    )
                }
                val block = ExerciseBlock(
                    blockId = slotId + ":" + e.id, slotId = slotId, exerciseId = e.id, optionIndex = avail.optionIndex ?: 0,
                    effectiveLoadType = loadType, slotRole = SlotRole.ACCESSORY, order = plan.blocks.size, sets = sets,
                    progressionKey = ProgressionKey.forExercise(e.id, loadType, plan.gymId),
                    explanation = Explanation(listOf(RuleIds.POL_01), "Added by you from the library: 2 sets, default profile."),
                )
                workouts.updateSession(record.copy(plan = plan.copy(blocks = plan.blocks + block)))
                workouts.recordAdjustment(
                    Adjustment(
                        id = IdGen.newId(), sessionId = record.id, exerciseId = e.id, kind = AdjustmentKind.USER, ruleIds = listOf(RuleIds.POL_01),
                        summary = "Added ${e.name} (2 sets) to today's session", beforeJson = "{}", afterJson = "{\"block_id\":\"${block.blockId}\"}",
                        source = OverrideSource.USER, createdAt = clock.now(),
                    ),
                )
                _state.update { it.copy(canAddToday = false) }
            } catch (ex: Exception) {
                _state.update { it.copy(error = ex.message ?: ERROR_LOAD) }
            }
        }
    }

    companion object {
        const val DEFAULT_REST_SECONDS = 90
        const val ERROR_UNKNOWN = "Unknown exercise."
        const val ERROR_LOAD = "Could not load this exercise."
    }
}
