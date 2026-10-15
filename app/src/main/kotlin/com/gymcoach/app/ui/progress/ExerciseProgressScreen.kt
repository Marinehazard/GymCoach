package com.gymcoach.app.ui.progress

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.nav.ExerciseProgressRoute

/** WP-01 placeholder (§9.1); owned by WP-07 from Phase 1 on. Frozen signature. */
@Composable
fun ExerciseProgressScreen(container: AppContainer, navController: NavHostController, route: ExerciseProgressRoute) { Text("Progress ${route.exerciseId}") }
