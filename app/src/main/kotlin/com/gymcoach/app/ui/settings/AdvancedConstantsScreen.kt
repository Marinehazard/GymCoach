package com.gymcoach.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.gymcoach.app.ui.components.RuleTag
import com.gymcoach.app.ui.knowledge.confidenceLabel
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.RuleDetailRoute

/** §8.18 Advanced: every PlannerConstants leaf; only user_adjustable rules are editable; bands/lists as N number fields. */
@Composable
fun AdvancedConstantsScreen(container: AppContainer, navController: NavHostController) {
    val vm: AdvancedConstantsViewModel = viewModel(
        factory = vmFactory { AdvancedConstantsViewModel(container.settingsRepository, container.knowledge.rules) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }

    SetupScaffold(
        title = stringResource(R.string.set_advanced),
        onBack = { navController.popBackStack() },
        actions = { TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.adv_reset_all)) } },
        bottomBar = {
            if (state.dirty) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = vm::onSave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.su_save)) }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Text(stringResource(R.string.adv_intro), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.query, onValueChange = vm::onQuery, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text(stringResource(R.string.adv_search)) },
            )
            LazyColumn {
                items(state.rows, key = { it.group + "." + it.field }) { row ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.group + "." + row.field, style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RuleTag(row.ruleId) { navController.navigate(RuleDetailRoute(it)) }
                                Text(confidenceLabel(row.confidence) + " · " + row.citation, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(stringResource(R.string.know_default, row.default), style = MaterialTheme.typography.bodySmall)
                            if (row.adjustable) {
                                ValueEditor(row) { vm.onEdit(row.group, row.field, it) }
                                TextButton(onClick = { vm.onReset(row.group, row.field) }) { Text(stringResource(R.string.adv_reset)) }
                            } else {
                                Text(row.current + "  " + stringResource(R.string.adv_fixed), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirmAll) {
        ConfirmDialog(
            title = stringResource(R.string.adv_reset_all_title), body = stringResource(R.string.adv_reset_all_body),
            confirmText = stringResource(R.string.adv_reset_all),
            onConfirm = { confirmAll = false; vm.onResetAll() }, onDismiss = { confirmAll = false },
        )
    }
}

/** Scalars: one field. Bands: two fields (lo, hi). Lists: N fields. The edited text is the comma-joined values. */
@Composable
private fun ValueEditor(row: ConstantRow, onChange: (String) -> Unit) {
    val text = row.current.replace("–", ",")
    val parts = text.split(',').map { it.trim() }
    if (row.kind == "band" || row.kind == "list") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.forEachIndexed { index, part ->
                OutlinedTextField(
                    value = part, singleLine = true, modifier = Modifier.weight(1f),
                    onValueChange = { v -> onChange(parts.toMutableList().also { it[index] = v }.joinToString(",")) },
                )
            }
        }
    } else {
        OutlinedTextField(value = row.current, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}
