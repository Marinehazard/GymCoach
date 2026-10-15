package com.gymcoach.app.ui.session

import com.gymcoach.app.ui.today.ScopedViewModel
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.app.ui.today.uiDispatcher
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.VolumeSnapshot
import com.gymcoach.core.volume.VolumeAccountant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** §8.9 state. [loading] is true until the first load; [record] null means the session id is unknown. */
data class SessionSummaryUiState(
    val record: SessionRecord?, val sets: List<SetLog>, val volume: VolumeSnapshot, val adjustments: List<Adjustment>,
    val progression: List<Explanation>, val e1rmChanges: List<Pair<String, Double>>, val durationMin: Int, val sessionRpe: Int?,
    val loading: Boolean = true, val error: String? = null,
)

class SessionSummaryViewModel(
    private val sessionId: String,
    private val deps: TrainingDeps,
    dispatcher: CoroutineDispatcher = uiDispatcher(),
) : ScopedViewModel(dispatcher) {
    private val _state = MutableStateFlow(SessionSummaryUiState(null, emptyList(), VolumeSnapshot(emptyMap(), emptyMap()), emptyList(), emptyList(), emptyList(), 0, null))
    val state: StateFlow<SessionSummaryUiState> = _state

    init { scope.launch { load() } }

    fun reload() { scope.launch { load() } }

    private suspend fun load() {
        try {
            val record = deps.workouts.getSession(sessionId)
            if (record == null) { _state.value = _state.value.copy(loading = false, error = "Session not found"); return }
            val sets = deps.workouts.setsForSession(sessionId)
            val adjustments = deps.workouts.adjustmentsForSession(sessionId)
            val progression = adjustments.filter { it.kind == AdjustmentKind.PROGRESSION && it.undoneAt == null }
                .map { Explanation(it.ruleIds, it.summary, it.inputs) }
            val keys = record.plan?.blocks?.map { it.progressionKey.id }?.toSet() ?: emptySet()
            val changes = deps.progression.getAll().filter { it.key.id in keys }.mapNotNull { st ->
                val now = st.history.getOrNull(0)?.e1rm?.kg
                val before = st.history.getOrNull(1)?.e1rm?.kg
                if (now == null || before == null) null
                else (deps.knowledge.exercise(st.key.exerciseId)?.name ?: st.key.exerciseId) to Math.round((now - before) * 10.0) / 10.0
            }
            val started = record.startedAt
            val finished = record.finishedAt
            val minutes = record.durationMinutes ?: if (started != null && finished != null) java.time.Duration.between(started, finished).toMinutes().toInt() else 0
            _state.value = SessionSummaryUiState(record, sets, VolumeAccountant.ofSets(sets, deps.knowledge), adjustments, progression, changes, minutes, record.sessionRpe, loading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /** Σ kg × reps over the sets that count as volume (WORKING / AMRAP / CALIBRATION / DROP). */
        fun tonnageKg(sets: List<SetLog>): Double = sets.filter { it.kind != SetKind.RAMP && it.supersededById == null }
            .sumOf { (it.weight?.kg ?: 0.0) * (it.reps ?: 0) * (if (it.perHand) 2.0 else 1.0) }

        fun workingSetCount(sets: List<SetLog>): Int = sets.count { it.kind != SetKind.RAMP && it.supersededById == null }
    }
}
