package com.gymcoach.app.ui.knowledge

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.nav.RuleDetailRoute

/** WP-01 placeholder (§9.1); owned by WP-08 from Phase 1 on. Frozen signature. */
@Composable
fun RuleDetailScreen(container: AppContainer, navController: NavHostController, route: RuleDetailRoute) { Text("Rule ${route.ruleId}") }
