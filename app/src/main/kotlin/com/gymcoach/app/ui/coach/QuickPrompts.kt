@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.coach

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** §8.17 quick prompts. They are user messages sent to the model, so they stay in English like the rest of the chat. */
object QuickPrompts {
    val DEFAULT: List<String> = listOf(
        "Shorten today to 30 min",
        "My shoulder hurts on pressing",
        "Hotel gym Tue–Thu",
        "Explain today's plan",
        "Hotel breakfast: what do I eat?",
        "Why this deload?",
    )
}

/** One row above the composer; tapping fills the draft (the user still taps Send). */
@Composable
fun QuickPromptRow(prompts: List<String>, onPrompt: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (p in prompts) AssistChip(onClick = { onPrompt(p) }, label = { Text(p) })
    }
}
