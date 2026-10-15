package com.gymcoach.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R

/** Number keypad sheet (§8.8: nothing needs a keyboard). [allowDecimal] false for reps / seconds. */
@Composable
fun KeypadContent(initial: String, allowDecimal: Boolean, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val shown = text.ifEmpty { initial }
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (allowDecimal) "." else "", "0", "⌫"))
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(shown.ifEmpty { "0" }, style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        for (r in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (key in r) {
                    OutlinedButton(
                        onClick = {
                            val base = if (text.isEmpty() && key != "⌫") "" else text
                            text = when {
                                key == "⌫" -> base.dropLast(1)
                                key == "." -> if (base.contains('.') || base.contains(',')) base else (if (base.isEmpty()) "0." else "$base.")
                                key.isEmpty() -> base
                                else -> base + key
                            }
                        },
                        enabled = key.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                    ) { Text(key, style = MaterialTheme.typography.headlineSmall) }
                }
            }
        }
        Button(onClick = { onCommit(shown) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.training_action_done)) }
    }
}
