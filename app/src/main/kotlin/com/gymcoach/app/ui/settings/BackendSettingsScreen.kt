package com.gymcoach.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.ConfirmDialog
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.MiniStepper
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.SwitchRow
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.Effort
import com.gymcoach.core.units.Units

/** Model ids the picker offers (R1 §2.3); dateless ids are pinned snapshots. */
object CoachModels {
    const val OPUS = "claude-opus-5"
    const val SONNET = "claude-sonnet-5"
}

/** §8.18 Backend: the three policy blocks verbatim (§6.1), key entry, Test, bridge behind "Show experimental", model, effort, budget. */
@Composable
fun BackendSettingsScreen(container: AppContainer, navController: NavHostController) {
    val vm: BackendSettingsViewModel = viewModel(
        factory = vmFactory {
            BackendSettingsViewModel(
                settings = container.settingsRepository, keys = container.apiKeyStore, backendFor = { mode -> container.backend(mode) },
                coach = container.coachRepository, clock = container.clock,
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }

    SetupScaffold(title = stringResource(R.string.backend_title), onBack = { navController.popBackStack() }) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PolicyBlock(stringResource(R.string.backend_api_title), stringResource(R.string.backend_api_body))
            if (state.showExperimental || state.mode == BackendMode.HOME_BRIDGE) {
                PolicyBlock(stringResource(R.string.backend_bridge_title), stringResource(R.string.backend_bridge_body))
            }
            Text(stringResource(R.string.backend_never), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionHeader(stringResource(R.string.set_mode))
            val modes = if (state.showExperimental || state.mode == BackendMode.HOME_BRIDGE) listOf(BackendMode.NONE, BackendMode.DIRECT_API, BackendMode.HOME_BRIDGE)
            else listOf(BackendMode.NONE, BackendMode.DIRECT_API)
            ChoiceChips(modes, state.mode, {
                when (it) { BackendMode.NONE -> stringResource(R.string.set_mode_none); BackendMode.DIRECT_API -> stringResource(R.string.set_mode_api); BackendMode.HOME_BRIDGE -> stringResource(R.string.set_mode_bridge) }
            }, onSelect = vm::onMode)

            SectionHeader(stringResource(R.string.set_api_key))
            ApiKeyEntry(state.hasKey, state.keyDraft, state.keyError, vm::onKeyDraft, vm::onSaveKey) { confirmRemove = true }
            Button(onClick = vm::onTest, enabled = !state.testing && state.mode != BackendMode.NONE, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.testing) stringResource(R.string.set_testing) else stringResource(R.string.set_test))
            }
            state.testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("OK")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }

            SectionHeader(stringResource(R.string.set_experimental))
            SwitchRow(stringResource(R.string.set_show_experimental), state.showExperimental, vm::onShowExperimental)
            if (state.showExperimental || state.mode == BackendMode.HOME_BRIDGE) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.set_bridge_conditions), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.set_bridge_no_lookup), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = state.bridgeUrl, onValueChange = vm::onBridgeUrl, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.set_bridge_url)) })
                        var secret by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = secret, onValueChange = { secret = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (state.bridgeSecretSet) stringResource(R.string.set_bridge_secret_set) else stringResource(R.string.set_bridge_secret)) },
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Button(onClick = { vm.onBridgeSecret(secret); secret = "" }, enabled = secret.isNotBlank()) { Text(stringResource(R.string.set_bridge_secret_save)) }
                    }
                }
            }

            SectionHeader(stringResource(R.string.set_model))
            ChoiceChips(listOf(CoachModels.OPUS, CoachModels.SONNET), state.model, {
                if (it == CoachModels.OPUS) stringResource(R.string.set_model_opus) else stringResource(R.string.set_model_sonnet)
            }, onSelect = vm::onModel)
            ChoiceChips(listOf(Effort.LOW, Effort.MEDIUM), state.effort, {
                if (it == Effort.LOW) stringResource(R.string.set_effort_low) else stringResource(R.string.set_effort_medium)
            }, onSelect = vm::onEffort)
            Text(stringResource(R.string.backend_cost_line), style = MaterialTheme.typography.bodySmall)
            if (state.openConversationWillClose) Text(stringResource(R.string.set_closes_conversation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            MiniStepper(
                label = stringResource(R.string.set_budget), value = "$" + Units.trim(state.monthlyBudgetUsd),
                onMinus = { vm.onBudget(state.monthlyBudgetUsd - 5.0) }, onPlus = { vm.onBudget(state.monthlyBudgetUsd + 5.0) },
            )
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.set_key_remove_title), body = stringResource(R.string.set_key_remove_body),
            confirmText = stringResource(R.string.set_key_remove),
            onConfirm = { confirmRemove = false; vm.onRemoveKey() }, onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun PolicyBlock(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
