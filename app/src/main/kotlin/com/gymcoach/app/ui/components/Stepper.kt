package com.gymcoach.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * §8.4: 56 dp load/reps stepper. Minus · value · plus; tapping the value opens the keypad (onTap).
 * The value is rendered in `displayMedium` (§8.2 session numerals).
 */
@Composable
fun Stepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinus, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(imageVector = Icons.Filled.Remove, contentDescription = "Decrease")
        }
        Text(
            text = value,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, onClick = onTap)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.displayMedium,
        )
        IconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = "Increase")
        }
    }
}
