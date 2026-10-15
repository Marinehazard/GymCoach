package com.gymcoach.app.ui.session

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.nav.CoachRoute
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.app.ui.nav.SessionSummaryRoute
import com.gymcoach.app.ui.nav.TodayRoute
import com.gymcoach.app.ui.today.trainingDepsOf
import com.gymcoach.core.units.Units

/** §8.9: totals, per-exercise outcome lines with RuleTags, e1RM changes, the modifier recap and every Adjustment of the session. */
@Composable
fun SessionSummaryScreen(container: AppContainer, navController: NavHostController, route: SessionSummaryRoute) {
    val vm: SessionSummaryViewModel = viewModel(key = "summary-" + route.sessionId) { SessionSummaryViewModel(route.sessionId, trainingDepsOf(container)) }
    val s by vm.state.collectAsStateWithLifecycle()
    val onRule: (String) -> Unit = { navController.navigate(RuleDetailRoute(it)) }
    if (s.loading) { LoadingBox(); return }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.session_summary_title), style = MaterialTheme.typography.headlineSmall) }
        s.error?.let { e -> item { ErrorBanner(stringResource(R.string.session_summary_error), e, vm::reload, null) } }
        if (s.record != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(SessionSummaryViewModel.workingSetCount(s.sets).toString(), style = MaterialTheme.typography.displaySmall); Text(stringResource(R.string.session_summary_sets)) }
                        Column { Text(Units.trim(Math.round(SessionSummaryViewModel.tonnageKg(s.sets)).toDouble()), style = MaterialTheme.typography.displaySmall); Text(stringResource(R.string.session_summary_tonnage)) }
                        Column { Text(s.durationMin.toString(), style = MaterialTheme.typography.displaySmall); Text(stringResource(R.string.session_summary_minutes)) }
                    }
                }
            }
            s.sessionRpe?.let { rpe -> item { Text(stringResource(R.string.session_summary_rpe, rpe), style = MaterialTheme.typography.titleMedium) } }
        }
        if (s.progression.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.session_summary_next_time)) }
            items2(s.progression) { ExplanationChip(explanation = it, onRule = onRule) }
        }
        if (s.e1rmChanges.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.session_summary_e1rm)) }
            items2(s.e1rmChanges) { (name, delta) -> Text("$name  ${if (delta >= 0) "+" else ""}${Units.trim(delta)} kg", style = MaterialTheme.typography.bodyLarge) }
        }
        val recap = s.record?.plan?.modifiers ?: emptyList()
        if (recap.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.session_summary_recap)) }
            items2(recap) { ExplanationChip(explanation = it, onRule = onRule) }
        }
        if (s.adjustments.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.session_summary_adjustments)) }
            items2(s.adjustments) { a ->
                Column { Text(a.summary, style = MaterialTheme.typography.bodyLarge); Row { for (id in a.ruleIds) com.gymcoach.app.ui.components.RuleTag(id, onRule) } }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { navController.navigate(CoachRoute) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.session_summary_ask_coach)) }
                Button(onClick = { navController.navigate(TodayRoute) { popUpTo<TodayRoute> { inclusive = false } } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.training_action_done)) }
            }
        }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.items2(list: List<T>, content: @Composable (T) -> Unit) {
    items(list.size) { content(list[it]) }
}
