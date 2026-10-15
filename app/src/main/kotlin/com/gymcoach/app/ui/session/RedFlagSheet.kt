package com.gymcoach.app.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.gymcoach.core.readiness.RedFlagItem
import com.gymcoach.core.readiness.RedFlags

/** RED-01 "Something feels wrong" (session and readiness screens). Any selection ends the session and shows the hand-off text (POL-02: no diagnosis). */
@Composable
fun RedFlagContent(onStop: (RedFlagItem) -> Unit, onContinue: () -> Unit) {
    var selected by remember { mutableStateOf<RedFlagItem?>(null) }
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.session_redflag_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.session_redflag_intro), style = MaterialTheme.typography.bodyLarge)
        for (item in RedFlags.items) {
            Row(modifier = Modifier.fillMaxWidth().clickable { selected = item }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected?.id == item.id, onClick = { selected = item })
                Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
        val sel = selected
        if (sel != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(RedFlags.handoffText(sel.emergency), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { onStop(sel) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.session_redflag_stop)) }
                }
            }
        }
        androidx.compose.material3.TextButton(onClick = onContinue) { Text(stringResource(R.string.session_redflag_continue)) }
    }
}
