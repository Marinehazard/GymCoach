package com.gymcoach.app.ui.gyms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.app.ui.components.ConfirmDialog
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.MiniStepper
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.SwitchRow
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.GymEditRoute
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.model.EquipmentDef

/** §8.13 gym editor: name, colour, presets, search (DE/EN aliases), 8 categories with parameters, live availability footer. */
@Composable
fun GymEditScreen(container: AppContainer, navController: NavHostController, route: GymEditRoute) {
    val vm: GymEditViewModel = viewModel(
        key = "gym-edit-" + route.gymId + "-" + route.fromPreset,
        factory = vmFactory {
            GymEditViewModel(route.gymId, route.fromPreset, container.gymRepository, container.settingsRepository, container.knowledge, container.clock)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val others by vm.otherGyms.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingPreset by remember { mutableStateOf<String?>(null) }
    var pendingCopy by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<EquipmentDef?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val retryLabel = stringResource(R.string.action_retry)

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                GymEditEvent.Saved, GymEditEvent.Archived -> navController.popBackStack()
                is GymEditEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }
    LaunchedEffect(state.error) {
        val message = state.error
        if (message != null) {
            val result = snackbar.showSnackbar(message, actionLabel = retryLabel)
            if (result == SnackbarResult.ActionPerformed) vm.onSave()
        }
    }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    SetupScaffold(
        title = if (state.isNew) stringResource(R.string.gym_new) else state.gym.name.ifBlank { stringResource(R.string.gym_edit) },
        onBack = { if (state.dirty) confirmDiscard = true else navController.popBackStack() },
        snackbar = snackbar,
        actions = {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.gym_more)) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.gym_set_default)) }, onClick = { menuOpen = false; vm.onSetDefault() })
                if (!state.isNew) DropdownMenuItem(text = { Text(stringResource(R.string.gym_archive)) }, onClick = { menuOpen = false; vm.onArchive() })
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val footer = if (state.gym.items.isEmpty()) stringResource(R.string.gym_footer_empty, state.availableCount)
                    else stringResource(R.string.gym_footer, state.gym.items.size, state.availableCount, state.totalExercises)
                    Text(footer, style = MaterialTheme.typography.bodyMedium)
                    BigPrimaryButton(text = stringResource(R.string.su_save), onClick = vm::onSave, enabled = !state.saving && loaded)
                }
            }
        },
    ) { padding ->
        if (!loaded) {
            Box(modifier = Modifier.padding(padding)) { LoadingBox() }
            return@SetupScaffold
        }
        val visible = vm.visibleEquipment()
        LazyColumn(modifier = Modifier.padding(padding)) {
            item(key = "header") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = state.gym.name, onValueChange = vm::onName, label = { Text(stringResource(R.string.gym_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GymColors.forEachIndexed { index, color ->
                            val selected = index == state.gym.colorIndex
                            Box(
                                modifier = Modifier.size(32.dp).background(color, CircleShape)
                                    .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { vm.onColor(index) },
                            )
                        }
                    }
                    Text(stringResource(R.string.gym_start_from), style = MaterialTheme.typography.titleSmall)
                    ChoiceChips(
                        listOf(Presets.BASE_GYM, Presets.HOME_GYM, Presets.HOTEL_GYM, Presets.TRAVEL_KIT),
                        state.gym.presetOrigin,
                        { id -> presetLabel(id) },
                    ) { id -> if (state.gym.items.isEmpty()) vm.onStartFromPreset(id) else pendingPreset = id }
                    if (others.isNotEmpty()) {
                        Text(stringResource(R.string.gym_same_as), style = MaterialTheme.typography.titleSmall)
                        ChoiceChips(others.map { it.id }, null, { id -> others.firstOrNull { it.id == id }?.name ?: id }) { id -> pendingCopy = id }
                    }
                    val minutes = state.gym.defaultSessionMinutes
                    MiniStepper(
                        label = stringResource(R.string.gym_default_minutes),
                        value = if (minutes == null) "–" else "$minutes min",
                        onMinus = { vm.onDefaultMinutes(((minutes ?: 60) - 5).coerceAtLeast(20)) },
                        onPlus = { vm.onDefaultMinutes(((minutes ?: 55) + 5).coerceAtMost(120)) },
                    )
                    SwitchRow(stringResource(R.string.gym_travel_kit), state.gym.travelKitMerged, { vm.onToggleTravelKit() }, supporting = stringResource(R.string.gym_travel_kit_sub))
                    OutlinedTextField(value = state.query, onValueChange = vm::onQuery, label = { Text(stringResource(R.string.gym_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (state.query.isNotBlank() && visible.isEmpty()) Text(stringResource(R.string.gym_search_none), style = MaterialTheme.typography.bodyMedium)
                }
            }
            equipmentSections(
                knowledge = container.knowledge, visible = visible, gym = state.gym, expanded = state.expanded, searching = state.query.isNotBlank(),
                onToggleCategory = vm::onToggleCategory, onToggleItem = vm::onToggleItem, onParam = vm::onParam,
                onInfo = { info = it }, onAddToday = vm::onAddToday,
            )
        }
    }

    pendingPreset?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.gym_replace_title), body = stringResource(R.string.gym_replace_body),
            confirmText = stringResource(R.string.gym_replace_confirm),
            onConfirm = { vm.onStartFromPreset(id); pendingPreset = null }, onDismiss = { pendingPreset = null },
        )
    }
    pendingCopy?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.gym_replace_title), body = stringResource(R.string.gym_replace_body),
            confirmText = stringResource(R.string.gym_replace_confirm),
            onConfirm = { vm.onCopyFrom(id); pendingCopy = null }, onDismiss = { pendingCopy = null },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.gym_discard_title), body = stringResource(R.string.gym_discard_body),
            confirmText = stringResource(R.string.gym_discard_confirm),
            onConfirm = { confirmDiscard = false; navController.popBackStack() }, onDismiss = { confirmDiscard = false },
        )
    }
    info?.let { def ->
        AlertDialog(
            onDismissRequest = { info = null },
            confirmButton = { TextButton(onClick = { info = null }) { Text(stringResource(R.string.su_close)) } },
            title = { Text(def.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(def.description ?: stringResource(R.string.gym_no_description))
                    if (def.aliases.isNotEmpty()) Text(stringResource(R.string.gym_aliases, def.aliases.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                    if (def.packable) Text(stringResource(R.string.gym_packable), style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
}

@Composable
fun presetLabel(id: String): String = when (id) {
    Presets.BASE_GYM -> stringResource(R.string.su_preset_commercial)
    Presets.HOME_GYM -> stringResource(R.string.su_preset_home)
    Presets.HOTEL_GYM -> stringResource(R.string.su_preset_hotel)
    Presets.TRAVEL_KIT -> stringResource(R.string.su_preset_bands)
    else -> id
}
