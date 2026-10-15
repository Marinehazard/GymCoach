package com.gymcoach.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** §8.4 / §8.2: skeleton rows instead of a blocking spinner. */
@Composable
fun LoadingBox() {
    val color = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (fraction in listOf(0.9f, 0.7f, 0.8f, 0.5f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color),
            )
        }
    }
}
