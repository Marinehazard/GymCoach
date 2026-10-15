package com.gymcoach.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation

/**
 * §8.4: the explanation message (HR-15: shown exactly as `Explanation.message`) followed by one
 * [RuleTag] per rule id and the confidence label when present.
 */
@Composable
fun ExplanationChip(explanation: Explanation, onRule: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = explanation.message, style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (id in explanation.ruleIds) RuleTag(ruleId = id, onClick = onRule)
            val confidence = explanation.confidence
            if (confidence != null) {
                Text(
                    text = confidenceLabel(confidence),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** §10.6: weak constants are labelled "app default, adjustable". Exhaustive (HR-11). */
fun confidenceLabel(confidence: Confidence): String = when (confidence) {
    Confidence.STRONG -> "strong evidence"
    Confidence.MODERATE -> "moderate evidence"
    Confidence.WEAK -> "weak — app default, adjustable"
    Confidence.CONTESTED -> "contested"
    Confidence.UNVERIFIED -> "unverified"
}
