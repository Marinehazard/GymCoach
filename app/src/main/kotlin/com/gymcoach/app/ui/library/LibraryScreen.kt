package com.gymcoach.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.MultiChips
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.SwitchRow
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.ExerciseDetailRoute
import com.gymcoach.core.model.Difficulty
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import java.util.Locale

/** "horizontal_push" style enum names for filter chips: "Horizontal push". */
fun prettyEnum(name: String): String =
    name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

@Composable
private fun flagLabel(flag: String): String = when (flag) {
    LibraryFlags.NO_SPINAL -> stringResource(R.string.lib_flag_no_spinal)
    LibraryFlags.KNEE_FRIENDLY -> stringResource(R.string.lib_flag_knee)
    LibraryFlags.SHOULDER_FRIENDLY -> stringResource(R.string.lib_flag_shoulder)
    LibraryFlags.BACK_FRIENDLY -> stringResource(R.string.lib_flag_back)
    else -> stringResource(R.string.lib_flag_no_spotter)
}

/** §8.12: search names and aliases (EN/DE) with filter chips. */
@Composable
fun LibraryScreen(container: AppContainer, navController: NavHostController) {
    val vm: LibraryViewModel = viewModel(
        factory = vmFactory { LibraryViewModel(container.knowledge, container.gymRepository, container.settingsRepository) },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    SetupScaffold(title = stringResource(R.string.lib_title), onBack = { navController.popBackStack() }) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.query, onValueChange = vm::onQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.lib_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                )
                ChoiceChips(MovementPattern.values().toList(), state.pattern, { prettyEnum(it.name) }) { vm.onPattern(if (it == state.pattern) null else it) }
                ChoiceChips(Muscle.values().toList(), state.muscle, { prettyEnum(it.name) }) { vm.onMuscle(if (it == state.muscle) null else it) }
                ChoiceChips(Difficulty.values().toList(), state.difficulty, { prettyEnum(it.name) }) { vm.onDifficulty(if (it == state.difficulty) null else it) }
                MultiChips(LibraryFlags.ALL, state.flagFilters, { flagLabel(it) }, onToggle = vm::onFlag)
                SwitchRow(
                    label = vm.gymName?.let { stringResource(R.string.lib_available_in, it) } ?: stringResource(R.string.lib_available_none),
                    checked = state.availableOnly, onChange = vm::onAvailableOnly,
                )
            }
            HorizontalDivider()
            if (state.results.isEmpty()) {
                EmptyState(Icons.Filled.Search, stringResource(R.string.lib_empty_title), stringResource(R.string.lib_empty_body), stringResource(R.string.lib_clear), vm::onClearFilters)
            } else {
                LazyColumn {
                    items(state.results, key = { it.exercise.id }) { row ->
                        ListItem(
                            modifier = Modifier.clickable { navController.navigate(ExerciseDetailRoute(row.exercise.id)) },
                            headlineContent = { Text(row.exercise.name) },
                            supportingContent = { Text(prettyEnum(row.exercise.pattern.name) + " · " + prettyEnum(row.exercise.difficulty.name)) },
                            trailingContent = {
                                if (row.availableInGym) Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.lib_available_cd))
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
