package com.gymcoach.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * §8.4 / §8.20: error card with optional Retry and expandable details (e.g. `error.type` +
 * `request_id` for backend failures — never the API key, §6.10).
 */
@Composable
fun ErrorBanner(title: String, body: String, onRetry: (() -> Unit)?, details: String?) {
    var showDetails by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
            if (showDetails && details != null) {
                Text(text = details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (details != null) {
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Hide details" else "Details")
                    }
                }
                if (onRetry != null) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}
