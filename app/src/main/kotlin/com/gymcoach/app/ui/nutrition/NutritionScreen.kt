package com.gymcoach.app.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Card
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
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.app.ui.components.ErrorBanner
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.components.NumberField
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.nav.RuleDetailRoute

/** §8.16 Nutrition: targets with rule tags, energy model assumptions, weigh-in (+ steps), trend, review card, daily log, supplements. */
@Composable
fun NutritionScreen(container: AppContainer, navController: NavHostController) {
    val vm: NutritionViewModel = viewModel(
        factory = vmFactory {
            NutritionViewModel(
                profiles = container.profileRepository, metrics = container.metricsRepository, plans = container.planRepository,
                settings = container.settingsRepository, knowledge = container.knowledge, clock = container.clock,
                constants = { container.planCoordinator.constants() },
                energyOf = { c -> container.energyEstimator(c) }, macrosOf = { c -> container.macroTargets(c) },
                trendOf = { c -> container.weightTrend(c) }, supplementsOf = { c -> container.supplementAdvisor(c) },
                trainingAgeOf = { p, d -> container.planner(container.planCoordinator.constants()).trainingAgeOf(p, d) },
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val onRule: (String) -> Unit = { navController.navigate(RuleDetailRoute(it)) }

    SetupScaffold(title = stringResource(R.string.nut_title), onBack = { navController.popBackStack() }) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                extras.error?.let { ErrorBanner(stringResource(R.string.nut_error), it, null, null) }
                when {
                    extras.loading -> LoadingBox()
                    extras.noProfile -> EmptyState(Icons.Filled.Restaurant, stringResource(R.string.nut_no_profile_title), stringResource(R.string.nut_no_profile_body), null, null)
                    else -> {
                        if (state.targets == null) Text(stringResource(R.string.nut_need_weight), style = MaterialTheme.typography.bodyLarge)
                        state.targets?.let { TargetsCard(it, onRule) }
                        state.reviewCard?.let { review ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(stringResource(R.string.nut_review), style = MaterialTheme.typography.titleMedium)
                                    ExplanationChip(review, onRule)
                                }
                            }
                        }
                        EnergyCard(state, vm::onActivityFactor, onRule)
                        WeighInCard(state, extras.unit, extras.weighIns, vm::onWeightValue, vm::onStepsValue) { vm.onWeighIn(state.weightValue, state.stepsValue) }
                        DailyLogCard(
                            state = state, onKcal = vm::onKcalValue, onSaveEntry = { protein -> vm.onLog(kcal = state.kcalValue, proteinG = protein) },
                            onProteinHit = { vm.onLog(proteinHit = it) }, onMeals = { vm.onLog(meals = it) },
                            onAlcohol = { vm.onLog(alcohol = it) }, onTravelDay = { vm.onLog(travelDay = it) },
                        )
                        NumberField(state.bodyFatPct, vm::onBodyFat, stringResource(R.string.nut_body_fat), "%", Modifier.fillMaxWidth())
                    }
                }
            }
            if (!extras.loading && !extras.noProfile) {
                SupplementsSection(
                    items = state.supplements, priority = container.knowledge.supplements.priorityOrder,
                    caveat = container.knowledge.supplements.caveatSup12, gate = state.medicalGate, onRule = onRule,
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nut_tips), style = MaterialTheme.typography.titleMedium)
                    for (tip in state.tips) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(tip.title, style = MaterialTheme.typography.titleSmall)
                                Text(tip.body, style = MaterialTheme.typography.bodyMedium)
                                Text(tip.ruleId + " · " + tip.confidence.name.lowercase() + " · " + tip.citation, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
