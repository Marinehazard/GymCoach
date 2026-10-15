package com.gymcoach.app.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.NumberField
import com.gymcoach.app.ui.components.SectionHeader
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.MiniStepper
import com.gymcoach.app.ui.more.MultiChips
import com.gymcoach.app.ui.more.SwitchRow
import com.gymcoach.app.ui.profile.DraftRules
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Limitation
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.MedicalFlag
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.readiness.RedFlags
import com.gymcoach.core.util.IdGen
import java.time.LocalDate

@Composable
fun WelcomePage() {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onb_welcome_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.onb_welcome_honest), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AboutPage(draft: ProfileDraft, extras: OnboardingExtras, onChange: (ProfileDraft) -> Unit) {
    val metric = draft.unitSystem == UnitSystem.METRIC
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onb_about_title), style = MaterialTheme.typography.headlineSmall)
        ChoiceChips(listOf(Sex.MALE, Sex.FEMALE), draft.sex, { if (it == Sex.MALE) stringResource(R.string.onb_sex_male) else stringResource(R.string.onb_sex_female) }) {
            onChange(draft.copy(sex = it))
        }
        MiniStepper(
            label = stringResource(R.string.onb_birth_year),
            value = draft.birthYear.toString(),
            onMinus = { onChange(draft.copy(birthYear = (draft.birthYear - 1).coerceAtLeast(DraftRules.BIRTH_YEAR_MIN))) },
            onPlus = { onChange(draft.copy(birthYear = (draft.birthYear + 1).coerceAtMost(DraftRules.BIRTH_YEAR_MAX))) },
        )
        ChoiceChips(listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL), draft.unitSystem, { if (it == UnitSystem.METRIC) stringResource(R.string.onb_unit_metric) else stringResource(R.string.onb_unit_imperial) }) {
            onChange(DraftRules.switchUnits(draft, it))
        }
        NumberField(draft.heightValue, { onChange(draft.copy(heightValue = it)) }, stringResource(R.string.onb_height), if (metric) "cm" else "in", Modifier.fillMaxWidth())
        if (!extras.heightOk) Text(stringResource(R.string.onb_height_range), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        NumberField(draft.weightValue, { onChange(draft.copy(weightValue = it)) }, stringResource(R.string.onb_weight), if (metric) "kg" else "lb", Modifier.fillMaxWidth())
        if (!extras.weightOk) Text(stringResource(R.string.onb_weight_range), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun goalTitle(goal: Goal): String = when (goal) {
    Goal.HYPERTROPHY -> stringResource(R.string.su_goal_hypertrophy)
    Goal.STRENGTH -> stringResource(R.string.su_goal_strength)
    Goal.GENERAL_FITNESS -> stringResource(R.string.su_goal_general_fitness)
    Goal.FAT_LOSS -> stringResource(R.string.su_goal_fat_loss)
    Goal.ENDURANCE_SUPPORT -> stringResource(R.string.su_goal_endurance_support)
}

@Composable
private fun goalLine(goal: Goal): String = when (goal) {
    Goal.HYPERTROPHY -> stringResource(R.string.su_goal_hypertrophy_line)
    Goal.STRENGTH -> stringResource(R.string.su_goal_strength_line)
    Goal.GENERAL_FITNESS -> stringResource(R.string.su_goal_general_fitness_line)
    Goal.FAT_LOSS -> stringResource(R.string.su_goal_fat_loss_line)
    Goal.ENDURANCE_SUPPORT -> stringResource(R.string.su_goal_endurance_support_line)
}

@Composable
fun weekdayLabel(isoDay: Int): String = when (isoDay) {
    1 -> stringResource(R.string.su_day_mon)
    2 -> stringResource(R.string.su_day_tue)
    3 -> stringResource(R.string.su_day_wed)
    4 -> stringResource(R.string.su_day_thu)
    5 -> stringResource(R.string.su_day_fri)
    6 -> stringResource(R.string.su_day_sat)
    else -> stringResource(R.string.su_day_sun)
}

/** Goal cards (DEF-06 one-liners), days chips 1-6, weekday toggles, minutes chips. */
@Composable
fun GoalSchedulePicker(draft: ProfileDraft, onChange: (ProfileDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (goal in Goal.values()) {
            val selected = draft.goal == goal
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onChange(draft.copy(goal = goal)) },
                colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { onChange(draft.copy(goal = goal)) })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(goalTitle(goal), style = MaterialTheme.typography.titleMedium)
                        Text(goalLine(goal), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Text(stringResource(R.string.onb_days_per_week), style = MaterialTheme.typography.titleSmall)
        ChoiceChips((1..6).toList(), draft.daysPerWeek, { it.toString() }) { onChange(draft.copy(daysPerWeek = it)) }
        Text(stringResource(R.string.onb_weekdays), style = MaterialTheme.typography.titleSmall)
        MultiChips((1..7).toList(), draft.weekdays.toSet(), { weekdayLabel(it) }) { day ->
            val next = if (day in draft.weekdays) draft.weekdays - day else (draft.weekdays + day).sorted()
            onChange(draft.copy(weekdays = next))
        }
        if (draft.weekdays.isNotEmpty() && draft.weekdays.size != draft.daysPerWeek) {
            Text(stringResource(R.string.onb_weekdays_mismatch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Text(stringResource(R.string.onb_session_minutes), style = MaterialTheme.typography.titleSmall)
        ChoiceChips(listOf(30, 45, 60, 90), draft.sessionMinutes, { "$it min" }) { onChange(draft.copy(sessionMinutes = it)) }
    }
}

@Composable
fun GoalPage(draft: ProfileDraft, onChange: (ProfileDraft) -> Unit) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onb_goal_title), style = MaterialTheme.typography.headlineSmall)
        GoalSchedulePicker(draft, onChange)
    }
}

@Composable
fun regionLabel(region: LimitationRegion): String = when (region) {
    LimitationRegion.KNEE -> stringResource(R.string.su_region_knee)
    LimitationRegion.SHOULDER -> stringResource(R.string.su_region_shoulder)
    LimitationRegion.LOW_BACK -> stringResource(R.string.su_region_low_back)
    LimitationRegion.ELBOW_WRIST -> stringResource(R.string.su_region_elbow_wrist)
    LimitationRegion.HIP -> stringResource(R.string.su_region_hip)
    LimitationRegion.ANKLE -> stringResource(R.string.su_region_ankle)
    LimitationRegion.NECK -> stringResource(R.string.su_region_neck)
    LimitationRegion.OTHER -> stringResource(R.string.su_region_other)
}

@Composable
fun medicalFlagLabel(flag: MedicalFlag): String = when (flag) {
    MedicalFlag.HEART_DISEASE -> stringResource(R.string.su_med_heart)
    MedicalFlag.KIDNEY_DISEASE -> stringResource(R.string.su_med_kidney)
    MedicalFlag.LIVER_DISEASE -> stringResource(R.string.su_med_liver)
    MedicalFlag.PREGNANCY -> stringResource(R.string.su_med_pregnancy)
    MedicalFlag.DIABETES_ON_MEDICATION -> stringResource(R.string.su_med_diabetes)
    MedicalFlag.UNCONTROLLED_HYPERTENSION -> stringResource(R.string.su_med_hypertension)
    MedicalFlag.DOCTOR_RESTRICTION -> stringResource(R.string.su_med_doctor)
    MedicalFlag.OTHER -> stringResource(R.string.su_med_other)
}

/** Limitation chips (region), each with side chips and a 0-10 pain slider (INJ-00..04); shared with the Profile screen. */
@Composable
fun LimitationEditor(limitations: List<Limitation>, today: LocalDate, onChange: (List<Limitation>) -> Unit) {
    val regions = LimitationRegion.values().toList()
    val active = limitations.filter { it.active }.map { it.region }.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MultiChips(regions, active, { regionLabel(it) }) { region ->
            if (region in active) {
                onChange(limitations.filterNot { it.region == region })
            } else {
                onChange(limitations + Limitation(id = IdGen.newId(), region = region, side = Side.BOTH, pain0to10 = 2, since = today))
            }
        }
        for (lim in limitations.filter { it.active }) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(regionLabel(lim.region), style = MaterialTheme.typography.titleSmall)
                    ChoiceChips(listOf(Side.LEFT, Side.RIGHT, Side.BOTH), lim.side, {
                        when (it) { Side.LEFT -> stringResource(R.string.su_side_left); Side.RIGHT -> stringResource(R.string.su_side_right); Side.BOTH -> stringResource(R.string.su_side_both) }
                    }) { side -> onChange(limitations.map { if (it.id == lim.id) it.copy(side = side) else it }) }
                    Text(stringResource(R.string.onb_pain, lim.pain0to10), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = lim.pain0to10.toFloat(),
                        onValueChange = { v -> onChange(limitations.map { if (it.id == lim.id) it.copy(pain0to10 = v.toInt().coerceIn(0, 10)) else it }) },
                        valueRange = 0f..10f,
                        steps = 9,
                    )
                    SwitchRow(stringResource(R.string.onb_limited_range), lim.limitedRange, { v -> onChange(limitations.map { if (it.id == lim.id) it.copy(limitedRange = v) else it }) })
                }
            }
        }
    }
}

