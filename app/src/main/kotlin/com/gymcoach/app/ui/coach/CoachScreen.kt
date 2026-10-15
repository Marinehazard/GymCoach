package com.gymcoach.app.ui.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.BackendSettingsRoute
import com.gymcoach.app.ui.nav.ConversationRoute
import com.gymcoach.core.coach.CoachTurnInputs
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.EnergyModel
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.volume.WeeklyLedger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** §8.17 conversation list state. */
data class CoachListUiState(
    val conversations: List<ChatConversation>,
    val messageCounts: Map<String, Int>,
    val spendByConversation: Map<String, Double>,
    val loading: Boolean,
    val error: String?,
)

class CoachListViewModel(private val container: AppContainer, scopeOverride: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(CoachListUiState(emptyList(), emptyMap(), emptyMap(), loading = true, error = null))
    val state: StateFlow<CoachListUiState> = _state.asStateFlow()
    private val _noBackend = MutableStateFlow(false)
    val noBackend: StateFlow<Boolean> = _noBackend.asStateFlow()

    fun refresh() {
        scope.launch {
            try {
                val coach = container.coachRepository
                val list = coach.listConversations().sortedByDescending { it.startedAt }
                val counts = list.associate { c -> c.id to coach.messages(c.id).count { it.displayText.isNotEmpty() && (it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT) } }
                val spend = coach.spendSince(Instant.EPOCH).groupBy { it.conversationId }.mapValues { (_, rs) -> rs.sumOf { it.costUsd } }
                _noBackend.value = container.settingsRepository.get().backendMode == BackendMode.NONE
                _state.value = CoachListUiState(list, counts, spend, loading = false, error = null)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Could not load the conversations.") }
            }
        }
    }

    fun onNew(onCreated: (String) -> Unit) {
        scope.launch {
            val created = startNewConversation(container.coachRepository, container.settingsRepository, container.knowledge, container.clock)
            onCreated(created.id)
        }
    }
}

