package com.gymcoach.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.checkin.CheckinScreen
import com.gymcoach.app.ui.coach.CoachScreen
import com.gymcoach.app.ui.coach.ConversationScreen
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.gyms.GymEditScreen
import com.gymcoach.app.ui.gyms.GymsScreen
import com.gymcoach.app.ui.knowledge.KnowledgeScreen
import com.gymcoach.app.ui.knowledge.RuleDetailScreen
import com.gymcoach.app.ui.library.ExerciseDetailScreen
import com.gymcoach.app.ui.library.LibraryScreen
import com.gymcoach.app.ui.more.MoreScreen
import com.gymcoach.app.ui.nutrition.NutritionScreen
import com.gymcoach.app.ui.onboarding.OnboardingScreen
import com.gymcoach.app.ui.plan.AdjustmentLogScreen
import com.gymcoach.app.ui.plan.PlanScreen
import com.gymcoach.app.ui.plan.PlanSessionScreen
import com.gymcoach.app.ui.profile.ProfileScreen
import com.gymcoach.app.ui.progress.ExerciseProgressScreen
import com.gymcoach.app.ui.progress.ProgressScreen
import com.gymcoach.app.ui.readiness.ReadinessScreen
import com.gymcoach.app.ui.session.SessionScreen
import com.gymcoach.app.ui.session.SessionSummaryScreen
import com.gymcoach.app.ui.settings.AdvancedConstantsScreen
import com.gymcoach.app.ui.settings.BackendSettingsScreen
import com.gymcoach.app.ui.settings.SettingsScreen
import com.gymcoach.app.ui.today.TodayScreen
import com.gymcoach.app.ui.usage.UsageScreen
import kotlinx.coroutines.flow.first

/**
 * §8.3: one `composable<T>` per route calling `XxxScreen(container, navController[, route])`.
 * Start destination: OnboardingRoute until `settings.onboardingDone`; else TodayRoute; an IN_PROGRESS
 * session opens SessionRoute directly. The bottom bar shows on the five tab routes only.
 */
@Composable
fun AppNavHost(container: AppContainer, navController: NavHostController) {
    val startDestination: Any? by produceState<Any?>(initialValue = null, container) {
        value = resolveStartDestination(container)
    }
    val start = startDestination
    if (start == null) {
        LoadingBox()
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination: NavDestination? = backStackEntry?.destination
    val showBottomBar = currentDestination != null && isTabDestination(currentDestination)

    Scaffold(
        bottomBar = { if (showBottomBar) BottomBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.padding(innerPadding),
        ) {
            // Tabs
            composable<TodayRoute> { TodayScreen(container, navController) }
            composable<PlanRoute> { PlanScreen(container, navController) }
            composable<ProgressRoute> { ProgressScreen(container, navController) }
            composable<CoachRoute> { CoachScreen(container, navController) }
            composable<MoreRoute> { MoreScreen(container, navController) }

            // Full-screen destinations
            composable<OnboardingRoute> { OnboardingScreen(container, navController) }
            composable<ReadinessRoute> { entry -> ReadinessScreen(container, navController, entry.toRoute<ReadinessRoute>()) }
            composable<SessionRoute> { entry -> SessionScreen(container, navController, entry.toRoute<SessionRoute>()) }
            composable<SessionSummaryRoute> { entry -> SessionSummaryScreen(container, navController, entry.toRoute<SessionSummaryRoute>()) }
            composable<PlanSessionRoute> { entry -> PlanSessionScreen(container, navController, entry.toRoute<PlanSessionRoute>()) }
            composable<ExerciseProgressRoute> { entry -> ExerciseProgressScreen(container, navController, entry.toRoute<ExerciseProgressRoute>()) }
            composable<GymsRoute> { GymsScreen(container, navController) }
            composable<GymEditRoute> { entry -> GymEditScreen(container, navController, entry.toRoute<GymEditRoute>()) }
            composable<LibraryRoute> { LibraryScreen(container, navController) }
            composable<ExerciseDetailRoute> { entry -> ExerciseDetailScreen(container, navController, entry.toRoute<ExerciseDetailRoute>()) }
            composable<ProfileRoute> { ProfileScreen(container, navController) }
            composable<CheckinRoute> { CheckinScreen(container, navController) }
            composable<NutritionRoute> { NutritionScreen(container, navController) }
            composable<KnowledgeRoute> { KnowledgeScreen(container, navController) }
            composable<RuleDetailRoute> { entry -> RuleDetailScreen(container, navController, entry.toRoute<RuleDetailRoute>()) }
            composable<AdjustmentLogRoute> { AdjustmentLogScreen(container, navController) }
            composable<ConversationRoute> { entry -> ConversationScreen(container, navController, entry.toRoute<ConversationRoute>()) }
            composable<SettingsRoute> { SettingsScreen(container, navController) }
            composable<BackendSettingsRoute> { BackendSettingsScreen(container, navController) }
            composable<AdvancedConstantsRoute> { AdvancedConstantsScreen(container, navController) }
            composable<UsageRoute> { UsageScreen(container, navController) }
        }
    }
}

/** Reads settings and the open session once; local persistence only (HR-14 concerns network work). */
private suspend fun resolveStartDestination(container: AppContainer): Any {
    val settings = container.settingsRepository.get()
    if (!settings.onboardingDone) return OnboardingRoute
    val open = container.workoutRepository.observeOpenSession().first()
    return if (open != null) SessionRoute(open.id) else TodayRoute
}

/** True for the five tab routes of §8.3. */
fun isTabDestination(destination: NavDestination): Boolean =
    destination.hasRoute<TodayRoute>() ||
        destination.hasRoute<PlanRoute>() ||
        destination.hasRoute<ProgressRoute>() ||
        destination.hasRoute<CoachRoute>() ||
        destination.hasRoute<MoreRoute>()
