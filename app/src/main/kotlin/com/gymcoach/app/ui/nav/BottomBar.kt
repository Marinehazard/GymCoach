package com.gymcoach.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.gymcoach.app.R

/** §8.3 tabs: Today · Plan · Progress · Coach · More. Shown on the five tab routes only (AppNavHost). */
@Composable
fun BottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination: NavDestination? = backStackEntry?.destination
    NavigationBar {
        TabItem(
            selected = destination?.hasRoute<TodayRoute>() == true,
            label = stringResource(R.string.tab_today),
            icon = Icons.Filled.Today,
            onClick = { navigateToTab(navController, TodayRoute) },
        )
        TabItem(
            selected = destination?.hasRoute<PlanRoute>() == true,
            label = stringResource(R.string.tab_plan),
            icon = Icons.Filled.CalendarMonth,
            onClick = { navigateToTab(navController, PlanRoute) },
        )
        TabItem(
            selected = destination?.hasRoute<ProgressRoute>() == true,
            label = stringResource(R.string.tab_progress),
            icon = Icons.Filled.ShowChart,
            onClick = { navigateToTab(navController, ProgressRoute) },
        )
        TabItem(
            selected = destination?.hasRoute<CoachRoute>() == true,
            label = stringResource(R.string.tab_coach),
            icon = Icons.Filled.Chat,
            onClick = { navigateToTab(navController, CoachRoute) },
        )
        TabItem(
            selected = destination?.hasRoute<MoreRoute>() == true,
            label = stringResource(R.string.tab_more),
            icon = Icons.Filled.MoreHoriz,
            onClick = { navigateToTab(navController, MoreRoute) },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = label) },
        label = { Text(label) },
        alwaysShowLabel = true,
    )
}

/** Standard tab navigation: single top, state saved/restored per tab, back pops to the start tab. */
private fun navigateToTab(navController: NavHostController, route: Any) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
