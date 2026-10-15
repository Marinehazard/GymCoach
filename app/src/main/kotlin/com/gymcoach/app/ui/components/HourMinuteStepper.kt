package com.gymcoach.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * §8.4 / HR-3: time entry via two steppers (no `TimePicker`). Hours step by 1 (wrapping 0..23),
 * minutes by 5 (wrapping 0..55). `minutesOfDay` is 0..1439.
 */
@Composable
fun HourMinuteStepper(minutesOfDay: Int, onChange: (Int) -> Unit) {
    val clamped = ((minutesOfDay % 1440) + 1440) % 1440
    val hours = clamped / 60
    val minutes = clamped % 60
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Stepper(
            value = twoDigits(hours),
            onMinus = { onChange(((hours + 23) % 24) * 60 + minutes) },
            onPlus = { onChange(((hours + 1) % 24) * 60 + minutes) },
            onTap = {},
            modifier = Modifier.weight(1f),
        )
        Text(text = ":", style = MaterialTheme.typography.displayMedium)
        Stepper(
            value = twoDigits(minutes),
            onMinus = { onChange(hours * 60 + ((minutes - 5 + 60) % 60)) },
            onPlus = { onChange(hours * 60 + ((minutes + 5) % 60)) },
            onTap = {},
            modifier = Modifier.weight(1f),
        )
    }
}

/** 7 → "07". */
fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()
