@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.more

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.nav.CheckinRoute
import com.gymcoach.app.ui.nav.GymsRoute
import com.gymcoach.app.ui.nav.KnowledgeRoute
import com.gymcoach.app.ui.nav.LibraryRoute
import com.gymcoach.app.ui.nav.NutritionRoute
import com.gymcoach.app.ui.nav.ProfileRoute
import com.gymcoach.app.ui.nav.SettingsRoute
import com.gymcoach.app.ui.nav.UsageRoute

/**
 * §8.19 More: links to Gyms, Library, Profile, Check-in, Nutrition, Knowledge, Settings, Usage, About.
 * This file also hosts the small helpers every WP-08 screen shares (scaffold, view-model factory,
 * chip rows), because WP-08 owns no other common file.
 */
@Composable
fun MoreScreen(container: AppContainer, navController: NavHostController) {
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SetupScaffold(title = stringResource(R.string.more_title), onBack = null) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            MoreRow(Icons.Filled.FitnessCenter, stringResource(R.string.more_gyms), stringResource(R.string.more_gyms_sub)) { navController.navigate(GymsRoute) }
            MoreRow(Icons.Filled.Search, stringResource(R.string.more_library), stringResource(R.string.more_library_sub)) { navController.navigate(LibraryRoute) }
            MoreRow(Icons.Filled.Person, stringResource(R.string.more_profile), stringResource(R.string.more_profile_sub)) { navController.navigate(ProfileRoute) }
            MoreRow(Icons.Filled.Checklist, stringResource(R.string.more_checkin), stringResource(R.string.more_checkin_sub)) { navController.navigate(CheckinRoute) }
            MoreRow(Icons.Filled.Restaurant, stringResource(R.string.more_nutrition), stringResource(R.string.more_nutrition_sub)) { navController.navigate(NutritionRoute) }
            MoreRow(Icons.Filled.School, stringResource(R.string.more_knowledge), stringResource(R.string.more_knowledge_sub)) { navController.navigate(KnowledgeRoute) }
            MoreRow(Icons.Filled.Settings, stringResource(R.string.more_settings), stringResource(R.string.more_settings_sub)) { navController.navigate(SettingsRoute) }
            MoreRow(Icons.Filled.BarChart, stringResource(R.string.more_usage), stringResource(R.string.more_usage_sub)) { navController.navigate(UsageRoute) }
            MoreRow(Icons.Filled.Info, stringResource(R.string.more_about), stringResource(R.string.more_about_sub)) { showAbout = true }
        }
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.su_close)) } },
            title = { Text(stringResource(R.string.about_title)) },
            text = { Text(stringResource(R.string.about_body, appVersionName(context))) },
        )
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
    HorizontalDivider()
}

/** versionName from the package manager; "?" if it cannot be read. */
fun appVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
}

/** ViewModelProvider.Factory around a plain constructor call (no reflection, HR-5). */
fun <T : ViewModel> vmFactory(make: () -> T): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
}

/** Top bar + optional snackbar host shared by every WP-08 screen. */
@Composable
fun SetupScaffold(
    title: String,
    onBack: (() -> Unit)?,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.su_cd_back))
                        }
                    }
                },
                actions = actions,
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        bottomBar = bottomBar,
        content = content,
    )
}

/** Horizontally scrolling single-choice chip row. */
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: @Composable (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

/** Horizontally scrolling multi-choice chip row. */
@Composable
fun <T> MultiChips(options: List<T>, selected: Set<T>, label: @Composable (T) -> String, modifier: Modifier = Modifier, onToggle: (T) -> Unit) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) {
            FilterChip(selected = option in selected, onClick = { onToggle(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(text = supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Compact minus / value / plus row (48 dp buttons) for settings-style numeric entry. */
@Composable
fun MiniStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(imageVector = Icons.Filled.Remove, contentDescription = stringResource(R.string.cd_decrease))
        }
        Text(text = value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
        IconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = stringResource(R.string.cd_increase))
        }
    }
}
