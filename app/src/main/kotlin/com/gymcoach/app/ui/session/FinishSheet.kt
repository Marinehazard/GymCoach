@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.gymcoach.core.model.SetLog

/** Finish sheet (§8.8): unlogged planned sets are listed "not done" (nothing fabricated), session RPE 0–10 (RDY-06), note, mobility block. */
@Composable
fun FinishContent(state: SessionUiState, live: List<SetLog>, onFinish: (rpe: Int?, note: String, mobility: Boolean) -> Unit) {
    var rpe by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf(state.record?.notes ?: "") }
    var mobility by remember { mutableStateOf(false) }
    val notDone = state.rows.flatMap { row ->
        SetLogging.workPrescriptions(row.block).filter { p -> !SetLogging.hasLogged(row.block, p, live) }.map { p -> row.name + " · " + p.index }
    }
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.session_finish_title), style = MaterialTheme.typography.titleLarge)
        if (notDone.isNotEmpty()) {
            Text(stringResource(R.string.session_finish_not_done, notDone.size), style = MaterialTheme.typography.titleMedium)
            for (line in notDone.take(12)) Text("○ $line", style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.session_finish_rpe), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (v in 0..10) FilterChip(selected = rpe == v, onClick = { rpe = if (rpe == v) null else v }, label = { Text(v.toString()) })
        }
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.session_finish_note)) }, modifier = Modifier.fillMaxWidth())
        if (state.plan?.cooldown?.isNotEmpty() == true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = mobility, onCheckedChange = { mobility = it })
                Text(stringResource(R.string.session_finish_mobility))
            }
        }
        BigPrimaryButton(text = stringResource(R.string.session_finish_button), onClick = { onFinish(rpe, note, mobility) })
    }
}
