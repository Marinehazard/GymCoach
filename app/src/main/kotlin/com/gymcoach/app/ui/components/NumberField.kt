package com.gymcoach.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * §8.4 / §8.2: decimal entry with a unit label. The raw string is kept by the caller (HR-20: what the
 * user typed is shown verbatim); parsing accepts "." and "," via [parseDecimal].
 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        trailingIcon = {
            Text(
                text = unit,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 12.dp),
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** "82,5" and "82.5" both parse to 82.5; blank or garbage returns null. */
fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
