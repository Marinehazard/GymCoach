@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.today

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.app.ui.components.NumberField
import com.gymcoach.app.ui.components.Stepper
import com.gymcoach.core.model.Exercise

/** Cardio-log sheet (§8.6): modality chips from the day's prescription / the gym's machines, minutes stepper, optional HR, RPE 6–20. */
@Composable
fun CardioLogContent(draft: CardioLogDraft, modalities: List<Exercise>, onChange: (CardioLogDraft) -> Unit, onSave: () -> Unit) {
    val minutes = draft.minutes.trim().toIntOrNull() ?: 0
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.today_cardiolog_title), style = MaterialTheme.typography.titleLarge)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in modalities) FilterChip(selected = m.id == draft.exerciseId, onClick = { onChange(draft.copy(exerciseId = m.id)) }, label = { Text(m.name) })
        }
        Text(stringResource(R.string.today_cardiolog_minutes), style = MaterialTheme.typography.labelLarge)
        Stepper(
            value = minutes.toString(), onMinus = { onChange(draft.copy(minutes = maxOf(0, minutes - 5).toString())) },
            onPlus = { onChange(draft.copy(minutes = (minutes + 5).toString())) }, onTap = {}, modifier = Modifier.fillMaxWidth(),
        )
        NumberField(value = draft.avgHr, onValueChange = { onChange(draft.copy(avgHr = it)) }, label = stringResource(R.string.today_cardiolog_avg_hr), unit = "bpm", modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.today_cardiolog_rpe), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in 6..20) FilterChip(selected = draft.rpe == v, onClick = { onChange(draft.copy(rpe = if (draft.rpe == v) null else v)) }, label = { Text(v.toString()) })
        }
        BigPrimaryButton(text = stringResource(R.string.today_cardiolog_log), onClick = onSave, enabled = minutes > 0)
    }
}
