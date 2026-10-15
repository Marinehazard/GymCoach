@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.core.model.EquipmentPreset
import com.gymcoach.core.model.GymSet

private val GYM_COLORS = listOf(
    Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFEF6C00), Color(0xFF6A1B9A),
    Color(0xFFC62828), Color(0xFF00838F), Color(0xFF5D4037), Color(0xFF546E7A),
)

/** §8.6 gym switcher: gyms by lastUsedAt with colour dot, "used Tue", "12/13 exercises" for today's session, dumbbell-max warning. */
@Composable
fun GymSwitcherContent(
    gyms: List<GymSet>, activeId: String?, info: Map<String, GymAvailability>, presets: List<EquipmentPreset>, usedDay: (GymSet) -> String?,
    onSelect: (String) -> Unit, onNewFromPreset: (String) -> Unit, onDuplicate: () -> Unit,
) {
    var showPresets by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.today_gym_switcher_title), style = MaterialTheme.typography.titleLarge)
        if (gyms.isEmpty()) EmptyState(
            icon = Icons.Filled.Warning, title = stringResource(R.string.today_gym_switcher_empty_title), body = stringResource(R.string.today_gym_switcher_empty_body),
            actionText = null, onAction = null,
        )
        for (g in gyms) {
            val a = info[g.id]
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onSelect(g.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(modifier = Modifier.size(16.dp).clip(CircleShape).background(GYM_COLORS[g.colorIndex.coerceIn(0, GYM_COLORS.size - 1)])) {}
                Column(modifier = Modifier.weight(1f)) {
                    Text(g.name + if (g.id == activeId) " ✓" else "", style = MaterialTheme.typography.titleMedium)
                    val used = usedDay(g)
                    val parts = listOfNotNull(used?.let { stringResource(R.string.today_gym_used, it) }, a?.takeIf { it.total > 0 }?.let { stringResource(R.string.today_gym_exercises, it.available, it.total) })
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
                if (a?.dumbbellWarning == true) Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.today_gym_dumbbell_warning), tint = MaterialTheme.colorScheme.tertiary)
            }
        }
        OutlinedButton(onClick = { showPresets = !showPresets }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.today_gym_new_from_preset)) }
        if (showPresets) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in presets.filter { it.id != "travel_kit" }.take(4)) AssistChip(onClick = { onNewFromPreset(p.id) }, label = { Text(p.name) })
            }
        }
        if (gyms.isNotEmpty()) OutlinedButton(onClick = onDuplicate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.today_gym_duplicate)) }
    }
}
