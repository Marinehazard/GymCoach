package com.gymcoach.app.ui.gyms

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.nav.GymEditRoute

/** WP-01 placeholder (§9.1); owned by WP-08 from Phase 1 on. Frozen signature. */
@Composable
fun GymEditScreen(container: AppContainer, navController: NavHostController, route: GymEditRoute) { Text("Gym editor ${route.gymId} ${route.fromPreset}") }
