package com.gymcoach.app.ui.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.app.ui.components.LineChart
import com.gymcoach.app.ui.components.NumberField
import com.gymcoach.app.ui.components.RuleTag
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.MiniStepper
import com.gymcoach.app.ui.more.SwitchRow
import com.gymcoach.core.model.ChartPoint
import com.gymcoach.core.model.ChartSeries
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.SupplementAdvice
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightTrendPoint
import com.gymcoach.core.units.Units

@Composable
fun TargetsCard(t: NutritionTargets, onRule: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.nut_targets), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.nut_kcal, t.kcal), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.nut_macros, t.proteinG, t.carbsG, t.fatG))
            Text(stringResource(R.string.nut_fibre_water, t.fibreG, Units.trim(t.waterL)))
            if (t.energyDeltaKcal != 0) Text(stringResource(R.string.nut_delta, t.energyDeltaKcal), style = MaterialTheme.typography.bodyMedium)
            ExplanationChip(t.explanation, onRule)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (id in listOf("NUT-01", "NUT-08", "NUT-09", "NUT-10", "NUT-11")) RuleTag(id, onRule)
            }
        }
    }
}

@Composable
fun EnergyCard(state: NutritionUiState, onFactor: (Double?) -> Unit, onRule: (String) -> Unit) {
    val e = state.energy ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.nut_energy), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.nut_energy_line, e.reeKcal, Units.trim(e.activityFactor), e.tdeeEstimateKcal))
            ExplanationChip(e.explanation, onRule)
            Text(stringResource(R.string.nut_factor_pick), style = MaterialTheme.typography.bodySmall)
            ChoiceChips(listOf(1.2, 1.375, 1.55, 1.725, 1.9), state.activityFactor, { Units.trim(it) }) { onFactor(it) }
            for (adj in e.adjustments) ExplanationChip(adj.explanation, onRule)
        }
    }
}

/** MA7 and raw weigh-ins as two series (the noise-flagged raw points draw dashed). */
fun trendSeries(points: List<WeightTrendPoint>, unit: UnitSystem): List<ChartSeries> {
    fun conv(kg: Double): Double = if (unit == UnitSystem.METRIC) kg else Units.kgToLb(kg)
    val raw = points.mapNotNull { p -> p.rawKg?.let { ChartPoint(p.date.toEpochDay(), conv(it), lowConfidence = p.noise) } }
    val ma = points.mapNotNull { p -> p.ma7Kg?.let { ChartPoint(p.date.toEpochDay(), conv(it)) } }
    return listOf(ChartSeries("MA7", ma), ChartSeries("Raw", raw)).filter { it.points.isNotEmpty() }
}

@Composable
fun WeighInCard(
    state: NutritionUiState, unit: UnitSystem, weighIns: Int, onWeight: (String) -> Unit, onSteps: (String) -> Unit, onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nut_weigh_in), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(state.weightValue, onWeight, stringResource(R.string.nut_weight), if (unit == UnitSystem.METRIC) "kg" else "lb", Modifier.weight(1f))
                OutlinedTextField(
                    value = state.stepsValue, onValueChange = onSteps, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.nut_steps)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.su_save)) }
            val series = trendSeries(state.trend, unit)
            if (weighIns < 7 || series.isEmpty()) {
                Text(stringResource(R.string.nut_need_weights, weighIns), style = MaterialTheme.typography.bodyMedium)
            } else {
                LineChart(series, Modifier.fillMaxWidth(), if (unit == UnitSystem.METRIC) "kg" else "lb")
            }
        }
    }
}

@Composable
fun DailyLogCard(
    state: NutritionUiState,
    onKcal: (String) -> Unit,
    onSaveEntry: (String) -> Unit,
    onProteinHit: (Boolean) -> Unit,
    onMeals: (Int) -> Unit,
    onAlcohol: (Int) -> Unit,
    onTravelDay: (Boolean) -> Unit,
) {
    val log = state.todayLog
    var protein by remember(log?.proteinG) { mutableStateOf(log?.proteinG?.toString() ?: "") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nut_today_log), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.nut_protein_days, state.proteinDays7), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(state.kcalValue, onKcal, stringResource(R.string.nut_kcal_label), "kcal", Modifier.weight(1f))
                NumberField(protein, { protein = it }, stringResource(R.string.nut_protein_label), "g", Modifier.weight(1f))
            }
            Button(onClick = { onSaveEntry(protein) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.su_save)) }
            Text(stringResource(R.string.nut_protein_hit))
            ChoiceChips(listOf(true, false), log?.proteinTargetHit, { if (it) stringResource(R.string.chk_yes) else stringResource(R.string.chk_no) }, onSelect = onProteinHit)
            Text(stringResource(R.string.nut_meals))
            ChoiceChips(listOf(1, 2, 3), log?.mealsHeaviness, {
                when (it) { 1 -> stringResource(R.string.nut_meals_light); 2 -> stringResource(R.string.nut_meals_normal); else -> stringResource(R.string.nut_meals_heavy) }
            }, onSelect = onMeals)
            val drinks = log?.alcoholDrinks ?: 0
            MiniStepper(stringResource(R.string.nut_alcohol), drinks.toString(), { onAlcohol((drinks - 1).coerceAtLeast(0)) }, { onAlcohol(drinks + 1) })
            SwitchRow(stringResource(R.string.nut_travel_day), log?.travelDay ?: false, onTravelDay, supporting = stringResource(R.string.nut_travel_day_sub))
        }
    }
}

@Composable
fun SupplementsSection(items: List<SupplementAdvice>, priority: String, caveat: List<String>, gate: Boolean, onRule: (String) -> Unit) {
    SectionHeader(stringResource(R.string.nut_supplements))
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (gate) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(stringResource(R.string.nut_gate), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(priority, style = MaterialTheme.typography.bodyMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(modifier = Modifier.padding(12.dp)) {
                RuleTag("SUP-12", onRule)
                for (line in caveat) Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
        for (tier in listOf("A", "B", "C")) {
            val inTier = items.filter { it.tier == tier }
            if (inTier.isEmpty()) continue
            Text(stringResource(R.string.nut_tier, tier), style = MaterialTheme.typography.titleSmall)
            for (s in inTier) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall)
                        Text(s.doseText, style = MaterialTheme.typography.bodyMedium)
                        if (s.timing.isNotBlank()) Text(s.timing, style = MaterialTheme.typography.bodySmall)
                        s.doNotReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        for (n in s.notes) Text("• $n", style = MaterialTheme.typography.bodySmall)
                        s.legalNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RuleTag(s.id, onRule)
                            Text(s.evidence.name.lowercase() + " · " + s.citation, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
