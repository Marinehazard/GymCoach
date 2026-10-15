@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.gymcoach.app.ui.components.HourMinuteStepper
import com.gymcoach.app.ui.components.NumberField
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.library.prettyEnum
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.MiniStepper
import com.gymcoach.app.ui.more.MultiChips
import com.gymcoach.app.ui.more.SetupScaffold
import com.gymcoach.app.ui.more.SwitchRow
import com.gymcoach.app.ui.more.vmFactory
import com.gymcoach.app.ui.onboarding.GoalSchedulePicker
import com.gymcoach.app.ui.onboarding.LimitationEditor
import com.gymcoach.app.ui.onboarding.MedicalFlagChecklist
import com.gymcoach.app.ui.onboarding.regionLabel
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.UnitSystem
import java.time.LocalDate

private fun minutesOf(hhmm: String): Int {
    val p = hhmm.split(':')
    return ((p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)).coerceIn(0, 1439)
}

private fun hhmmOf(minutes: Int): String = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

@Composable
private fun trainingAgeLabel(a: TrainingAge): String = when (a) {
    TrainingAge.NOVICE -> stringResource(R.string.prof_age_novice)
    TrainingAge.INTERMEDIATE -> stringResource(R.string.prof_age_intermediate)
    TrainingAge.ADVANCED -> stringResource(R.string.prof_age_advanced)
}

