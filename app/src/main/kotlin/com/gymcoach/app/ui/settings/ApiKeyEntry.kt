package com.gymcoach.app.ui.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.gymcoach.app.R

/** Reads the first clipboard item as text, or null. Android 12+ shows its own "pasted from clipboard" toast. */
private fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/**
 * §8.18 API-key entry: masked field, paste button, prefix validation (only `sk-ant-api…`; anything else shows
 * `backend_key_rejected`), Save, Remove. The stored key is never shown again and never logged.
 */
@Composable
fun ApiKeyEntry(
    hasKey: Boolean,
    draft: String,
    error: String?,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (hasKey) stringResource(R.string.set_key_stored) else stringResource(R.string.set_key_none),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.set_key_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = error != null,
            trailingIcon = {
                IconButton(onClick = { readClipboard(context)?.let(onDraft) }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.set_key_paste))
                }
            },
            supportingText = {
                when (error) {
                    null -> Text(stringResource(R.string.set_key_hint))
                    KeyError.EMPTY -> Text(stringResource(R.string.set_key_empty))
                    KeyError.STORE -> Text(stringResource(R.string.set_key_store_failed))
                    else -> Text(stringResource(R.string.backend_key_rejected))
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = draft.isNotBlank()) { Text(stringResource(R.string.set_key_save)) }
            if (hasKey) OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.set_key_remove)) }
        }
    }
}
