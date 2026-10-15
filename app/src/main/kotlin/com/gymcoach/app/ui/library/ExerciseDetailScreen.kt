package com.gymcoach.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.LineChart
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.ExerciseDetailRoute
import com.gymcoach.app.ui.nav.ExerciseProgressRoute
import com.gymcoach.core.model.ContentStatus
import com.gymcoach.core.units.Units

/** §8.12 detail: the info-sheet content, availability, e1RM history, "Add to today's session". */
@Composable
fun ExerciseDetailScreen(container: AppContainer, navController: NavHostController, route: ExerciseDetailRoute) {
    val vm: ExerciseDetailViewModel = viewModel(
        key = "exercise-" + route.exerciseId,
        factory = vmFactory {
            ExerciseDetailViewModel(
                exerciseId = route.exerciseId, knowledge = container.knowledge, gyms = container.gymRepository,
                settings = container.settingsRepository, profiles = container.profileRepository,
                progression = container.progressionRepository, workouts = container.workoutRepository, clock = container.clock,
                e1rmSeries = { states, id -> container.progressSeries.e1rm(states, id) },
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val exercise = state.exercise

    SetupScaffold(title = exercise?.name ?: stringResource(R.string.lib_title), onBack = { navController.popBackStack() }) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { ErrorBanner(stringResource(R.string.lib_detail_error), it, null, null) }
            if (exercise == null) return@Column
            Text(prettyEnum(exercise.pattern.name) + " · " + prettyEnum(exercise.difficulty.name) + " · " + prettyEnum(exercise.loadType.name), style = MaterialTheme.typography.titleSmall)
            Text(
                if (state.availableInGym) stringResource(R.string.lib_detail_available)
                else stringResource(R.string.lib_detail_missing, state.missing.joinToString(", ").ifBlank { "?" }),
                color = if (state.availableInGym) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            val c = state.content
            if (c == null || c.description.isBlank()) {
                Text(stringResource(R.string.lib_detail_not_written), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(c.description, style = MaterialTheme.typography.bodyLarge)
                Bullets(stringResource(R.string.lib_detail_setup), c.setup)
                Bullets(stringResource(R.string.lib_detail_cues), c.cues)
                Bullets(stringResource(R.string.lib_detail_mistakes), c.mistakes)
                Bullets(stringResource(R.string.lib_detail_safety), c.safety)
                if (c.substitutionRationale.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.lib_detail_subs))
                    for ((id, why) in c.substitutionRationale) {
                        Text("• " + (container.knowledge.exercise(id)?.name ?: id) + ": " + why, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Bullets(stringResource(R.string.lib_detail_sources), c.sources)
                if (state.contentStatus != ContentStatus.REVIEWED) {
                    Text(stringResource(R.string.lib_detail_draft), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.state?.let { st ->
                SectionHeader(stringResource(R.string.lib_detail_progress))
                val load = st.workingLoadKg?.let { Units.trim(it) + " kg" } ?: "–"
                Text(stringResource(R.string.lib_detail_state, load, st.workingReps.lo, st.workingReps.hi, st.targetRir, st.status.name.lowercase()))
            }
            state.history?.let { LineChart(listOf(it), Modifier.fillMaxWidth(), "e1RM kg") }
            OutlinedButton(onClick = { navController.navigate(ExerciseProgressRoute(exercise.id)) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lib_detail_open_progress))
            }
            if (state.canAddToday) BigPrimaryButton(text = stringResource(R.string.lib_detail_add_today), onClick = vm::onAddToday)
        }
    }
}

@Composable
private fun Bullets(title: String, items: List<String>) {
    if (items.isEmpty()) return
    SectionHeader(title)
    for (line in items) Text("• $line", style = MaterialTheme.typography.bodyMedium)
}
