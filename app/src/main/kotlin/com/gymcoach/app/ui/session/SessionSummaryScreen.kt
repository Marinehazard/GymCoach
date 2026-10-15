package com.gymcoach.app.ui.session

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.nav.SessionSummaryRoute

/** WP-01 placeholder (§9.1); owned by WP-07 from Phase 1 on. Frozen signature. */
@Composable
fun SessionSummaryScreen(container: AppContainer, navController: NavHostController, route: SessionSummaryRoute) { Text("Session summary ${route.sessionId}") }
