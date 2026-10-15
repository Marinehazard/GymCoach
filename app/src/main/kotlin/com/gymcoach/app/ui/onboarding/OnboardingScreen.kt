package com.gymcoach.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.GymEditRoute
import com.gymcoach.app.ui.nav.OnboardingRoute
import com.gymcoach.app.ui.nav.TodayRoute
import java.time.LocalDate

/** §8.5: six pages, profile + gym + generated first week. */
@Composable
fun OnboardingScreen(container: AppContainer, navController: NavHostController) {
    val vm: OnboardingViewModel = viewModel(
        factory = vmFactory {
            OnboardingViewModel(
                profiles = container.profileRepository,
                gyms = container.gymRepository,
                metrics = container.metricsRepository,
                settings = container.settingsRepository,
                knowledge = container.knowledge,
                clock = container.clock,
                buildPlan = { container.planCoordinator.recompute("onboarding") },
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val built by vm.built.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val knowledge = container.knowledge
    val presets = remember(knowledge) {
        listOf("base_gym", "home_gym", "hotel_gym", "travel_kit").mapNotNull { id -> knowledge.presets[id]?.let { id to it.name } }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is OnboardingEvent.OpenGymEditor -> navController.navigate(GymEditRoute(event.gymId, ""))
                OnboardingEvent.Finished -> navController.navigate(TodayRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
            }
        }
    }
    BackHandler(enabled = state.page > 1) { vm.onBack() }

    SetupScaffold(
        title = stringResource(R.string.onb_title, state.page, 6),
        onBack = if (state.page > 1) ({ vm.onBack() }) else null,
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val label = when (state.page) {
                        1 -> stringResource(R.string.onb_get_started)
                        5 -> stringResource(R.string.onb_build_plan)
                        6 -> stringResource(R.string.onb_start)
                        else -> stringResource(R.string.su_next)
                    }
                    BigPrimaryButton(
                        text = label,
                        onClick = { if (state.page == 6) vm.onFinish() else vm.onNext() },
                        enabled = !state.building && !(state.page == 6 && built == null),
                    )
                    if (state.page == 4 || state.page == 5) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = vm::onSkipPage) { Text(stringResource(R.string.su_skip)) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            LinearProgressIndicator(progress = { state.page / 6f }, modifier = Modifier.fillMaxWidth())
            when (state.page) {
                1 -> WelcomePage()
                2 -> AboutPage(state.draft, extras, vm::onDraftChange)
                3 -> GoalPage(state.draft, vm::onDraftChange)
                4 -> ExperiencePage(state.draft, today, vm::onDraftChange)
                5 -> GymPage(
                    state = state,
                    extras = extras,
                    unitSystem = state.draft.unitSystem,
                    presets = presets,
                    onPreset = vm::onPresetSelected,
                    onName = vm::onGymName,
                    onReview = vm::onReviewEquipment,
                    onKnownLoad = { id, text -> vm.onDraftChange(state.draft.copy(knownLoads = state.draft.knownLoads + (id to text))) },
                    exerciseName = { id -> knowledge.exercise(id)?.name ?: id },
                )
                else -> WeekPage(state, built)
            }
        }
    }
}
