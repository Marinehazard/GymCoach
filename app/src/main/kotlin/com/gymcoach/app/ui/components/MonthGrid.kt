package com.gymcoach.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * §8.4 / HR-3: date picker replacement (no `DatePicker`). Month header with previous/next, ISO
 * weekday columns (Mon..Sun), 48 dp day cells; the selected day is filled with the primary colour.
 */
@Composable
fun MonthGrid(selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(selected ?: LocalDate.now())) }
    val firstDay = month.atDay(1)
    val leadingBlanks = firstDay.dayOfWeek.value - 1          // Monday = 0 blanks
    val dayCount = month.lengthOfMonth()
    val cellCount = leadingBlanks + dayCount
    val rowCount = (cellCount + 6) / 7
    val weekdayLabels = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(imageVector = Icons.Filled.ChevronLeft, contentDescription = "Previous month")
            }
            Text(
                text = month.month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault()) + " " + month.year,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = "Next month")
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (label in weekdayLabels) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        for (row in 0 until rowCount) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val index = row * 7 + col
                    val dayOfMonth = index - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (dayOfMonth in 1..dayCount) {
                            val date = month.atDay(dayOfMonth)
                            val isSelected = date == selected
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                    .clickable { onSelect(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
