package com.gymcoach.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.HourMinuteStepper
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.SwitchRow
import com.gymcoach.app.ui.more.appVersionName
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.AdvancedConstantsRoute
import com.gymcoach.app.ui.nav.BackendSettingsRoute
import com.gymcoach.app.ui.nav.ProfileRoute
import com.gymcoach.app.ui.onboarding.weekdayLabel
import com.gymcoach.core.model.UnitSystem

private fun toMinutes(hhmm: String): Int {
    val parts = hhmm.split(':')
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 30
    return (h * 60 + m).coerceIn(0, 1439)
}

private fun toHhmm(minutes: Int): String = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

/** §8.18 main settings: units, week start, profile, session, reminders, data, backend, advanced, about. */
@Composable
fun SettingsScreen(container: AppContainer, navController: NavHostController) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(container.settingsRepository, appVersionName(context)) })
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    var showReferences by remember { mutableStateOf(false) }

    SetupScaffold(title = stringResource(R.string.set_title), onBack = { navController.popBackStack() }) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.error?.let { ErrorBanner(stringResource(R.string.set_error), it, null, null) }
            SectionHeader(stringResource(R.string.set_units))
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL), s.unitSystem, {
                    if (it == UnitSystem.METRIC) stringResource(R.string.onb_unit_metric) else stringResource(R.string.onb_unit_imperial)
                }, onSelect = vm::onUnits)
                Text(stringResource(R.string.set_week_start), style = MaterialTheme.typography.titleSmall)
                ChoiceChips(listOf(true, false), s.weekStartsOnMonday, { if (it) stringResource(R.string.set_week_monday) else stringResource(R.string.set_week_sunday) }, onSelect = vm::onWeekStart)
            }
            SettingsLink(stringResource(R.string.set_profile), stringResource(R.string.set_profile_sub)) { navController.navigate(ProfileRoute) }
            SectionHeader(stringResource(R.string.set_session))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(stringResource(R.string.set_keep_screen_on), s.keepScreenOnInSession, vm::onKeepScreenOn)
            }
            SectionHeader(stringResource(R.string.set_reminders))
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.set_reminders_note), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.set_readiness_time), style = MaterialTheme.typography.titleSmall)
                HourMinuteStepper(toMinutes(s.readinessReminderTime)) { vm.onReminderTime(toHhmm(it)) }
                Text(stringResource(R.string.set_checkin_day), style = MaterialTheme.typography.titleSmall)
                ChoiceChips((1..7).toList(), s.weeklyCheckinWeekday, { weekdayLabel(it) }, onSelect = vm::onCheckinWeekday)
            }
            SectionHeader(stringResource(R.string.set_data))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.set_catalog, state.catalogVersion), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.set_export_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsLink(stringResource(R.string.set_backend), stringResource(R.string.set_backend_sub)) { navController.navigate(BackendSettingsRoute) }
            SettingsLink(stringResource(R.string.set_advanced), stringResource(R.string.set_advanced_sub)) { navController.navigate(AdvancedConstantsRoute) }
            SectionHeader(stringResource(R.string.more_about))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.about_body, state.appVersion), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.set_licences), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.set_references), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showReferences = !showReferences },
                )
                if (showReferences) {
                    for (c in container.knowledge.rules.entries.values.map { it.citation }.distinct().sorted()) Text("• $c", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
    HorizontalDivider()
}