/** §8.17 Coach tab: conversation list, "New conversation"; no backend -> "Set up the coach". */
@Composable
fun CoachScreen(container: AppContainer, navController: NavHostController) {
    val vm: CoachListViewModel = viewModel(factory = vmFactory { CoachListViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val noBackend by vm.noBackend.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    LaunchedEffect(Unit) { vm.refresh() }

    SetupScaffold(title = stringResource(R.string.coach_title), onBack = null) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            Column {
                state.error?.let { ErrorBanner(stringResource(R.string.coach_list_error), it, vm::refresh, null) }
                when {
                    state.loading -> LoadingBox()
                    noBackend -> EmptyState(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.coach_setup_title), stringResource(R.string.coach_setup_body), stringResource(R.string.coach_setup_action)) { navController.navigate(BackendSettingsRoute) }
                    state.conversations.isEmpty() -> EmptyState(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.coach_empty_title), stringResource(R.string.coach_empty_body), null, null)
                    else -> LazyColumn {
                        items(state.conversations, key = { it.id }) { c ->
                            ListItem(
                                modifier = Modifier.clickable { navController.navigate(ConversationRoute(c.id)) },
                                overlineContent = { Text(statusLabel(c)) },
                                headlineContent = { Text(c.startedAt.atZone(zone).format(fmt)) },
                                supportingContent = {
                                    Text(
                                        stringResource(R.string.coach_row_stats, state.messageCounts[c.id] ?: 0, String.format(java.util.Locale.ROOT, "%.2f", state.spendByConversation[c.id] ?: 0.0), c.model.removePrefix("claude-")),
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
            if (!noBackend && !state.loading) {
                ExtendedFloatingActionButton(
                    onClick = { vm.onNew { id -> navController.navigate(ConversationRoute(id)) } },
                    modifier = Modifier.padding(16.dp).align(Alignment.BottomEnd),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.coach_new)) },
                )
            }
        }
    }
}

@Composable
private fun statusLabel(c: ChatConversation): String {
    val base = when (c.status) {
        ConversationStatus.OPEN -> stringResource(R.string.coach_status_open)
        ConversationStatus.AWAITING_TOOL_RESULTS -> stringResource(R.string.coach_status_awaiting)
        ConversationStatus.COMPACTED -> stringResource(R.string.coach_status_compacted)
        ConversationStatus.CLOSED -> stringResource(R.string.coach_status_closed)
    }
    return if (c.isDefault && (c.status == ConversationStatus.OPEN || c.status == ConversationStatus.AWAITING_TOOL_RESULTS)) base + " · " + stringResource(R.string.coach_default) else base
}

/**
 * §8.17 `onSend` input assembly: persisted TodayContext, the open session's frozen plan (else realizeToday), buildInputs(), constants(),
 * getCurrent(), Planner.normalize(...).trainingAge, WeeklyLedger.build, MacroTargets / EnergyEstimator / WeightTrend.
 */
class CoachInputsBuilder(private val container: AppContainer) {
    suspend fun build(skipRedFlagScan: Boolean): CoachTurnInputs {
        val pc = container.planCoordinator
        val settings = container.settingsRepository.get()
        val today = container.clock.today()
        val gyms = container.gymRepository.getAll()
        val gymId = settings.activeGymId.ifBlank { gyms.firstOrNull { it.isDefault }?.id ?: gyms.firstOrNull()?.id ?: "" }
        val persisted = try {
            if (settings.todayContextJson.isBlank()) null else CoreJson.stored.decodeFromString(TodayContext.serializer(), settings.todayContextJson)
        } catch (e: Exception) {
            null
        }
        val todayCtx = if (persisted != null && persisted.date == today) persisted else TodayContext(today, gymId)
        val constants = pc.constants()
        val planInputs = pc.buildInputs()
        val open = container.workoutRepository.observeOpenSession().first()
        val todaySession = open?.plan ?: try { pc.realizeToday(todayCtx) } catch (e: Exception) { null }
        val mesocycle = container.planRepository.getCurrent()
        val trainingAge = withContext(Dispatchers.Default) { container.planner(constants).normalize(planInputs, todayCtx.date).trainingAge }

        val ledger: WeeklyLedger? = mesocycle?.let { m ->
            val week = m.weeks.lastOrNull { !it.startDate.isAfter(today) } ?: m.weeks.firstOrNull()
            if (week == null) null else {
                val sessions = container.workoutRepository.sessionsBetween(week.startDate, week.startDate.plusDays(6))
                val sets = container.workoutRepository.setsForSessions(sessions.map { it.id })
                WeeklyLedger.build(week, sets, sessions, todaySession, container.knowledge)
            }
        }

        val trend = container.weightTrend(constants)
        val points = trend.series(planInputs.bodyMetrics)
        val ma7 = points.lastOrNull { it.ma7Kg != null }?.ma7Kg
        val weightKg = planInputs.bodyMetrics.lastOrNull { it.weight != null }?.weight?.kg ?: ma7
        val profile = planInputs.profile
        val steps = planInputs.bodyMetrics.filter { it.date >= today.minusDays(13) }.mapNotNull { it.steps }
        val stepsMean = if (steps.isEmpty()) null else steps.average().toInt()
        val nutritionState = container.planRepository.nutritionState()
        var energy: EnergyModel? = null
        var nutrition: NutritionTargets? = null
        if (weightKg != null) {
            val age = profile.ageOn(today)
            energy = container.energyEstimator(constants).model(profile, weightKg, age, profile.daysPerWeek, stepsMean, nutritionState)
            val cardio = mesocycle?.weeks?.lastOrNull { !it.startDate.isAfter(today) }?.cardio?.size ?: 0
            nutrition = container.macroTargets(constants).compute(profile, weightKg, age, profile.goal, trainingAge, energy, nutritionState, cardio, today)
        }
        return CoachTurnInputs(
            today = todayCtx, todaySession = todaySession, planInputs = planInputs, constants = constants, mesocycle = mesocycle,
            trainingAge = trainingAge, ledger = ledger, nutrition = nutrition, energy = energy,
            trendKgPerWeek = trend.delta14Kg(points)?.let { it / 2.0 }, weightMa7Kg = ma7, skipRedFlagScan = skipRedFlagScan,
        )
    }
}
