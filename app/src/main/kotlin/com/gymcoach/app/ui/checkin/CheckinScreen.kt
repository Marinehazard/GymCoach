package com.gymcoach.app.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.components.NumberField
import com.gymcoach.app.ui.components.parseDecimal
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.app.ui.onboarding.weekdayLabel
import com.gymcoach.core.model.CheckinAnswer
import com.gymcoach.core.model.CheckinAnswerType
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.units.Units

/** §8.15: one question per page, big chips or a stepper; auto-answers pre-filled and editable; triggered actions as cards. */
@Composable
fun CheckinScreen(container: AppContainer, navController: NavHostController) {
    val vm: CheckinViewModel = viewModel(
        factory = vmFactory {
            CheckinViewModel(
                metrics = container.metricsRepository, workouts = container.workoutRepository, plans = container.planRepository,
                profiles = container.profileRepository, settings = container.settingsRepository, bank = container.checkinBank(),
                evaluator = { c -> container.checkinEvaluator(c) }, constants = { container.planCoordinator.constants() },
                recompute = { reason -> container.planCoordinator.recompute(reason) }, clock = container.clock,
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val onRule: (String) -> Unit = { navController.navigate(RuleDetailRoute(it)) }

    SetupScaffold(title = stringResource(R.string.chk_title), onBack = { navController.popBackStack() }) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            extras.error?.let { ErrorBanner(stringResource(R.string.chk_error), it, null, null) }
            when {
                extras.loading -> LoadingBox()
                state.done -> {
                    if (extras.alreadyDone) {
                        EmptyState(Icons.Filled.EventAvailable, stringResource(R.string.chk_done_title), stringResource(R.string.chk_next, weekdayLabel(extras.nextCheckinWeekday)), null, null)
                    } else {
                        Text(stringResource(R.string.chk_saved), style = MaterialTheme.typography.titleMedium)
                    }
                    for (e in state.triggered) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) { ExplanationChip(e, onRule) }
                        }
                    }
                    for (mutation in extras.proposals) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(mutation.rationale, style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { vm.onProposal(mutation, false) }) { Text(stringResource(R.string.chk_not_this_week)) }
                                    OutlinedButton(onClick = { vm.onProposal(mutation, true) }) { Text(stringResource(R.string.chk_ok)) }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.su_close)) }
                }
                state.questions.isEmpty() -> EmptyState(Icons.Filled.EventAvailable, stringResource(R.string.chk_empty_title), stringResource(R.string.chk_next, weekdayLabel(extras.nextCheckinWeekday)), null, null)
                else -> {
                    val q = state.questions[state.page.coerceIn(0, state.questions.size - 1)]
                    Text(stringResource(R.string.chk_progress, state.page + 1, state.questions.size), style = MaterialTheme.typography.labelLarge)
                    Text(q.text, style = MaterialTheme.typography.headlineSmall)
                    if (q.id in state.autoAnswered) Text(stringResource(R.string.chk_auto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    AnswerInput(q, state.answers[q.id], vm::onAnswer)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = vm::onBack, enabled = state.page > 0) { Text(stringResource(R.string.su_back)) }
                        if (state.page < state.questions.size - 1) {
                            TextButton(onClick = vm::onNext) { Text(stringResource(R.string.su_next)) }
                        }
                    }
                    if (state.page == state.questions.size - 1) {
                        BigPrimaryButton(text = stringResource(R.string.su_done), onClick = vm::onDone, enabled = !extras.saving)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerInput(q: CheckinQuestion, answer: CheckinAnswer?, onAnswer: (CheckinAnswer) -> Unit) {
    when (q.answerType) {
        CheckinAnswerType.SCALE5 -> ChoiceChips((1..5).toList(), answer?.number?.toInt(), { it.toString() }) { onAnswer(CheckinAnswer(q.id, number = it.toDouble())) }
        CheckinAnswerType.YESNO -> ChoiceChips(listOf(true, false), answer?.yes, { if (it) stringResource(R.string.chk_yes) else stringResource(R.string.chk_no) }) { onAnswer(CheckinAnswer(q.id, yes = it)) }
        CheckinAnswerType.NUMBER -> NumberField(
            value = answer?.number?.let { Units.trim(it) } ?: "",
            onValueChange = { t -> onAnswer(CheckinAnswer(q.id, number = parseDecimal(t))) },
            label = stringResource(R.string.chk_number), unit = "", modifier = Modifier.fillMaxWidth(),
        )
        CheckinAnswerType.TEXT -> OutlinedTextField(
            value = answer?.text ?: "", onValueChange = { onAnswer(CheckinAnswer(q.id, text = it)) },
            label = { Text(stringResource(R.string.chk_text)) }, modifier = Modifier.fillMaxWidth(),
        )
    }
}
