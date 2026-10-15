package com.gymcoach.app.ui.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory

private fun usd(v: Double): String = "$" + String.format(java.util.Locale.ROOT, "%.2f", v)

/** §8.19: spend per day and per conversation, tokens, cache hit rate, budget bar, list-price footnote. */
@Composable
fun UsageScreen(container: AppContainer, navController: NavHostController) {
    val vm: UsageViewModel = viewModel(
        factory = vmFactory { UsageViewModel(container.coachRepository, container.settingsRepository, container.spendMeter, container.clock) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val fraction = if (state.budgetUsd > 0.0) (state.monthSpendUsd / state.budgetUsd).toFloat().coerceIn(0f, 1f) else 0f

    SetupScaffold(title = stringResource(R.string.usage_title), onBack = { navController.popBackStack() }) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { ErrorBanner(stringResource(R.string.usage_error), it, vm::onRefresh, null) }
            Text(stringResource(R.string.usage_month, usd(state.monthSpendUsd), usd(state.budgetUsd)), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            if (fraction >= 0.8f) Text(stringResource(R.string.usage_budget_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.usage_tokens, state.tokensIn, state.tokensOut), style = MaterialTheme.typography.bodyLarge)
            Text(
                state.cacheHitRate?.let { stringResource(R.string.usage_cache, (it * 100).toInt()) } ?: stringResource(R.string.usage_cache_none),
                style = MaterialTheme.typography.bodyLarge,
            )
            SectionHeader(stringResource(R.string.usage_per_day))
            if (state.perDay.isEmpty()) Text(stringResource(R.string.usage_none), style = MaterialTheme.typography.bodyMedium)
            val maxDay = state.perDay.maxOfOrNull { it.second } ?: 0.0
            for ((day, cost) in state.perDay) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(day.toString(), modifier = Modifier.padding(end = 4.dp), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (maxDay > 0.0) (cost / maxDay).toFloat() else 0f }, modifier = Modifier.weight(1f).padding(top = 8.dp))
                    Text(usd(cost), style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionHeader(stringResource(R.string.usage_per_conversation))
            for ((label, cost) in state.perConversation) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(usd(cost), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.usage_footnote), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
