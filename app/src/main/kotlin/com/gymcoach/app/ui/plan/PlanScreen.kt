package com.gymcoach.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.ConfirmDialog
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.nav.AdjustmentLogRoute
import com.gymcoach.app.ui.nav.OnboardingRoute
import com.gymcoach.app.ui.nav.PlanSessionRoute
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.app.ui.today.trainingDepsOf

/** §8.10 Plan: timeline, week strip, per-session rows, weekly ledger, under-target report, overrides, planner proposals, "Changes". */
@Composable
fun PlanScreen(container: AppContainer, navController: NavHostController) {
    val vm: PlanViewModel = viewModel { PlanViewModel(trainingDepsOf(container)) }
    val s by vm.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val onRule: (String) -> Unit = { navController.navigate(RuleDetailRoute(it)) }
    LaunchedEffect(vm) { vm.openSession.collect { navController.navigate(PlanSessionRoute(it)) } }
    if (s.loading) { LoadingBox(); return }
    val meso = s.mesocycle
    val week = s.currentWeek
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.error?.let { e -> item { ErrorBanner(stringResource(R.string.plan_error_title), e, vm::refresh, null) } }
        if (meso == null || week == null) {
            item { EmptyState(Icons.Filled.FitnessCenter, stringResource(R.string.today_no_plan_title), stringResource(R.string.today_no_plan_body), stringResource(R.string.today_no_plan_action)) { navController.navigate(OnboardingRoute) } }
            return@LazyColumn
        }
        item { Text(PlanViewModel.weekLabel(meso, week), style = MaterialTheme.typography.titleLarge) }
        item { WeekSelector(meso.weeks, s.weekIndex, meso.deloadWeekIndex, vm::onWeekSelect) }
        item { WeekStrip(week, vm::onOpenSession) }
        item { LedgerCard(s.ledger, vm::onToggleDirect) }
        if (s.underTarget.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.plan_under_target)) }
            items(s.underTarget.size) { ExplanationChip(explanation = s.underTarget[it], onRule = onRule) }
        }
        if (s.proposals.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.plan_proposals)) }
            items(s.proposals.size) { i -> val m = s.proposals[i]; ProposalRow(m, { vm.onProposal(m, true) }, { vm.onProposal(m, false) }) }
        }
        if (s.overrides.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.plan_overrides)) }
            items(s.overrides.size) { i -> val o = s.overrides[i]; OverrideRow(o) { vm.onRevokeOverride(o.id) } }
        }
        item { OutlinedButton(onClick = { navController.navigate(AdjustmentLogRoute) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.today_changes)) } }
        item { OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.plan_regenerate)) } }
    }
    if (confirm) ConfirmDialog(
        title = stringResource(R.string.plan_regenerate), body = stringResource(R.string.plan_regenerate_body), confirmText = stringResource(R.string.plan_regenerate),
        onConfirm = { confirm = false; vm.onRegenerate() }, onDismiss = { confirm = false },
    )
}
