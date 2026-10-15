@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.OfflineBanner
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.BackendSettingsRoute
import com.gymcoach.app.ui.nav.ConversationRoute
import com.gymcoach.app.ui.nav.ExerciseDetailRoute
import com.gymcoach.app.ui.nav.GymsRoute
import com.gymcoach.app.ui.settings.BackendCopy
import com.gymcoach.core.coach.TranscriptCompactor
import com.gymcoach.core.repo.BackendError

private const val COMPACTION_FALLBACK =
    "Summarise the following coaching conversation in at most 600 tokens: standing decisions, user preferences, open questions, pending proposals. Facts only, no advice."

/** compaction_prompt_v1.txt from the :core class path (§6.9), with the spec text as fallback. */
private fun loadCompactionPrompt(): String = try {
    ConversationRoute::class.java.classLoader?.getResourceAsStream("gymcoach/coach/compaction_prompt_v1.txt")?.bufferedReader()?.use { it.readText() }
        ?.takeIf { it.isNotBlank() } ?: COMPACTION_FALLBACK
} catch (e: Exception) {
    COMPACTION_FALLBACK
}

/** §8.17 conversation: bubbles, streaming, proposal cards, context chip, quick prompts, offline banner and queued draft. */
@Composable
fun ConversationScreen(container: AppContainer, navController: NavHostController, route: ConversationRoute) {
    val builder = remember { CoachInputsBuilder(container) }
    val compactor = remember { TranscriptCompactor(container.coachRepository, { mode -> container.backend(mode) }, container.settingsRepository, container.clock) }
    val vm: CoachViewModel = viewModel(
        key = "conversation-" + route.conversationId,
        factory = vmFactory {
            CoachViewModel(
                conversationId = route.conversationId, coach = container.coachRepository, settings = container.settingsRepository,
                gyms = container.gymRepository, plans = container.planRepository, workouts = container.workoutRepository,
                network = container.networkMonitor, turns = container.coachTurnService, applier = container.proposalApplier,
                spend = container.spendMeter, knowledge = container.knowledge, clock = container.clock,
                buildInputs = { skip -> builder.build(skip) }, recompute = { reason -> container.planCoordinator.recompute(reason) },
                compact = { id -> compactor.compact(id, loadCompactionPrompt()) },
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val phase = state.phase
    val streamingLength = (phase as? CoachPhase.Streaming)?.partial?.length ?: 0
    val openConversation: (String) -> Unit = { id -> navController.navigate(ConversationRoute(id)) }

    LaunchedEffect(state.messages.size, streamingLength) {
        val count = state.messages.size + (if (phase is CoachPhase.Streaming) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    SetupScaffold(
        title = stringResource(R.string.coach_title),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(onClick = vm::onShowContext) { Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.coach_context_title)) }
            IconButton(onClick = { vm.onNewConversation(openConversation) }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.coach_new)) }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!extras.readOnly) QuickPromptRow(state.quickPrompts, vm::onQuickPrompt)
                Text(
                    stringResource(R.string.coach_footer, state.messages.count { it.id != CoachViewModel.LOCAL_ID }, String.format(java.util.Locale.ROOT, "%.2f", extras.conversationCostUsd), modelLabel(state.model), state.effort.name.lowercase()),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.draft, onValueChange = vm::onDraft, modifier = Modifier.weight(1f), maxLines = 4,
                        enabled = !extras.readOnly, label = { Text(stringResource(if (extras.readOnly) R.string.coach_read_only else R.string.coach_composer)) },
                    )
                    val busy = phase is CoachPhase.Sending || phase is CoachPhase.Streaming || phase is CoachPhase.AwaitingTool
                    if (busy) {
                        IconButton(onClick = vm::onStop) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.coach_stop)) }
                    } else {
                        IconButton(onClick = vm::onSend, enabled = state.draft.isNotBlank() && !extras.readOnly) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.coach_send))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!extras.online) OfflineBanner()
            if (state.budgetUsd > 0.0 && state.monthSpendUsd >= 0.8 * state.budgetUsd) {
                Text(stringResource(R.string.coach_budget_banner, String.format(java.util.Locale.ROOT, "%.2f", state.monthSpendUsd), String.format(java.util.Locale.ROOT, "%.0f", state.budgetUsd)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (state.contextPreview.isNotBlank()) AssistChip(onClick = vm::onShowContext, label = { Text(state.contextPreview) }, modifier = Modifier.padding(horizontal = 12.dp))
            if (phase is CoachPhase.NoBackend) {
                EmptyState(Icons.AutoMirrored.Filled.Chat, stringResource(R.string.coach_setup_title), stringResource(R.string.coach_setup_body), stringResource(R.string.coach_setup_action)) { navController.navigate(BackendSettingsRoute) }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.messages, key = { it.id }) { m ->
                    MessageBubble(
                        message = m, proposals = state.proposals.values.filter { it.assistantMessageId == m.id }, knowledge = container.knowledge,
                        canUndo = vm::canUndo, onApply = vm::onApply, onReject = { vm.onReject(it, null) }, onLater = vm::onLater, onUndo = vm::onUndo,
                        onExercise = { navController.navigate(ExerciseDetailRoute(it)) },
                    )
                }
                if (phase is CoachPhase.Streaming) item(key = "streaming") { StreamingBubble(phase.partial, container.knowledge) }
            }
            state.redFlagHit?.let { RedFlagCard(it, vm::onRedFlagSendAnyway, vm::onRedFlagCancel) }
            PhasePanel(phase, extras.queuedDraft, vm, navController, openConversation)
        }
    }

    if (extras.showContext) ContextSheet(state.messages, vm::onShowContext)
    if (extras.decideFirst) {
        AlertDialog(
            onDismissRequest = vm::onDismissDecideFirst,
            confirmButton = { TextButton(onClick = vm::onDismissDecideFirst) { Text(stringResource(R.string.su_close)) } },
            title = { Text(stringResource(R.string.coach_decide_title)) }, text = { Text(stringResource(R.string.coach_decide_body)) },
        )
    }
    if (extras.confirmBudget) {
        AlertDialog(
            onDismissRequest = vm::onBudgetCancel,
            confirmButton = { TextButton(onClick = vm::onBudgetContinue) { Text(stringResource(R.string.coach_budget_continue)) } },
            dismissButton = { TextButton(onClick = vm::onBudgetCancel) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.coach_budget_title)) }, text = { Text(stringResource(R.string.coach_budget_body)) },
        )
    }
}

