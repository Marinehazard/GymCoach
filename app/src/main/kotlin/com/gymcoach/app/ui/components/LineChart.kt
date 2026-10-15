package com.gymcoach.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gymcoach.core.model.ChartSeries
import com.gymcoach.core.units.Units

/**
 * §8.4 / R2 §6.2: hand-drawn Canvas line chart. One polyline per series, drawn segment by segment:
 * a segment touching a low-confidence point (E1RM-02 LOW) is dashed and the point itself hollow.
 * Axis labels are plain Text around the canvas (no text drawing inside the canvas, which keeps the
 * implementation on stable APIs only).
 */
@Composable
fun LineChart(series: List<ChartSeries>, modifier: Modifier = Modifier, yLabel: String) {
    val colorScheme = MaterialTheme.colorScheme
    val palette: List<Color> = listOf(colorScheme.primary, colorScheme.secondary, colorScheme.tertiary, colorScheme.error)
    val gridColor = colorScheme.outlineVariant

    val points = series.flatMap { it.points }
    if (points.isEmpty()) {
        Text(
            text = "No data yet",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
        )
        return
    }
    val minX = points.minOf { it.dateEpochDay }
    val maxX = points.maxOf { it.dateEpochDay }
    val rawMinY = points.minOf { it.value }
    val rawMaxY = points.maxOf { it.value }
    val pad = if (rawMaxY == rawMinY) (if (rawMaxY == 0.0) 1.0 else rawMaxY.let { kotlin.math.abs(it) * 0.1 }) else (rawMaxY - rawMinY) * 0.1
    val minY = rawMinY - pad
    val maxY = rawMaxY + pad

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = yLabel, style = MaterialTheme.typography.labelMedium)
            Text(text = Units.trim(rawMaxY), style = MaterialTheme.typography.labelMedium)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            val w = size.width
            val h = size.height
            val spanX = (maxX - minX).toDouble().coerceAtLeast(1.0)
            val spanY = (maxY - minY).coerceAtLeast(1e-9)
            fun px(day: Long): Float = ((day - minX) / spanX * w).toFloat()
            fun py(v: Double): Float = (h - (v - minY) / spanY * h).toFloat()

            // horizontal grid: 4 lines
            for (i in 0..4) {
                val y = h * i / 4f
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
            }
            val dashed = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()), 0f)
            series.forEachIndexed { index, s ->
                val color = palette[index % palette.size]
                val sorted = s.points.sortedBy { it.dateEpochDay }
                for (i in 1 until sorted.size) {
                    val a = sorted[i - 1]
                    val b = sorted[i]
                    val lowConfidence = a.lowConfidence || b.lowConfidence
                    drawLine(
                        color = color,
                        start = Offset(px(a.dateEpochDay), py(a.value)),
                        end = Offset(px(b.dateEpochDay), py(b.value)),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (lowConfidence) dashed else null,
                    )
                }
                for (p in sorted) {
                    val center = Offset(px(p.dateEpochDay), py(p.value))
                    if (p.lowConfidence) {
                        drawCircle(color = color, radius = 5.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
                    } else {
                        drawCircle(color = color, radius = 5.dp.toPx(), center = center)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = java.time.LocalDate.ofEpochDay(minX).toString(), style = MaterialTheme.typography.labelMedium)
            Text(text = Units.trim(rawMinY), style = MaterialTheme.typography.labelMedium)
            Text(text = java.time.LocalDate.ofEpochDay(maxX).toString(), style = MaterialTheme.typography.labelMedium)
        }
        Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            series.forEachIndexed { index, s ->
                Box(modifier = Modifier.size(10.dp).background(palette[index % palette.size]))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = s.name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
