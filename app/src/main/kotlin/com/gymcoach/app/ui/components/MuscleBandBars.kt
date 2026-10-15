package com.gymcoach.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleBandRow
import com.gymcoach.core.units.Units

/**
 * §8.4: per-muscle weekly bars from the §2.14 [MuscleBandRow] (produced by `WeeklyLedger.rows`).
 * Achieved fractional sets = solid; planned remaining = translucent continuation; direct sets = thin
 * inner bar (only when [showDirect]); the RP MEV..MRV band = background; the target = vertical
 * marker (absent for TIER_3 rows, which show the advisory cap only).
 */
@Composable
fun MuscleBandBars(rows: List<MuscleBandRow>, showDirect: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val bandColor = colorScheme.surfaceVariant
    val achievedColor = colorScheme.primary
    val remainingColor = colorScheme.primary.copy(alpha = 0.35f)
    val directColor = colorScheme.tertiary
    val targetColor = colorScheme.onSurface
    val maxValue = rows.maxOfOrNull { row ->
        maxOf(
            row.achievedFractional + row.plannedRemaining,
            row.direct.toDouble(),
            row.target ?: 0.0,
            row.rpMrv?.hi?.toDouble() ?: 0.0,
        )
    }?.coerceAtLeast(1.0) ?: 1.0

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = muscleLabel(row.muscle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(110.dp),
                )
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    fun x(v: Double): Float = (v / maxValue * w).toFloat().coerceIn(0f, w)
                    val mev = row.rpMev
                    val mrv = row.rpMrv
                    if (mev != null && mrv != null) {
                        drawRect(color = bandColor, topLeft = Offset(x(mev.lo.toDouble()), 0f), size = Size(x(mrv.hi.toDouble()) - x(mev.lo.toDouble()), h))
                    }
                    drawRect(color = achievedColor, topLeft = Offset(0f, h * 0.15f), size = Size(x(row.achievedFractional), h * 0.7f))
                    drawRect(
                        color = remainingColor,
                        topLeft = Offset(x(row.achievedFractional), h * 0.15f),
                        size = Size(x(row.achievedFractional + row.plannedRemaining) - x(row.achievedFractional), h * 0.7f),
                    )
                    if (showDirect) {
                        drawRect(color = directColor, topLeft = Offset(0f, h * 0.4f), size = Size(x(row.direct.toDouble()), h * 0.2f))
                    }
                    val target = row.target
                    if (target != null) {
                        drawRect(color = targetColor, topLeft = Offset(x(target) - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), h))
                    }
                }
                Text(
                    text = rowLabel(row, showDirect),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(84.dp),
                )
            }
        }
    }
}

/** "7.5 / 12" (plus " · 6 direct" when shown) or "7.5 (advisory)" for TIER_3 rows without a target. */
fun rowLabel(row: MuscleBandRow, showDirect: Boolean): String {
    val target = row.target
    val main = if (target == null) "${Units.trim(row.achievedFractional)} (advisory)"
    else "${Units.trim(row.achievedFractional)} / ${Units.trim(target)}"
    return if (showDirect) "$main · ${row.direct} direct" else main
}

/** "front_delts" → "front delts" (display label only). */
fun muscleLabel(muscle: Muscle): String = muscle.name.lowercase().replace('_', ' ')
