@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
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
import com.gymcoach.app.ui.components.LineChart
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.nav.ExerciseProgressRoute
import com.gymcoach.app.ui.today.trainingDepsOf
import com.gymcoach.core.units.Units

/** e1RM (dashed when LOW) or load × reps per gym, best sets, state; gym-scope and range chips (§8.11). */
@Composable
fun ExerciseProgressScreen(container: AppContainer, navController: NavHostController, route: ExerciseProgressRoute) {
    val vm: ExerciseProgressViewModel = viewModel(key = "xp-" + route.exerciseId) { ExerciseProgressViewModel(route.exerciseId, trainingDepsOf(container)) }
    val s by vm.state.collectAsStateWithLifecycle()
    if (s.loading) { LoadingBox(); return }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(s.exercise?.name ?: stringResource(R.string.session_unavailable), style = MaterialTheme.typography.headlineSmall) }
        s.error?.let { e -> item { ErrorBanner(stringResource(R.string.progress_error), e, null, null) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in listOf(30, 90, 180, 365)) FilterChip(selected = s.range == d, onClick = { vm.onRange(d) }, label = { Text(stringResource(R.string.progress_days, d)) })
            }
        }
        if (s.gymScopes.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.selectedScope == null, onClick = { vm.onGymScope(null) }, label = { Text(stringResource(R.string.progress_all_gyms)) })
                for (g in s.gymScopes) FilterChip(selected = s.selectedScope == g, onClick = { vm.onGymScope(g) }, label = { Text(g.take(8)) })
            }
        }
        item {
            val shown = s.loadXReps ?: s.series
            if (shown.points.size >= 2) LineChart(series = listOf(shown), modifier = Modifier.fillMaxWidth().height(200.dp), yLabel = if (s.loadXReps != null) "kg × reps" else "kg e1RM")
            else Text(stringResource(R.string.progress_need_sessions, 3))
        }
        s.state?.let { st ->
            item { Text(stringResource(R.string.progress_state, st.status.name.lowercase(), st.workingLoadKg?.let { Units.trim(it) + " kg" } ?: "–"), style = MaterialTheme.typography.titleMedium) }
        }
        item { Text(stringResource(R.string.progress_best_sets), style = MaterialTheme.typography.titleMedium) }
        items(s.bestSets.size) { i ->
            val b = s.bestSets[i]
            Text((b.weight?.let { Units.formatWeight(it, it.unit) + " × " } ?: "") + (b.reps ?: b.seconds ?: 0) + (b.rir?.let { " @$it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