/** §8.14: every onboarding field editable; derived training age; "Plan affected - regenerate?" after save. */
@Composable
fun ProfileScreen(container: AppContainer, navController: NavHostController) {
    val vm: ProfileViewModel = viewModel(
        factory = vmFactory {
            ProfileViewModel(
                profiles = container.profileRepository, metrics = container.metricsRepository, settings = container.settingsRepository,
                clock = container.clock,
                trainingAgeOf = { p, d -> container.planner(container.planCoordinator.constants()).trainingAgeOf(p, d) },
                recompute = { reason -> container.planCoordinator.recompute(reason) },
            )
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val d = state.draft
    val today = remember { LocalDate.now() }

    SetupScaffold(title = stringResource(R.string.prof_title), onBack = { navController.popBackStack() }) { padding ->
        if (state.profile == null) {
            EmptyState(Icons.Filled.Person, stringResource(R.string.prof_empty_title), stringResource(R.string.prof_empty_body), null, null)
            return@SetupScaffold
        }
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { ErrorBanner(stringResource(R.string.prof_error), it, null, null) }
            state.trainingAge?.let { Text(stringResource(R.string.prof_training_age, trainingAgeLabel(it)), style = MaterialTheme.typography.titleMedium) }
            if (state.planAffected) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.prof_plan_affected), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = vm::onRegenerate, enabled = !state.saving) { Text(stringResource(R.string.prof_regenerate)) }
                    }
                }
            }

            SectionHeader(stringResource(R.string.prof_body))
            ChoiceChips(listOf(Sex.MALE, Sex.FEMALE), d.sex, { if (it == Sex.MALE) stringResource(R.string.onb_sex_male) else stringResource(R.string.onb_sex_female) }) { vm.onDraftChange(d.copy(sex = it)) }
            MiniStepper(stringResource(R.string.onb_birth_year), d.birthYear.toString(), { vm.onDraftChange(d.copy(birthYear = (d.birthYear - 1).coerceAtLeast(DraftRules.BIRTH_YEAR_MIN))) }, { vm.onDraftChange(d.copy(birthYear = (d.birthYear + 1).coerceAtMost(DraftRules.BIRTH_YEAR_MAX))) })
            ChoiceChips(listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL), d.unitSystem, { if (it == UnitSystem.METRIC) stringResource(R.string.onb_unit_metric) else stringResource(R.string.onb_unit_imperial) }) { vm.onDraftChange(DraftRules.switchUnits(d, it)) }
            NumberField(d.heightValue, { vm.onDraftChange(d.copy(heightValue = it)) }, stringResource(R.string.onb_height), if (d.unitSystem == UnitSystem.METRIC) "cm" else "in", Modifier.fillMaxWidth())
            NumberField(d.bodyFatPct, { vm.onDraftChange(d.copy(bodyFatPct = it)) }, stringResource(R.string.nut_body_fat), "%", Modifier.fillMaxWidth())
            SwitchRow(stringResource(R.string.prof_physical_job), d.physicalJob, { vm.onDraftChange(d.copy(physicalJob = it)) })

            SectionHeader(stringResource(R.string.onb_goal_title))
            GoalSchedulePicker(d) { vm.onDraftChange(it) }

            SectionHeader(stringResource(R.string.onb_experience_title))
            MiniStepper(stringResource(R.string.onb_training_months), d.trainingMonths.toString(), { vm.onDraftChange(d.copy(trainingMonths = (d.trainingMonths - 3).coerceAtLeast(0))) }, { vm.onDraftChange(d.copy(trainingMonths = d.trainingMonths + 3)) })
            MiniStepper(stringResource(R.string.onb_layoff_months), d.layoffMonths.toString(), { vm.onDraftChange(d.copy(layoffMonths = (d.layoffMonths - 1).coerceAtLeast(0))) }, { vm.onDraftChange(d.copy(layoffMonths = d.layoffMonths + 1)) })
            MiniStepper(stringResource(R.string.onb_retrained_months), d.retrainedMonths.toString(), { vm.onDraftChange(d.copy(retrainedMonths = (d.retrainedMonths - 1).coerceAtLeast(0))) }, { vm.onDraftChange(d.copy(retrainedMonths = d.retrainedMonths + 1)) })

            SectionHeader(stringResource(R.string.onb_limitations))
            LimitationEditor(d.limitations, today) { vm.onDraftChange(d.copy(limitations = it)) }
            for (lim in state.profile?.limitations.orEmpty().filter { it.active && it.pain0to10 > 3 }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.prof_region_block, regionLabel(lim.region), lim.pain0to10), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { vm.onClearRedFlagRegion(lim.region) }) { Text(stringResource(R.string.prof_clear_block)) }
                    }
                }
            }

            SectionHeader(stringResource(R.string.onb_medical))
            MedicalFlagChecklist(d.medicalFlags) { vm.onDraftChange(d.copy(medicalFlags = it)) }
            if (d.medicalFlags.isNotEmpty()) {
                OutlinedTextField(
                    value = d.clearanceConfirmedOn, onValueChange = { vm.onDraftChange(d.copy(clearanceConfirmedOn = it)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.prof_clearance)) },
                    supportingText = { Text(stringResource(R.string.prof_clearance_hint)) },
                )
                OutlinedButton(onClick = { vm.onDraftChange(d.copy(clearanceConfirmedOn = today.toString())) }) { Text(stringResource(R.string.prof_clearance_today)) }
            }

            SectionHeader(stringResource(R.string.prof_priority))
            MultiChips(Muscle.values().toList(), d.priorityMuscles.toSet(), { prettyEnum(it.name) }) { m ->
                val next = if (m in d.priorityMuscles) d.priorityMuscles - m else (d.priorityMuscles + m).takeLast(3)
                vm.onDraftChange(d.copy(priorityMuscles = next))
            }
            ExercisePicker(stringResource(R.string.prof_liked), d.likedExerciseIds, container.knowledge) { vm.onDraftChange(d.copy(likedExerciseIds = it)) }
            ExercisePicker(stringResource(R.string.prof_disliked), d.dislikedExerciseIds, container.knowledge) { vm.onDraftChange(d.copy(dislikedExerciseIds = it)) }

            SectionHeader(stringResource(R.string.onb_known_loads))
            for (id in DraftRules.MAIN_LIFTS) {
                NumberField(d.knownLoads[id] ?: "", { vm.onDraftChange(d.copy(knownLoads = d.knownLoads + (id to it))) }, container.knowledge.exercise(id)?.name ?: id, if (d.unitSystem == UnitSystem.METRIC) "kg" else "lb", Modifier.fillMaxWidth())
            }
            SwitchRow(stringResource(R.string.prof_advanced), d.allowAdvanced, { vm.onDraftChange(d.copy(allowAdvanced = it)) }, supporting = stringResource(R.string.prof_advanced_sub))

            SectionHeader(stringResource(R.string.prof_heart))
            SwitchRow(stringResource(R.string.prof_hr_monitor), d.hasHrMonitor, { vm.onDraftChange(d.copy(hasHrMonitor = it)) })
            OutlinedTextField(
                value = d.measuredHrMax, onValueChange = { vm.onDraftChange(d.copy(measuredHrMax = it.filter { c -> c.isDigit() })) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.prof_hr_max)) },
            )
            Text(stringResource(R.string.prof_wake), style = MaterialTheme.typography.titleSmall)
            HourMinuteStepper(minutesOf(d.wakeTime)) { vm.onDraftChange(d.copy(wakeTime = hhmmOf(it))) }
            Text(stringResource(R.string.prof_bed), style = MaterialTheme.typography.titleSmall)
            HourMinuteStepper(minutesOf(d.bedTime)) { vm.onDraftChange(d.copy(bedTime = hhmmOf(it))) }
            Text(stringResource(R.string.prof_usual_time), style = MaterialTheme.typography.titleSmall)
            ChoiceChips(listOf("morning", "midday", "evening"), d.usualSessionTime, {
                when (it) { "morning" -> stringResource(R.string.prof_time_morning); "midday" -> stringResource(R.string.prof_time_midday); else -> stringResource(R.string.prof_time_evening) }
            }) { vm.onDraftChange(d.copy(usualSessionTime = it)) }

            Button(onClick = vm::onSave, enabled = state.dirty && !state.saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.su_save)) }
        }
    }
}

/** Search-and-add exercise ids (liked / disliked lists). */
@Composable
private fun ExercisePicker(title: String, ids: List<String>, knowledge: KnowledgeBase, onChange: (List<String>) -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        for (id in ids) {
            InputChip(selected = true, onClick = { onChange(ids - id) }, label = { Text((knowledge.exercise(id)?.name ?: id) + "  ✕") })
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.prof_exercise_search)) })
        if (query.length >= 2) {
            for (e in knowledge.searchByNameOrAlias(query).filter { it.id !in ids }.take(5)) {
                TextButton(onClick = { onChange(ids + e.id); query = "" }) { Text(e.name) }
            }
        }
    }
}