@Composable
fun MedicalFlagChecklist(flags: Set<MedicalFlag>, onChange: (Set<MedicalFlag>) -> Unit) {
    Column {
        Text(stringResource(R.string.onb_medical_gate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (flag in MedicalFlag.values()) {
            Row(modifier = Modifier.fillMaxWidth().clickable { onChange(if (flag in flags) flags - flag else flags + flag) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = flag in flags, onCheckedChange = { onChange(if (flag in flags) flags - flag else flags + flag) })
                Text(medicalFlagLabel(flag), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun ExperiencePage(draft: ProfileDraft, today: LocalDate, onChange: (ProfileDraft) -> Unit) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onb_experience_title), style = MaterialTheme.typography.headlineSmall)
        MiniStepper(stringResource(R.string.onb_training_months), draft.trainingMonths.toString(), { onChange(draft.copy(trainingMonths = (draft.trainingMonths - 3).coerceAtLeast(0))) }, { onChange(draft.copy(trainingMonths = draft.trainingMonths + 3)) })
        MiniStepper(stringResource(R.string.onb_layoff_months), draft.layoffMonths.toString(), { onChange(draft.copy(layoffMonths = (draft.layoffMonths - 1).coerceAtLeast(0))) }, { onChange(draft.copy(layoffMonths = draft.layoffMonths + 1)) })
        MiniStepper(stringResource(R.string.onb_retrained_months), draft.retrainedMonths.toString(), { onChange(draft.copy(retrainedMonths = (draft.retrainedMonths - 1).coerceAtLeast(0))) }, { onChange(draft.copy(retrainedMonths = draft.retrainedMonths + 1)) })
        SectionHeader(stringResource(R.string.onb_limitations))
        LimitationEditor(draft.limitations, today) { onChange(draft.copy(limitations = it)) }
        SectionHeader(stringResource(R.string.onb_medical))
        MedicalFlagChecklist(draft.medicalFlags) { onChange(draft.copy(medicalFlags = it)) }
        SectionHeader(stringResource(R.string.onb_redflags))
        Text(stringResource(R.string.onb_redflags_intro), style = MaterialTheme.typography.bodyMedium)
        for (item in RedFlags.items) {
            Text("• " + item.text, style = MaterialTheme.typography.bodyMedium, color = if (item.emergency) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        Text(stringResource(R.string.onb_redflags_ack), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GymPage(
    state: OnboardingUiState,
    extras: OnboardingExtras,
    unitSystem: UnitSystem,
    presets: List<Pair<String, String>>,
    onPreset: (String) -> Unit,
    onName: (String) -> Unit,
    onReview: () -> Unit,
    onKnownLoad: (String, String) -> Unit,
    exerciseName: (String) -> String,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onb_gym_title), style = MaterialTheme.typography.headlineSmall)
        for ((id, name) in presets) {
            val selected = state.presetId == id
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onPreset(id) },
                colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(presetTitle(id, name), style = MaterialTheme.typography.titleMedium)
                    Text(presetBlurb(id), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        OutlinedTextField(value = extras.gymName, onValueChange = onName, label = { Text(stringResource(R.string.onb_gym_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_review_equipment)) }
        SectionHeader(stringResource(R.string.onb_known_loads))
        Text(stringResource(R.string.onb_known_loads_hint), style = MaterialTheme.typography.bodySmall)
        for (id in DraftRules.MAIN_LIFTS) {
            NumberField(state.draft.knownLoads[id] ?: "", { onKnownLoad(id, it) }, exerciseName(id), if (unitSystem == UnitSystem.METRIC) "kg" else "lb", Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun presetTitle(id: String, fallback: String): String = when (id) {
    "base_gym" -> stringResource(R.string.su_preset_commercial)
    "home_gym" -> stringResource(R.string.su_preset_home)
    "hotel_gym" -> stringResource(R.string.su_preset_hotel)
    "travel_kit" -> stringResource(R.string.su_preset_bands)
    else -> fallback
}

@Composable
private fun presetBlurb(id: String): String = when (id) {
    "base_gym" -> stringResource(R.string.su_preset_commercial_blurb)
    "home_gym" -> stringResource(R.string.su_preset_home_blurb)
    "hotel_gym" -> stringResource(R.string.su_preset_hotel_blurb)
    "travel_kit" -> stringResource(R.string.su_preset_bands_blurb)
    else -> ""
}

@Composable
fun WeekPage(state: OnboardingUiState, built: BuiltPlan?) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onb_week_title), style = MaterialTheme.typography.headlineSmall)
        if (state.building || built == null) {
            Text(stringResource(R.string.onb_week_building), style = MaterialTheme.typography.bodyLarge)
        } else {
            state.builtSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (day in built.days) {
                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = if (day.sessionName != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(weekdayLabel(day.weekday), style = MaterialTheme.typography.labelMedium)
                            Text(if (day.sessionName != null) day.sets.toString() else "–", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            for (note in built.underTarget) {
                Card(modifier = Modifier.fillMaxWidth()) { Text(note.message + "  " + note.ruleIds.joinToString(" "), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.onb_week_api_key), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
