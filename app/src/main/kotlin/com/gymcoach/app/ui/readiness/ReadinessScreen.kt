@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.readiness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.gymcoach.app.R
import com.gymcoach.app.di.AppContainer
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.app.ui.components.NumberField
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.nav.ReadinessRoute
import com.gymcoach.app.ui.nav.RuleDetailRoute
import com.gymcoach.app.ui.session.RedFlagContent
import com.gymcoach.app.ui.today.trainingDepsOf
import java.time.LocalDate

/** §8.7: quick mode = three chip rows + Done; "More" expands the full RDY-01 form. */
@Composable
fun ReadinessScreen(container: AppContainer, navController: NavHostController, route: ReadinessRoute) {
    val date = LocalDate.parse(route.date)
    val vm: ReadinessViewModel = viewModel(key = "readiness-" + route.date) { ReadinessViewModel(date, trainingDepsOf(container)) }
    val s by vm.state.collectAsStateWithLifecycle()
    val modifiers by vm.modifierPreview.collectAsStateWithLifecycle()
    val update by vm.isUpdate.collectAsStateWithLifecycle()
    var redFlag by remember { mutableStateOf(false) }
    val onRule: (String) -> Unit = { navController.navigate(RuleDetailRoute(it)) }
    LaunchedEffect(s.saved) { if (s.saved) navController.popBackStack() }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.readiness_title, s.date.toString()), style = MaterialTheme.typography.headlineSmall)
        SectionHeader(stringResource(R.string.readiness_slept))
        ChipRow(listOf("< 5", "5–6", "6–7", "7–7.5", "7.5+"), s.sleptBucket) { vm.onQuickSlept(it) }
        SectionHeader(stringResource(R.string.readiness_feel))
        ChipRow((1..5).map { it.toString() }, s.feel?.minus(1)) { vm.onQuickFeel(it + 1) }
        SectionHeader(stringResource(R.string.readiness_sore))
        ChipRow((1..5).map { it.toString() }, s.sore?.minus(1)) { vm.onQuickSore(it + 1) }
        s.preview?.let { p ->
            Text(p.band.name.lowercase().replaceFirstChar { c -> c.uppercase() } + " " + p.score, style = MaterialTheme.typography.titleLarge)
            modifiers?.let { ExplanationChip(explanation = it, onRule = onRule) }
            ExplanationChip(explanation = p.explanation, onRule = onRule)
            if (p.assumedFields.isNotEmpty()) Text(stringResource(R.string.readiness_assumed, p.assumedFields.joinToString()), style = MaterialTheme.typography.bodyMedium)
        }
        if (s.quickMode) OutlinedButton(onClick = vm::onExpand, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.readiness_more)) }
        else FullForm(s, vm)
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BigPrimaryButton(text = stringResource(if (update) R.string.readiness_update else R.string.training_action_done), onClick = vm::onDone)
        TextButton(onClick = { redFlag = true }) { Text(stringResource(R.string.session_something_wrong)) }
    }
    if (redFlag) ModalBottomSheet(onDismissRequest = { redFlag = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RedFlagContent(onStop = { vm.onSomethingWrong(it.text); redFlag = false }, onContinue = { redFlag = false })
    }
}

@Composable
private fun ChipRow(labels: List<String>, selected: Int?, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l -> FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(l) }) }
    }
}

@Composable
private fun ScaleRow(title: Int, value: Int?, onSelect: (Int?) -> Unit) {
    SectionHeader(stringResource(title))
    ChipRow((1..5).map { it.toString() }, value?.minus(1)) { onSelect(if (value == it + 1) null else it + 1) }
}

@Composable
private fun FullForm(s: ReadinessUiState, vm: ReadinessViewModel) {
    NumberField(value = s.sleepHours, onValueChange = vm::onSleepHours, label = stringResource(R.string.readiness_sleep_hours), unit = "h", modifier = Modifier.fillMaxWidth())
    ScaleRow(R.string.readiness_sleep_quality, s.sleepQuality) { vm.onField(ReadinessField.SLEEP_QUALITY, it) }
    ScaleRow(R.string.readiness_soreness, s.soreness) { vm.onField(ReadinessField.SORENESS, it) }
    ScaleRow(R.string.readiness_stress, s.stress) { vm.onField(ReadinessField.STRESS, it) }
    ScaleRow(R.string.readiness_energy, s.energy) { vm.onField(ReadinessField.ENERGY, it) }
    ScaleRow(R.string.readiness_motivation, s.motivation) { vm.onField(ReadinessField.MOTIVATION, it) }
    NumberField(value = s.restingHr, onValueChange = vm::onRestingHr, label = stringResource(R.string.readiness_rhr), unit = "bpm", modifier = Modifier.fillMaxWidth())
    NumberField(value = s.steps, onValueChange = vm::onSteps, label = stringResource(R.string.readiness_steps), unit = "", modifier = Modifier.fillMaxWidth())
    NumberField(value = s.weightValue, onValueChange = vm::onWeight, label = stringResource(R.string.readiness_weight), unit = "", modifier = Modifier.fillMaxWidth())
    SectionHeader(stringResource(R.string.readiness_protein))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = s.proteinHit == true, onClick = { vm.onProteinHit(if (s.proteinHit == true) null else true) }, label = { Text(stringResource(R.string.training_action_yes)) })
        FilterChip(selected = s.proteinHit == false, onClick = { vm.onProteinHit(if (s.proteinHit == false) null else false) }, label = { Text(stringResource(R.string.training_action_no)) })
    }
    ScaleRow(R.string.readiness_meals, s.mealsHeaviness) { vm.onField(ReadinessField.MEALS_HEAVINESS, it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { vm.onAlcohol(s.alcohol - 1) }) { Text("−") }
        Text(stringResource(R.string.readiness_alcohol, s.alcohol))
        OutlinedButton(onClick = { vm.onAlcohol(s.alcohol + 1) }) { Text("+") }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = s.illness, onCheckedChange = vm::onIllness)
        Text(stringResource(R.string.readiness_ill))
    }
}
