package com.gymcoach.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** §8.4 / §8.20: every screen's empty state — icon, title, body and an optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionText: String?,
    onAction: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text = body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            Button(onClick = onAction) { Text(actionText) }
        }
    }
}
