package com.gymcoach.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** §8.4: confirm/cancel dialog (abandon session, remove key, reset constants …). */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { Text(body) },
    )
}
