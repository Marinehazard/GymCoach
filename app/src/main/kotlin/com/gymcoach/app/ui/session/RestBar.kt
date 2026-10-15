package com.gymcoach.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import kotlinx.coroutines.delay

/** "⏱ Rest 1:42 [+30 s] [skip]" — only while resting; the countdown is announced politely at 10 s (§8.2). */
@Composable
fun RestBar(rest: RestTimerState?, onPlus30: () -> Unit, onSkip: () -> Unit) {
    if (rest == null) return
    var now by remember(rest.forBlockId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rest.endsAtEpochMs) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    val left = ((rest.endsAtEpochMs - now) / 1000L).toInt().coerceAtLeast(0)
    val announce = if (left in 1..10) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Timer, contentDescription = null)
            Text(stringResource(R.string.session_rest, "%d:%02d".format(left / 60, left % 60)), style = MaterialTheme.typography.titleLarge, modifier = announce)
            OutlinedButton(onClick = onPlus30) { Text(stringResource(R.string.session_rest_plus30)) }
            OutlinedButton(onClick = onSkip) { Text(stringResource(R.string.session_rest_skip)) }
        }
    }
}
