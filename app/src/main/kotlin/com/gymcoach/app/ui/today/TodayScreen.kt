@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.components.ProposalCard
import com.gymcoach.app.ui.nav.AdjustmentLogRoute
import com.gymcoach.app.ui.nav.CheckinRoute
import com.gymcoach.app.ui.nav.ConversationRoute
import com.gymcoach.app.ui.nav.GymEditRoute
import com.gymcoach.app.ui.nav.NutritionRoute
import com.gymcoach.app.ui.nav.OnboardingRoute
import com.gymcoach.app.ui.nav.ReadinessRoute
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.app.ui.nav.SessionRoute
import com.gymcoach.app.ui.nav.SessionSummaryRoute
import com.gymcoach.core.mobility.MobilityPlanner

/** Builds the narrow dependency holder of the WP-07 ViewModels from the composition root (§1.5). */
fun trainingDepsOf(container: AppContainer): TrainingDeps = TrainingDeps(
    clock = container.clock, knowledge = container.knowledge, settings = container.settingsRepository, profile = container.profileRepository,
    gyms = container.gymRepository, plans = container.planRepository, workouts = container.workoutRepository,
    progression = container.progressionRepository, metrics = container.metricsRepository, coach = container.coachRepository,
    coordinator = container.planCoordinator, proposalApplier = container.proposalApplier, progressSeries = container.progressSeries,
    plannerFactory = { c -> container.planner(c) }, engineFactory = { c -> container.progressionEngine(c) },
    substitutionFactory = { c -> container.substitutionEngine(c) }, readinessFactory = { c -> container.readinessScorer(c) },
    supplementFactory = { c -> container.supplementAdvisor(c) }, validatorFactory = { c -> container.mutationValidator(c) },
    mobilityFactory = { c -> MobilityPlanner(container.knowledge, c) },
)

