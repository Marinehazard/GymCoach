package com.gymcoach.app.ui.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.core.model.Confidence

@Composable
fun confidenceLabel(c: Confidence): String = when (c) {
    Confidence.STRONG -> stringResource(R.string.know_conf_strong)
    Confidence.MODERATE -> stringResource(R.string.know_conf_moderate)
    Confidence.WEAK -> stringResource(R.string.know_conf_weak)
    Confidence.CONTESTED -> stringResource(R.string.know_conf_contested)
    Confidence.UNVERIFIED -> stringResource(R.string.know_conf_unverified)
}

/** §8.16: every rule with its evidence level, grouped by section; tap opens the rule detail. */
@Composable
fun KnowledgeScreen(container: AppContainer, navController: NavHostController) {
    val vm: KnowledgeViewModel = viewModel(factory = vmFactory { KnowledgeViewModel(container.knowledge.rules) })
    val state by vm.state.collectAsStateWithLifecycle()

    SetupScaffold(title = stringResource(R.string.know_title), onBack = { navController.popBackStack() }) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.query, onValueChange = vm::onQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.know_search)) }, leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                )
                ChoiceChips(state.sections, state.section, { it.substringAfterLast("/").trim() }) { vm.onSection(if (it == state.section) null else it) }
            }
            if (state.rules.isEmpty()) {
                EmptyState(Icons.Filled.Search, stringResource(R.string.know_empty), stringResource(R.string.know_empty_body), null, null)
            } else {
                LazyColumn {
                    var last: String? = null
                    for (rule in state.rules) {
                        val header = if (rule.section != last) rule.section else null
                        last = rule.section
                        item(key = "rule-" + rule.id) {
                            if (header != null) SectionHeader(header)
                            ListItem(
                                modifier = Modifier.clickable { navController.navigate(RuleDetailRoute(rule.id)) },
                                overlineContent = { Text(rule.id + " · " + confidenceLabel(rule.confidence)) },
                                headlineContent = { Text(rule.statement, style = MaterialTheme.typography.bodyMedium) },
                                supportingContent = { Text(rule.citation, style = MaterialTheme.typography.labelSmall) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
