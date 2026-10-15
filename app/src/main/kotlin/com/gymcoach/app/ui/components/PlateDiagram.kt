package com.gymcoach.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.gymcoach.core.units.Units

/**
 * §8.4: Canvas drawing of one side of the bar — the bar, the collar and the plates from the inside out,
 * plate height proportional to its mass. The caption lists the loading in words so the diagram is never
 * the only carrier of the numbers (TalkBack).
 */
@Composable
fun PlateDiagram(barKg: Double, perSidePlates: List<Double>) {
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant
    val plateColor = MaterialTheme.colorScheme.primary
    val plateOutline = MaterialTheme.colorScheme.onPrimary
    val total = barKg + 2 * perSidePlates.sum()

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
        ) {
            val w = size.width
            val h = size.height
            val barThickness = 8.dp.toPx()
            val barY = h / 2f
            drawRect(color = barColor, topLeft = Offset(0f, barY - barThickness / 2f), size = Size(w, barThickness))
            // collar at 25 % of the width
            var x = w * 0.25f
            drawRect(color = barColor, topLeft = Offset(x, barY - h * 0.15f), size = Size(6.dp.toPx(), h * 0.3f))
            x += 6.dp.toPx() + 2.dp.toPx()
            for (plate in perSidePlates) {
                val heightFraction = plateHeightFraction(plate)
                val plateWidth = plateWidthDp(plate).dp.toPx()
                val plateHeight = h * heightFraction
                drawRect(color = plateColor, topLeft = Offset(x, barY - plateHeight / 2f), size = Size(plateWidth, plateHeight))
                drawRect(color = plateOutline, topLeft = Offset(x + plateWidth / 2f - 1f, barY - plateHeight / 2f), size = Size(2f, plateHeight))
                x += plateWidth + 3.dp.toPx()
            }
        }
        val plateText = if (perSidePlates.isEmpty()) "empty bar" else perSidePlates.joinToString(" + ") { Units.trim(it) }
        Text(
            text = "${Units.trim(barKg)} kg bar + 2 × ($plateText) = ${Units.trim(total)} kg",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Visual height of a plate relative to the canvas height. */
fun plateHeightFraction(kg: Double): Float = when {
    kg >= 25.0 -> 1.0f
    kg >= 20.0 -> 0.9f
    kg >= 15.0 -> 0.8f
    kg >= 10.0 -> 0.7f
    kg >= 5.0 -> 0.55f
    kg >= 2.5 -> 0.45f
    kg >= 1.25 -> 0.35f
    else -> 0.3f
}

/** Visual width of a plate in dp. */
fun plateWidthDp(kg: Double): Int = when {
    kg >= 20.0 -> 18
    kg >= 10.0 -> 14
    kg >= 5.0 -> 11
    else -> 8
}
