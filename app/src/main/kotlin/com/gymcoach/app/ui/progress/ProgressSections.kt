@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.app.ui.components.LineChart
import com.gymcoach.app.ui.components.MuscleBandBars
import com.gymcoach.core.model.Joint
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.units.Units
import java.time.LocalDate

@Composable
fun ProgressCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun RangeChips(range: Int, onRange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (d in listOf(30, 90, 180, 365)) FilterChip(selected = range == d, onClick = { onRange(d) }, label = { Text(stringResource(R.string.progress_days, d)) })
    }
}

@Composable
fun StrengthCard(cards: List<LiftCard>, unitLabel: String, onOpen: (String) -> Unit) {
    ProgressCard(stringResource(R.string.progress_strength)) {
        if (cards.isEmpty()) Text(stringResource(R.string.progress_need_sessions, 3))
        for (c in cards) {
            Column(modifier = Modifier.fillMaxWidth().clickable { onOpen(c.exerciseId) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        (c.bestE1rm14d?.let { Units.trim(it) + " kg e1RM" } ?: "") + (c.delta4w?.let { "  " + (if (it >= 0) "+" else "") + Units.trim(it) + " / 4 w" } ?: "") +
                            if (c.lowConfidence) "  ~" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (c.gymScoped) Text(stringResource(R.string.progress_gym_scoped), style = MaterialTheme.typography.labelMedium)
                if (c.spark.points.size >= 2) LineChart(series = listOf(c.spark), modifier = Modifier.fillMaxWidth().height(72.dp), yLabel = unitLabel)
            }
        }
    }
}

@Composable
fun VolumeCard(state: ProgressUiState, showDirect: Boolean, onToggle: () -> Unit) {
    ProgressCard(stringResource(R.string.progress_volume)) {
        FilterChip(selected = showDirect, onClick = onToggle, label = { Text(stringResource(if (showDirect) R.string.plan_direct else R.string.plan_fractional)) })
        if (state.volume.isEmpty()) Text(stringResource(R.string.progress_need_sessions, 1)) else MuscleBandBars(rows = state.volume, showDirect = showDirect)
        Text(stringResource(R.string.progress_fractional_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun BodyCard(state: ProgressUiState, unitLabel: String) {
    ProgressCard(stringResource(R.string.progress_body)) {
        if (state.weight.points.size < 3) Text(stringResource(R.string.progress_need_weighins))
        else LineChart(series = listOf(state.weight, state.weightMa7), modifier = Modifier.fillMaxWidth().height(160.dp), yLabel = unitLabel)
    }
}

@Composable
fun ReadinessCard(state: ProgressUiState) {
    ProgressCard(stringResource(R.string.progress_readiness)) {
        if (state.readiness.isEmpty()) Text(stringResource(R.string.progress_need_checkins))
        else Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((d, band) in state.readiness.takeLast(21)) Text(bandGlyph(band) + d.dayOfMonth, style = MaterialTheme.typography.labelMedium)
        }
        state.sleepAvg?.let { Text(stringResource(R.string.progress_sleep_avg, Units.trim(Math.round(it * 10.0) / 10.0)), style = MaterialTheme.typography.bodyLarge) }
        state.rhr?.let { if (it.points.size >= 2) LineChart(series = listOf(it), modifier = Modifier.fillMaxWidth().height(96.dp), yLabel = "bpm") }
    }
}

private fun bandGlyph(b: ReadinessBand): String = when (b) { ReadinessBand.GREEN -> "G"; ReadinessBand.AMBER -> "A"; ReadinessBand.RED -> "R" }

@Composable
fun WeeklyNumbersCard(title: String, rows: List<Pair<LocalDate, String>>, empty: String) {
    ProgressCard(title) {
        if (rows.isEmpty()) Text(empty)
        for ((d, v) in rows.takeLast(8)) Row { Text(d.toString(), modifier = Modifier.weight(1f)); Text(v, style = MaterialTheme.typography.titleSmall) }
    }
}

@Composable
fun TrainingLoadCard(state: ProgressUiState, onRule: (String) -> Unit) {
    ProgressCard(stringResource(R.string.progress_load)) {
        if (state.trainingLoad.isEmpty()) Text(stringResource(R.string.progress_need_sessions, 2))
        for ((d, v) in state.trainingLoad.takeLast(8)) Row { Text(d.toString(), modifier = Modifier.weight(1f)); Text(v.toString(), style = MaterialTheme.typography.titleSmall) }
        state.spikeNote?.let { ExplanationChip(explanation = it, onRule = onRule) }
    }
}

@Composable
fun MobilityCard(minutes: Map<Joint, Int>) {
    ProgressCard(stringResource(R.string.progress_mobility)) {
        if (minutes.isEmpty()) Text(stringResource(R.string.progress_need_mobility))
        for ((j, m) in minutes) Row { Text(j.name.lowercase().replace('_', ' '), modifier = Modifier.weight(1f)); Text("$m min", style = MaterialTheme.typography.titleSmall) }
    }
}
