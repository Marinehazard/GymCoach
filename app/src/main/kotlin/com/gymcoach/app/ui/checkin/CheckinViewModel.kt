package com.gymcoach.app.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.core.checkin.CheckinBank
import com.gymcoach.core.checkin.CheckinEvaluator
import com.gymcoach.core.model.CheckinAnswer
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.repo.MetricsRepository
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.util.IdGen
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** §8.15 state. `triggered` are the CheckinEvaluator actions shown as cards after Done. */
data class CheckinUiState(
    val weekStart: LocalDate,
    val questions: List<CheckinQuestion>,
    val answers: Map<String, CheckinAnswer>,
    val autoAnswered: Set<String>,
    val page: Int,
    val triggered: List<Explanation>,
    val done: Boolean,
)

/** Extra state: loading, errors and the proposals the evaluator created (each with OK / Not this week). */
data class CheckinExtras(
    val loading: Boolean,
    val saving: Boolean,
    val error: String?,
    val proposals: List<PlanMutation>,
    val nextCheckinWeekday: Int,
    val alreadyDone: Boolean,
)

class CheckinViewModel(
    private val metrics: MetricsRepository,
    private val workouts: WorkoutRepository,
    private val plans: PlanRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val bank: CheckinBank,
    private val evaluator: (PlannerConstants) -> CheckinEvaluator,
    private val constants: suspend () -> PlannerConstants,
    private val recompute: suspend (String) -> Mesocycle,
    private val clock: Clock,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val today = clock.today()
    private var history: List<WeeklyCheckin> = emptyList()
    private var inDeficit = false

    private val _state = MutableStateFlow(CheckinUiState(today, emptyList(), emptyMap(), emptySet(), 0, emptyList(), false))
    val state: StateFlow<CheckinUiState> = _state.asStateFlow()
    private val _extras = MutableStateFlow(CheckinExtras(true, false, null, emptyList(), 7, false))
    val extras: StateFlow<CheckinExtras> = _extras.asStateFlow()

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        try {
            val s = settings.get()
            val weekStart = Weeks.weekStart(today, s.weekStartsOnMonday)
            val profile = profiles.get()
            history = metrics.latestWeeklyCheckins(8).filter { it.weekStart != weekStart }
            val existing = metrics.weeklyCheckinFor(weekStart)
            if (existing?.completedAt != null) {
                _state.value = CheckinUiState(weekStart, emptyList(), existing.answers.associateBy { it.questionId }, existing.autoAnswered.toSet(), 0, existing.triggered, true)
                _extras.value = CheckinExtras(false, false, null, emptyList(), s.weeklyCheckinWeekday, true)
                return
            }
            inDeficit = profile?.goal == Goal.FAT_LOSS || plans.nutritionState()?.deficitStartedOn != null
            val overrides = plans.overrides().filter { it.revokedAt == null }
            val nextWeek = weekStart.plusDays(7)
            val travelNext = overrides.any { o ->
                val m = o.mutation
                m is PlanMutation.SetTravelDays && !m.from.isAfter(nextWeek.plusDays(6)) && !m.to.isBefore(nextWeek)
            }
            val custom = metrics.activeQuestions(today)
            val selected = bank.select(weekStart, history, inDeficit, travelNext, custom)
            val sessions = workouts.sessionsBetween(weekStart, weekStart.plusDays(6))
            val bodyMetrics = metrics.bodyMetricsBetween(weekStart.minusDays(14), weekStart.plusDays(6))
            val planned = plans.getCurrent()?.weeks?.firstOrNull { it.startDate == weekStart }?.sessions?.size ?: profile?.daysPerWeek ?: 3
            val auto = bank.autoAnswers(weekStart, bodyMetrics, sessions, planned)
            val answers = auto.filterKeys { id -> selected.any { it.id == id } }
            _state.value = CheckinUiState(weekStart, selected, answers, answers.keys, 0, emptyList(), false)
            _extras.value = CheckinExtras(false, false, null, emptyList(), s.weeklyCheckinWeekday, false)
        } catch (e: Exception) {
            _extras.update { it.copy(loading = false, error = e.message ?: ERROR_LOAD) }
        }
    }

    fun onAnswer(answer: CheckinAnswer) {
        _state.update { it.copy(answers = it.answers + (answer.questionId to answer), autoAnswered = it.autoAnswered - answer.questionId) }
    }

    fun onNext() = _state.update { it.copy(page = (it.page + 1).coerceAtMost((it.questions.size - 1).coerceAtLeast(0))) }
    fun onBack() = _state.update { it.copy(page = (it.page - 1).coerceAtLeast(0)) }

    /** Saves the check-in (holds from the outcome), asks the evaluator, recomputes, shows the cards. */
    fun onDone() {
        val s = _state.value
        if (_extras.value.saving || s.done) return
        scope.launch {
            _extras.update { it.copy(saving = true, error = null) }
            try {
                val now = clock.now()
                val answers = s.questions.mapNotNull { s.answers[it.id] }
                val base = WeeklyCheckin(
                    id = IdGen.newId(), weekStart = s.weekStart, answers = answers, autoAnswered = s.autoAnswered.toList(), completedAt = now,
                )
                val outcome = evaluator(constants()).evaluate(base, history, inDeficit)
                val stored = base.copy(triggered = outcome.triggered, holds = outcome.holds.sorted())
                metrics.saveWeeklyCheckin(stored)
                for (q in s.questions.filter { it.id.startsWith("C-") }) {
                    val a = s.answers[q.id] ?: continue
                    metrics.saveQuestion(q.copy(answered = true, answer = a.number?.toString() ?: a.yes?.toString() ?: a.text, answeredAt = now))
                }
                recompute("checkin")
                _state.update { it.copy(triggered = outcome.triggered, done = true) }
                _extras.update { it.copy(saving = false, proposals = outcome.proposals) }
            } catch (e: Exception) {
                _extras.update { it.copy(saving = false, error = e.message ?: ERROR_SAVE) }
            }
        }
    }

    /** "OK" writes a PLANNER-source override and recomputes; "Not this week" just dismisses the card. */
    fun onProposal(mutation: PlanMutation, accept: Boolean) {
        scope.launch {
            try {
                if (accept) {
                    plans.addOverride(PlanOverride(id = IdGen.newId(), createdAt = clock.now(), source = OverrideSource.PLANNER, mutation = mutation))
                    recompute("checkin")
                }
                _extras.update { it.copy(proposals = it.proposals - mutation) }
            } catch (e: Exception) {
                _extras.update { it.copy(error = e.message ?: ERROR_SAVE) }
            }
        }
    }

    companion object {
        const val ERROR_LOAD = "Could not prepare the check-in."
        const val ERROR_SAVE = "Could not save the check-in."
    }
}
