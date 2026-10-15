package com.gymcoach.app.ui.plan

import com.gymcoach.app.ui.today.ScopedViewModel
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.app.ui.today.uiDispatcher
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.ProgressionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AdjustmentLogUiState(val items: List<Adjustment>, val loading: Boolean, val error: String?)

/** POL-01: every automatic or confirmed change, newest first, with Undo where the change is reversible (override revoked / prior progression state restored). */
class AdjustmentLogViewModel(
    private val deps: TrainingDeps,
    private val limit: Int = 200,
    dispatcher: CoroutineDispatcher = uiDispatcher(),
) : ScopedViewModel(dispatcher) {
    private val _state = MutableStateFlow(AdjustmentLogUiState(emptyList(), true, null))
    val state: StateFlow<AdjustmentLogUiState> = _state

    init {
        scope.launch { deps.workouts.observeAdjustments(limit).collectLatest { _state.value = AdjustmentLogUiState(it.sortedByDescending { a -> a.createdAt }, false, _state.value.error) } }
    }

    fun beforeState(a: Adjustment): ProgressionState? =
        if (a.beforeJson.length < 3) null else try { CoreJson.stored.decodeFromString(ProgressionState.serializer(), a.beforeJson) } catch (e: IllegalArgumentException) { null }

    fun canUndo(a: Adjustment): Boolean = a.undoneAt == null && (a.overrideId != null || beforeState(a) != null)

    fun onUndo(a: Adjustment) {
        if (!canUndo(a)) return
        scope.launch {
            try {
                val now = deps.clock.now()
                a.overrideId?.let { deps.plans.revokeOverride(it, now) }
                beforeState(a)?.let { deps.progression.save(it) }
                deps.workouts.undoAdjustment(a.id, now)
                if (a.overrideId != null) deps.coordinator.recompute("undo")
                _state.value = _state.value.copy(error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
