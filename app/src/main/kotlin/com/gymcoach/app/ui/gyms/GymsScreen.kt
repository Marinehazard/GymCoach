package com.gymcoach.app.ui.gyms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.GymEditRoute
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.model.GymSet

/** §8.13 gyms list: ordered by last use, default badge, archive / duplicate via the overflow menu. */
@Composable
fun GymsScreen(container: AppContainer, navController: NavHostController) {
    val vm: GymsViewModel = viewModel(
        factory = vmFactory { GymsViewModel(container.gymRepository, container.settingsRepository, container.clock) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var presetDialog by remember { mutableStateOf(false) }

    SetupScaffold(
        title = stringResource(R.string.gyms_title),
        onBack = { navController.popBackStack() },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            Column {
                state.error?.let { ErrorBanner(stringResource(R.string.gyms_error), it, null, null) }
                when {
                    state.loading -> LoadingBox()
                    state.gyms.isEmpty() -> EmptyState(
                        icon = Icons.Filled.FitnessCenter,
                        title = stringResource(R.string.gyms_empty_title),
                        body = stringResource(R.string.gyms_empty_body),
                        actionText = stringResource(R.string.gyms_new),
                        onAction = { presetDialog = true },
                    )
                    else -> LazyColumn {
                        items(state.gyms, key = { it.id }) { gym ->
                            GymRow(
                                gym = gym,
                                active = gym.id == state.activeId || (state.activeId == null && gym.isDefault),
                                onOpen = { navController.navigate(GymEditRoute(gym.id, "")) },
                                onUse = { vm.onUse(gym.id) },
                                onDefault = { vm.onSetDefault(gym.id) },
                                onDuplicate = { vm.onDuplicate(gym.id) },
                                onArchive = { vm.onArchive(gym.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
            if (!state.loading && state.gyms.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { presetDialog = true },
                    modifier = Modifier.padding(16.dp).align(androidx.compose.ui.Alignment.BottomEnd),
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.gyms_new)) },
                )
            }
        }
    }

    if (presetDialog) {
        AlertDialog(
            onDismissRequest = { presetDialog = false },
            confirmButton = { TextButton(onClick = { presetDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.gyms_new_from)) },
            text = {
                ChoiceChips(
                    listOf("", Presets.BASE_GYM, Presets.HOME_GYM, Presets.HOTEL_GYM, Presets.TRAVEL_KIT),
                    null,
                    { id -> if (id.isEmpty()) stringResource(R.string.gyms_empty_gym) else presetLabel(id) },
                ) { id ->
                    presetDialog = false
                    navController.navigate(GymEditRoute("", id))
                }
            },
        )
    }
}

@Composable
private fun GymRow(
    gym: GymSet,
    active: Boolean,
    onOpen: () -> Unit,
    onUse: () -> Unit,
    onDefault: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { Box(modifier = Modifier.size(20.dp).background(GymColors[gym.colorIndex.coerceIn(0, GymColors.size - 1)], CircleShape)) },
        headlineContent = { Text(gym.name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.gyms_items, gym.items.size))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (gym.isDefault) Text(stringResource(R.string.gyms_default), color = MaterialTheme.colorScheme.primary)
                    if (active) Text(stringResource(R.string.gyms_in_use), color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        trailingContent = {
            Row {
                if (gym.isDefault) Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.gyms_default))
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.gym_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.gyms_use_today)) }, onClick = { menu = false; onUse() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.gym_set_default)) }, onClick = { menu = false; onDefault() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.gyms_duplicate)) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.gym_archive)) }, onClick = { menu = false; onArchive() })
                }
            }
        },
    )
}
