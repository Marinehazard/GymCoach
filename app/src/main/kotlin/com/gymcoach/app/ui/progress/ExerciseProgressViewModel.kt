package com.gymcoach.app.ui.progress

import com.gymcoach.app.ui.today.ScopedViewModel
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.app.ui.today.uiDispatcher
import com.gymcoach.core.model.ChartSeries
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.progression.E1rm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** §8.11. [exercise] is null only for an id unknown to this catalogue version (the screen then shows "Exercise unavailable"). */
data class ExerciseProgressUiState(
    val exercise: Exercise?, val series: ChartSeries, val loadXReps: ChartSeries?, val bestSets: List<SetLog>, val state: ProgressionState?,
    val gymScopes: List<String>, val loading: Boolean, val error: String?, val range: Int = 180, val selectedScope: String? = null,
)

class ExerciseProgressViewModel(
    private val exerciseId: String,
    private val deps: TrainingDeps,
    dispatcher: CoroutineDispatcher = uiDispatcher(),
) : ScopedViewModel(dispatcher) {
    private val empty = ChartSeries("", emptyList())
    private val _state = MutableStateFlow(ExerciseProgressUiState(null, empty, null, emptyList(), null, emptyList(), true, null))
    val state: StateFlow<ExerciseProgressUiState> = _state

    init { scope.launch { load(180, null) } }

    fun onGymScope(id: String?) { scope.launch { load(_state.value.range, id) } }
    fun onRange(days: Int) { scope.launch { load(days, _state.value.selectedScope) } }

    private suspend fun load(days: Int, scopeId: String?) {
        try {
            val exercise = deps.knowledge.exercise(exerciseId)
            val states = deps.progression.getForExercise(exerciseId)
            val scopes = states.mapNotNull { it.key.gymScope }.distinct()
            val shown = scopeId?.let { id -> states.filter { it.key.gymScope == id } } ?: states
            val state = shown.maxByOrNull { it.updatedAt }
            val cutoff = deps.clock.today().minusDays(days.toLong()).toEpochDay()
            val e1rm = deps.progressSeries.e1rm(shown, exerciseId)
            val series = e1rm.copy(points = e1rm.points.filter { it.dateEpochDay >= cutoff })
            val lx = if (state != null && (scopeId != null || scopes.isNotEmpty())) deps.progressSeries.loadXReps(state).let { s -> s.copy(points = s.points.filter { it.dateEpochDay >= cutoff }) } else null
            val sets = deps.workouts.setsForExercise(exerciseId, 20).filter { it.supersededById == null && it.kind != SetKind.RAMP }
            val best = sets.groupBy { it.sessionId }.values.mapNotNull { s -> s.maxByOrNull { score(it) } }.sortedByDescending { it.loggedAt }.take(5)
            _state.value = ExerciseProgressUiState(exercise, series, lx, best, state, scopes, false, null, days, scopeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun score(s: SetLog): Double {
        val w = s.weight?.kg ?: 0.0
        val r = s.reps ?: 0
        return E1rm.estimate(w, r, s.rir, java.time.LocalDate.ofEpochDay(0))?.kg ?: (w * r)
    }
}
