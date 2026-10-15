package com.gymcoach.app.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.nav.ExerciseProgressRoute
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.app.ui.today.trainingDepsOf
import com.gymcoach.core.model.UnitSystem

/** §8.11 Progress: strength, volume, body, readiness & sleep, cardio, consistency, training load, mobility; each card has a "need N more" state. */
@Composable
fun ProgressScreen(container: AppContainer, navController: NavHostController) {
    val vm: ProgressViewModel = viewModel { ProgressViewModel(trainingDepsOf(container)) }
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.observe().collectAsStateWithLifecycle(initialValue = null)
    var direct by remember { mutableStateOf(true) }
    if (s.loading) { LoadingBox(); return }
    val unit = if (settings?.unitSystem == UnitSystem.IMPERIAL) "lb" else "kg"
    val onRule: (String) -> Unit = { navController.navigate(RuleDetailRoute(it)) }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { RangeChips(s.range, vm::onRange) }
        s.error?.let { e -> item { ErrorBanner(stringResource(R.string.progress_error), e, { vm.onRange(s.range) }, null) } }
        item { StrengthCard(s.strength, unit) { navController.navigate(ExerciseProgressRoute(it)) } }
        item { VolumeCard(s, direct) { direct = !direct } }
        item { BodyCard(s, unit) }
        item { ReadinessCard(s) }
        item { WeeklyNumbersCard(stringResource(R.string.progress_cardio), s.cardioMinutesByWeek.map { it.first to "${it.second} min" }, stringResource(R.string.progress_need_cardio)) }
        item { WeeklyNumbersCard(stringResource(R.string.progress_consistency), s.consistency, stringResource(R.string.progress_need_sessions, 1)) }
        item { TrainingLoadCard(s, onRule) }
        item { MobilityCard(s.mobilityMinutes) }
    }
}
