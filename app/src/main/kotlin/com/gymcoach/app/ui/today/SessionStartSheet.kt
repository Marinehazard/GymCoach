@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.core.knowledge.KnowledgeBase

/** "Anything different today?" (§8.6): spotter, missing equipment, extra equipment, session length, quick readiness, Start. */
@Composable
fun SessionStartContent(
    state: TodayUiState, kb: KnowledgeBase, onSpotter: () -> Unit, onRemoved: (Set<String>) -> Unit, onAddExtra: (String) -> Unit,
    onMinutes: (Int?) -> Unit, onReadiness: () -> Unit, onStart: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val gym = state.gym
    val defaults = gym?.defaultSessionMinutes ?: state.sessionPlan?.estimatedMinutes?.toInt()
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.today_start_title), style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = state.spotter, onCheckedChange = { onSpotter() })
            Text(stringResource(R.string.today_start_spotter))
        }
        if (gym != null) {
            Text(stringResource(R.string.today_start_missing), style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in gym.items.take(30)) {
                    val id = item.equipmentId
                    val name = kb.equipment[id]?.name ?: id
                    FilterChip(selected = id in state.removedEquipment, onClick = { onRemoved(if (id in state.removedEquipment) state.removedEquipment - id else state.removedEquipment + id) }, label = { Text(name) })
                }
            }
        }
        Text(stringResource(R.string.today_start_extra), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text(stringResource(R.string.training_search)) }, modifier = Modifier.fillMaxWidth())
        if (query.isNotBlank()) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (e in kb.searchEquipment(query).filter { !it.sessionFlag }.take(8)) AssistChip(onClick = { onAddExtra(e.id); query = "" }, label = { Text("+ " + e.name) })
            }
        }
        Text(stringResource(R.string.today_start_length), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in listOf(30, 45, 60, 90)) {
                FilterChip(selected = state.minutesToday == m || (state.minutesToday == null && defaults == m), onClick = { onMinutes(if (state.minutesToday == m) null else m) }, label = { Text("$m") })
            }
        }
        if (state.readiness == null) OutlinedButton(onClick = onReadiness, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.today_start_quick_readiness)) }
        BigPrimaryButton(text = stringResource(R.string.today_start_session), onClick = onStart, enabled = state.sessionPlan != null && gym != null)
    }
}
