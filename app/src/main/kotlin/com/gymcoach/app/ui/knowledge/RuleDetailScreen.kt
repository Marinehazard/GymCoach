package com.gymcoach.app.ui.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.AdvancedConstantsRoute
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.core.units.Units

/** §8.16: statement, confidence, citation (opens the browser when online), constants current vs default, "edit in Advanced". */
@Composable
fun RuleDetailScreen(container: AppContainer, navController: NavHostController, route: RuleDetailRoute) {
    val vm: RuleDetailViewModel = viewModel(
        key = "rule-" + route.ruleId,
        factory = vmFactory { RuleDetailViewModel(route.ruleId, container.knowledge.rules, container.settingsRepository, plannerConstants = { json -> container.plannerConstants(json) }) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val rule = state.rule

    SetupScaffold(title = rule?.id ?: route.ruleId, onBack = { navController.popBackStack() }) { padding ->
        if (rule == null) {
            EmptyState(Icons.Filled.Search, stringResource(R.string.know_rule_missing), route.ruleId, null, null)
            return@SetupScaffold
        }
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(rule.statement, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.know_confidence, confidenceLabel(rule.confidence)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.know_citation, rule.citation), style = MaterialTheme.typography.bodyMedium)
            rule.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            rule.url?.let { url ->
                OutlinedButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(R.string.know_open_source)) }
            }
            if (state.constants.isNotEmpty()) {
                SectionHeader(stringResource(R.string.know_constants))
                for (c in state.constants) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.group + "." + c.key, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Units.trim(c.current) + if (c.current != c.default) "  (" + stringResource(R.string.know_default, Units.trim(c.default)) + ")" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (rule.userAdjustable) {
                    OutlinedButton(onClick = { navController.navigate(AdvancedConstantsRoute) }) { Text(stringResource(R.string.know_edit_advanced)) }
                } else {
                    Text(stringResource(R.string.know_not_adjustable), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
