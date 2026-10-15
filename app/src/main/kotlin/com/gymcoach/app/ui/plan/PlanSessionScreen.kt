package com.gymcoach.app.ui.plan

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.nav.PlanSessionRoute

/** WP-01 placeholder (§9.1); owned by WP-07 from Phase 1 on. Frozen signature. */
@Composable
fun PlanSessionScreen(container: AppContainer, navController: NavHostController, route: PlanSessionRoute) { Text("Planned session ${route.plannedSessionId}") }
