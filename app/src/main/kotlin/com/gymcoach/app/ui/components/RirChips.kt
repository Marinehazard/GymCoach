@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * §8.4: RIR chips 0 · 1 · 2 · 3 · 4+. `selected == 4` means "4 or more". The prescribed target is
 * marked with a dot so the user sees the plan without it being pre-selected (a logged RIR is sacred:
 * it must be the user's own choice, HR-17).
 */
@Composable
fun RirChips(selected: Int?, target: Int?, onSelect: (Int?) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (rir in 0..4) {
            val base = if (rir == 4) "4+" else rir.toString()
            val isTarget = target != null && (if (rir == 4) target >= 4 else target == rir)
            val label = if (isTarget) "$base •" else base
            FilterChip(
                selected = selected == rir,
                onClick = { onSelect(if (selected == rir) null else rir) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 56.dp),
            )
        }
    }
}
