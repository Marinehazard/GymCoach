package com.gymcoach.app.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
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
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.nav.PlanSessionRoute
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.app.ui.today.trainingDepsOf

/** One planned session: its slots with sets × reps @ RIR, current load, alternates; overflow actions are overrides with Adjustments (§8.10). */
@Composable
fun PlanSessionScreen(container: AppContainer, navController: NavHostController, route: PlanSessionRoute) {
    val vm: PlanViewModel = viewModel(key = "plan-session") { PlanViewModel(trainingDepsOf(container)) }
    val s by vm.state.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val onRule: (String) -> Unit = { navController.navigate(RuleDetailRoute(it)) }
    if (s.loading) { LoadingBox(); return }
    val meso = s.mesocycle
    val found = meso?.weeks?.firstNotNullOfOrNull { w -> w.sessions.firstOrNull { it.id == route.plannedSessionId }?.let { w to it } }
    if (meso == null || found == null) {
        EmptyState(Icons.Filled.FitnessCenter, stringResource(R.string.plan_session_missing), "", null, null)
        return
    }
    val (week, session) = found
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.error?.let { e -> item { ErrorBanner(stringResource(R.string.plan_error_title), e, vm::refresh, null) } }
        item { SessionHeader(session) }
        items(session.slots.size) { i ->
            val slot = session.slots[i]
            val ex = container.knowledge.exercise(slot.exerciseId)
            SlotCard(
                slot, week, container.knowledge, meso.goal, extras.states, extras.alternates[slot.exerciseId] ?: emptyList(), session.templateId,
                onSwap = { to -> vm.onSwapForRestOfProgram(slot.exerciseId, to, null) },
                onSets = { d -> ex?.primaryMuscles?.firstOrNull()?.let { vm.onSetsDelta(it, d) } },
                onRange = { lo, hi -> vm.onRepRange(slot.exerciseId, lo, hi) },
                onExclude = { vm.onExclude(slot.exerciseId) }, onRule = onRule,
            )
        }
    }
}
