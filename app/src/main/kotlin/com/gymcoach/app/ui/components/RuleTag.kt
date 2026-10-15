@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.components

import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** §8.4: outlined chip with a rule id ("VOL-04"); tapping opens `RuleDetailRoute` via [onClick]. */
@Composable
fun RuleTag(ruleId: String, onClick: (String) -> Unit) {
    AssistChip(
        onClick = { onClick(ruleId) },
        label = { Text(ruleId) },
    )
}
