@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.app.ui.nav.SessionRoute
import com.gymcoach.app.ui.nav.SessionSummaryRoute
import com.gymcoach.app.ui.today.trainingDepsOf
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.units.Units

/** §8.8 — the product. One thumb: the current exercise is pinned, LOG SET (64 dp) is always visible and never blocked by the warm-up. */
@Composable
fun SessionScreen(container: AppContainer, navController: NavHostController, route: SessionRoute) {
    val vm: SessionViewModel = viewModel(key = route.sessionId) { SessionViewModel(route.sessionId, trainingDepsOf(container)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val swap by vm.swap.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val keypad by vm.keypadField.collectAsStateWithLifecycle()
    val correcting by vm.correcting.collectAsStateWithLifecycle()
    val findLoad by vm.findLoadOffer.collectAsStateWithLifecycle()
    val stale by vm.stale.collectAsStateWithLifecycle()
    val keepOn by remember { container.settingsRepository.observe() }.collectAsStateWithLifecycle(initialValue = null)
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val unit = if (state.unit == com.gymcoach.core.model.UnitSystem.METRIC) WeightUnit.KG else WeightUnit.LB
    val onRule: (String) -> Unit = { navController.navigate(RuleDetailRoute(it)) }

    DisposableEffect(keepOn?.keepScreenOnInSession) {
        view.keepScreenOn = keepOn?.keepScreenOnInSession ?: true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { vm.onPause() }
    LaunchedEffect(vm) {
        vm.navigation.collect { nav ->
            when (nav) {
                is SessionNav.Summary -> navController.navigate(SessionSummaryRoute(nav.sessionId)) { popUpTo<SessionRoute> { inclusive = true } }
                SessionNav.Back -> navController.popBackStack()
                SessionNav.SetLogged, SessionNav.RestOver -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    Scaffold(
        topBar = { SessionTopBar(state, vm::onPause, { vm.openSheet(SessionSheet.NOTES) }, vm::onSomethingWrong, vm::onAbandon) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    RestBar(state.rest, vm::onRestPlus30, vm::onRestSkip)
                    val finishing = state.phase == SessionPhase.FINISHING
                    BigPrimaryButton(
                        text = stringResource(if (finishing) R.string.session_finish else R.string.session_log_set),
                        onClick = { if (finishing) vm.onFinish() else vm.onLogSet() },
                        modifier = Modifier.padding(12.dp),
                        enabled = state.phase != SessionPhase.LOADING && state.phase != SessionPhase.ERROR && state.phase != SessionPhase.DONE,
                    )
                }
            }
        },
    ) { padding: PaddingValues ->
        SessionBody(container, vm, state, findLoad, unit, stale, onRule, Modifier.padding(padding))
    }

    val sheet = state.sheet
    if (sheet != null) {
        ModalBottomSheet(onDismissRequest = { vm.openSheet(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (sheet) {
                SessionSheet.PLATES -> {
                    val kg = SetLogging.parse(state.draft.loadValue)?.let { if (unit == WeightUnit.KG) it else Units.lbToKg(it) } ?: 0.0
                    PlateCalculatorContent(kg, PlateMath.context(container.knowledge, vm.sessionEquipment(), state.rows.getOrNull(state.currentIndex)?.block), vm::onUsePlateLoad)
                }
                SessionSheet.SWAP -> SwapSheetContent(swap, vm::onSwapReason, vm::onPickSubstitute, vm::onSkipExercise, { vm.openSheet(null) }, null, onRule)
                SessionSheet.INFO -> ExerciseInfoContent(info)
                SessionSheet.NOTES -> NotesContent(state.record?.notes ?: "", vm::onSaveNote)
                SessionSheet.RED_FLAG -> RedFlagContent(onStop = { vm.onRedFlagStop(it.text) }, onContinue = { vm.openSheet(null) })
                SessionSheet.FINISH -> FinishContent(state, state.rows.flatMap { it.logged }.filter { it.supersededById == null }, vm::onFinishConfirm)
                SessionSheet.KEYPAD -> KeypadContent(
                    initial = when (keypad) { KeypadField.LOAD -> state.draft.loadValue; KeypadField.REPS -> state.draft.reps; KeypadField.SECONDS -> state.draft.seconds },
                    allowDecimal = keypad == KeypadField.LOAD, onCommit = { vm.onKeypadCommit(keypad, it) },
                )
                SessionSheet.CORRECT_SET -> correcting?.let { s -> CorrectSetContent(s, unit) { d -> vm.onSaveCorrection(s.id, d) } }
                SessionSheet.ADD_EXERCISE -> AddExerciseContent(container.knowledge) { vm.onAddExercise(it); vm.openSheet(null) }
            }
        }
    }
}

@Composable
private fun SessionBody(
    container: AppContainer, vm: SessionViewModel, state: SessionUiState, findLoad: Double?, unit: WeightUnit, stale: Boolean,
    onRule: (String) -> Unit, modifier: Modifier,
) {
    if (state.phase == SessionPhase.LOADING) { LoadingBox(); return }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(state.progressText, style = MaterialTheme.typography.titleMedium)
            val total = state.rows.sumOf { SetLogging.workPrescriptions(it.block).size }.coerceAtLeast(1)
            val done = state.rows.sumOf { r -> r.logged.count { it.supersededById == null && it.kind != com.gymcoach.core.model.SetKind.RAMP } }
            LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        }
        if (state.phase == SessionPhase.ERROR) {
            item { ErrorBanner(stringResource(R.string.session_error_title), state.error ?: "", null, null) }
            item {
                Button(onClick = { vm.onFinishConfirm(null, "", false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.session_finish_as_is)) }
                OutlinedButton(onClick = vm::onAbandon, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.session_abandon)) }
            }
            return@LazyColumn
        }
        if (state.phase == SessionPhase.RECOVERED) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.progressText, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = vm::onDismissRecovered) { Text(stringResource(R.string.training_action_continue)) }
                }
            }
        }
        if (stale) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.session_stale), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.onFinishConfirm(null, "", false) }) { Text(stringResource(R.string.session_finish_as_is)) }
                        OutlinedButton(onClick = vm::onAbandon) { Text(stringResource(R.string.session_abandon)) }
                    }
                }
            }
        }
        item { ModifierBanner(state.modifiers, onRule) }
        state.plan?.let { plan ->
            item {
                WarmupBlock(plan, state.warmupDone, { id -> container.knowledge.drills.firstOrNull { it.id == id }?.name ?: id }, vm::onWarmupDone, vm::onSkipWarmup, onRule)
            }
        }
        if (state.rows.isEmpty()) item {
            Card { Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.session_free_title), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { vm.openSheet(SessionSheet.ADD_EXERCISE) }) { Text(stringResource(R.string.session_add_exercise)) }
            } }
        }
        items(state.rows.size) { i ->
            val row = state.rows[i]
            if (i == state.currentIndex && row.status != RowStatus.SKIPPED && row.block.sets.isNotEmpty()) {
                CurrentExerciseCard(
                    state, row, unit, container.knowledge.exercise(row.block.exerciseId)?.unilateral == true,
                    vm::onInfo, vm::onSwap, vm::onCorrectSet, vm::onAddSet, vm::onRemovePlannedSet, vm::onSkipExercise,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HintChips(row, state.backoffChip, findLoad, unit, vm::onApplyBackoff, vm::onDismissBackoff, vm::onFindLoadAccept, onRule)
                        SetLogger(
                            state, row, vm::onLoadMinus, vm::onLoadPlus, vm::onOpenKeypad, vm::onOpenPlates, vm::onRepsMinus, vm::onRepsPlus,
                            vm::onSecondsMinus, vm::onSecondsPlus, vm::onRir, container.knowledge.exercise(row.block.exerciseId)?.unilateral == true,
                            onSecondsSet = { vm.onKeypadCommit(KeypadField.SECONDS, it) },
                        )
                    }
                }
            } else CollapsedExerciseRow(row) { vm.onJumpTo(i) }
        }
        item { OutlinedButton(onClick = vm::onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.session_finish)) } }
    }
}