private fun modelLabel(model: String): String = when (model) {
    "claude-opus-5" -> "Opus 5"
    "claude-sonnet-5" -> "Sonnet 5"
    else -> model
}

@Composable
private fun RedFlagCard(phrase: String, onSendAnyway: () -> Unit, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.coach_redflag_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.coach_redflag_body, phrase), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = onSendAnyway) { Text(stringResource(R.string.coach_redflag_send)) }
            }
        }
    }
}

/** One branch per CoachPhase subclass (HR-11: `when` is exhaustive). */
@Composable
private fun PhasePanel(phase: CoachPhase, queued: String?, vm: CoachViewModel, nav: NavHostController, openConversation: (String) -> Unit) {
    when (phase) {
        CoachPhase.Idle -> Unit
        CoachPhase.Sending -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is CoachPhase.Streaming -> Unit
        CoachPhase.AwaitingTool -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.coach_looking_up), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        CoachPhase.ProposalPending -> Text(stringResource(R.string.coach_proposal_pending), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        is CoachPhase.Error -> {
            val err = phase.error
            val noGym = err is BackendError.InvalidRequest && err.status == 0
            Column(modifier = Modifier.padding(12.dp)) {
                ErrorBanner(
                    title = stringResource(if (noGym) R.string.coach_no_gym_title else R.string.coach_error_title),
                    body = BackendCopy.of(err), onRetry = if (phase.retryable) vm::onRetry else null,
                    details = BackendCopy.typeOf(err),
                )
                if (noGym) TextButton(onClick = { nav.navigate(GymsRoute) }) { Text(stringResource(R.string.coach_add_gym)) }
                if (err == BackendError.Authentication) TextButton(onClick = { nav.navigate(BackendSettingsRoute) }) { Text(stringResource(R.string.coach_open_backend)) }
            }
        }
        is CoachPhase.Offline -> Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (phase.draftQueued) stringResource(R.string.coach_queued, queued ?: "") else stringResource(R.string.coach_offline_hint), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (phase.draftQueued) TextButton(onClick = vm::onCancelQueued) { Text(stringResource(R.string.action_cancel)) }
        }
        CoachPhase.NoBackend -> Unit
        is CoachPhase.Refused -> Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.coach_refused, phase.category ?: "-"), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = vm::onRetryOnSonnet) { Text(stringResource(R.string.coach_retry_sonnet)) }
        }
        CoachPhase.Truncated -> Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.coach_truncated), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = vm::onContinue) { Text(stringResource(R.string.coach_continue)) }
        }
        CoachPhase.SpendCapReached -> Text(BackendCopy.of(BackendError.SpendCapReached), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        CoachPhase.CompactionNeeded -> Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.coach_compaction), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { vm.onCompactAndContinue(openConversation) }) { Text(stringResource(R.string.coach_compact)) }
        }
    }
}
