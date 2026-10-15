package com.gymcoach.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
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
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.components.RuleTag
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.app.ui.today.trainingDepsOf

/** "Changes": the adjustment log with rule tags and Undo (§8.10, POL-01). */
@Composable
fun AdjustmentLogScreen(container: AppContainer, navController: NavHostController) {
    val vm: AdjustmentLogViewModel = viewModel { AdjustmentLogViewModel(trainingDepsOf(container)) }
    val s by vm.state.collectAsStateWithLifecycle()
    if (s.loading) { LoadingBox(); return }
    if (s.items.isEmpty()) {
        EmptyState(Icons.Filled.History, stringResource(R.string.adjustment_log_empty_title), stringResource(R.string.adjustment_log_empty_body), null, null)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        s.error?.let { e -> item { ErrorBanner(stringResource(R.string.plan_error_title), e, null, null) } }
        items(s.items.size) { i ->
            val a = s.items[i]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.createdAt.toString().take(16).replace('T', ' ') + " · " + a.kind.name.lowercase().replace('_', ' ') + " · " + a.source.name.lowercase(), style = MaterialTheme.typography.labelMedium)
                    Text(a.summary, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (id in a.ruleIds) RuleTag(id) { navController.navigate(RuleDetailRoute(it)) }
                    }
                    if (a.undoneAt != null) Text(stringResource(R.string.adjustment_log_undone), style = MaterialTheme.typography.labelMedium)
                    else if (vm.canUndo(a)) OutlinedButton(onClick = { vm.onUndo(a) }) { Text(stringResource(R.string.action_undo)) }
                }
            }
        }
    }
}
