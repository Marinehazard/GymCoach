package com.gymcoach.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus

/**
 * §8.4 / §8.17: one coach proposal. Apply / Reject / Later while pending or deferred; Undo (when
 * offered) after apply. Validation errors disable Apply; warnings are shown but do not block.
 */
@Composable
fun ProposalCard(
    proposal: Proposal,
    diffPreview: String?,
    onApply: () -> Unit,
    onReject: () -> Unit,
    onLater: () -> Unit,
    onUndo: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = proposalTitle(proposal.toolName), style = MaterialTheme.typography.titleMedium)
            Text(text = proposal.rationale, style = MaterialTheme.typography.bodyLarge)
            if (diffPreview != null) {
                Text(
                    text = diffPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            for (warning in proposal.validationWarnings) {
                Text(text = warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            for (error in proposal.validationErrors) {
                Text(text = error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Text(text = statusLabel(proposal.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (proposal.status) {
                ProposalStatus.PENDING, ProposalStatus.DEFERRED -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onReject) { Text("Reject") }
                        TextButton(onClick = onLater) { Text("Later") }
                        Button(onClick = onApply, enabled = proposal.validationErrors.isEmpty()) { Text("Apply") }
                    }
                }
                ProposalStatus.APPLIED -> {
                    if (onUndo != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onUndo) { Text("Undo") }
                        }
                    }
                }
                ProposalStatus.REJECTED, ProposalStatus.EXPIRED, ProposalStatus.INVALID, ProposalStatus.FAILED -> Unit
            }
        }
    }
}

/** "propose_swap_exercise" → "Swap exercise". */
fun proposalTitle(toolName: String): String {
    val words = toolName.removePrefix("propose_").split('_').filter { it.isNotEmpty() }
    if (words.isEmpty()) return toolName
    return words.joinToString(" ").replaceFirstChar { it.uppercaseChar() }
}

/** Exhaustive (HR-11). */
fun statusLabel(status: ProposalStatus): String = when (status) {
    ProposalStatus.PENDING -> "Waiting for your decision"
    ProposalStatus.APPLIED -> "Applied"
    ProposalStatus.REJECTED -> "Rejected"
    ProposalStatus.DEFERRED -> "Deferred"
    ProposalStatus.EXPIRED -> "Expired"
    ProposalStatus.INVALID -> "Invalid proposal"
    ProposalStatus.FAILED -> "Could not be applied"
}