/** §8.6 Today. */
@Composable
fun TodayScreen(container: AppContainer, navController: NavHostController) {
    val vm: TodayViewModel = viewModel { TodayViewModel(trainingDepsOf(container)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val cardioDraft by vm.cardioDraft.collectAsStateWithLifecycle()
    val gymInfo by vm.gymInfo.collectAsStateWithLifecycle()
    val referral by vm.referral.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val kb = container.knowledge
    val onRule: (String) -> Unit = { navController.navigate(RuleDetailRoute(it)) }

    LaunchedEffect(vm) {
        vm.navigation.collect { nav ->
            when (nav) {
                is TodayNav.Session -> navController.navigate(SessionRoute(nav.sessionId))
                is TodayNav.Readiness -> navController.navigate(ReadinessRoute(nav.date))
                is TodayNav.GymEdit -> navController.navigate(GymEditRoute(nav.gymId, ""))
                is TodayNav.Conversation -> navController.navigate(ConversationRoute(nav.conversationId))
                is TodayNav.SessionSummary -> navController.navigate(SessionSummaryRoute(nav.sessionId))
                TodayNav.Onboarding -> navController.navigate(OnboardingRoute)
                TodayNav.Checkin -> navController.navigate(CheckinRoute)
                TodayNav.Nutrition -> navController.navigate(NutritionRoute)
                TodayNav.AdjustmentLog -> navController.navigate(AdjustmentLogRoute)
            }
        }
    }

    if (state.loading) { LoadingBox(); return }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TodayHeader(state, onGym = { vm.onOpenSheet(TodaySheet.GYM_SWITCHER) }, onReadiness = vm::onOpenReadiness) }
        state.error?.let { e ->
            item { ErrorBanner(stringResource(R.string.today_error_title), e, vm::refresh, null) }
        }
        val open = state.openSession
        val plan = state.sessionPlan
        when {
            state.noPlan -> item {
                EmptyState(Icons.Filled.FitnessCenter, stringResource(R.string.today_no_plan_title), stringResource(R.string.today_no_plan_body),
                    stringResource(R.string.today_no_plan_action), vm::onOpenOnboarding)
            }
            state.gym == null -> item {
                EmptyState(Icons.Filled.FitnessCenter, stringResource(R.string.today_pick_gym), stringResource(R.string.today_pick_gym_body),
                    stringResource(R.string.today_pick_gym), { vm.onOpenSheet(TodaySheet.GYM_SWITCHER) })
            }
            open != null -> item { ResumeCard(open, container.clock.now().toEpochMilli(), vm::onResumeSession) }
            plan != null && plan.blocks.isNotEmpty() -> {
                item { ModifierSection(state, vm::onTrainAsPlanned, onRule) }
                item { SessionCard(state, plan, kb, { vm.onOpenSheet(TodaySheet.SESSION_START) }, { vm.onOpenSheet(TodaySheet.PREVIEW) }, { vm.onMinutesToday(30) }, { vm.onSkipToday("skipped") }) }
            }
            state.restDay || (plan != null && plan.blocks.isEmpty()) -> item { RestDayCard(state, vm::onLogCardio) }
            else -> item { ErrorBanner(stringResource(R.string.today_error_title), stringResource(R.string.today_error_body), vm::refresh, null) }
        }
        item { CardioCard(state, kb, vm::onLogCardio, onRule) }
        state.deloadSuggestion?.let { e -> item { DeloadCard(e, vm::onAcceptDeload, vm::onDismissDeload, onRule) } }
        referral?.let { r -> item { com.gymcoach.app.ui.components.ExplanationChip(explanation = r, onRule = onRule) } }
        items2(state.pendingProposals) { p ->
            ProposalCard(p, null, { vm.onProposalApply(p.id) }, { vm.onProposalReject(p.id) }, { vm.onProposalLater(p.id) }, null)
        }
        items2(state.plannerProposals) { m -> PlannerProposalCard(m, { vm.onPlannerProposal(m, true) }, { vm.onPlannerProposal(m, false) }) }
        if (state.weeklyCheckinDue) item {
            OutlinedButton(onClick = { navController.navigate(CheckinRoute) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.today_checkin_due)) }
        }
        items2(reminders) { q -> OutlinedButton(onClick = { navController.navigate(CheckinRoute) }, modifier = Modifier.fillMaxWidth()) { Text(q.text) } }
        items2(state.underTarget) { e -> com.gymcoach.app.ui.components.ExplanationChip(explanation = e, onRule = onRule) }
        item { NutritionStripCard(state.nutritionStrip) { navController.navigate(NutritionRoute) } }
        state.yesterday?.let { y ->
            item { OutlinedButton(onClick = vm::onOpenYesterday, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.today_yesterday, y.date.toString())) } }
        }
        item { OutlinedButton(onClick = { navController.navigate(AdjustmentLogRoute) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.today_changes)) } }
    }

    val current = sheet
    if (current != null) {
        ModalBottomSheet(onDismissRequest = { vm.onOpenSheet(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (current) {
                TodaySheet.GYM_SWITCHER -> GymSwitcherContent(
                    state.gyms, state.gym?.id, gymInfo, kb.presets.values.toList(),
                    usedDay = { g -> g.lastUsedAt?.atZone(container.clock.zone())?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) },
                    onSelect = vm::onSelectGym, onNewFromPreset = vm::onNewGymFromPreset, onDuplicate = vm::onDuplicateCurrentGym,
                )
                TodaySheet.SESSION_START -> SessionStartContent(
                    state, kb, vm::onToggleSpotter, vm::onRemoveEquipmentToday, vm::onAddExtraItem, vm::onMinutesToday, vm::onOpenReadiness, vm::onStartSession,
                )
                TodaySheet.CARDIO_LOG -> cardioDraft?.let { d -> CardioLogContent(d, vm.cardioModalities(), vm::onCardioDraft, vm::onCardioSave) }
                TodaySheet.PREVIEW -> state.sessionPlan?.let { PreviewContent(it, kb) }
            }
        }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.items2(list: List<T>, content: @Composable (T) -> Unit) {
    items(list.size) { content(list[it]) }
}
