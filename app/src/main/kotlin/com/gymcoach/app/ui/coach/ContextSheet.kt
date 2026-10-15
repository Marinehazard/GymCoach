@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.core.coach.SystemPrompt
import com.gymcoach.core.coach.ToolContract
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.util.IdGen
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** "Context: profile, Hotel Ulm, U/L wk 3, last 14 days" - the chip at the top of the conversation. */
suspend fun contextLine(gyms: GymRepository, plans: PlanRepository, clock: Clock, activeGymId: String): String {
    val all = gyms.getAll()
    val gym = all.firstOrNull { it.id == activeGymId } ?: all.firstOrNull { it.isDefault } ?: all.firstOrNull()
    val meso = plans.getCurrent()
    val today = clock.today()
    val week = meso?.weeks?.indexOfLast { !it.startDate.isAfter(today) }?.let { it + 1 }
    val parts = ArrayList<String>()
    parts.add("profile")
    gym?.let { parts.add(it.name) }
    if (meso != null) parts.add(meso.split.name.replace('_', '/') + (if (week != null && week > 0) " wk $week" else ""))
    parts.add("last 14 days")
    return "Context: " + parts.joinToString(", ")
}

/** Closes the open conversation and creates a fresh OPEN one frozen to the current backend/model/effort (§6.9). */
suspend fun startNewConversation(coach: CoachRepository, settings: SettingsRepository, kb: KnowledgeBase, clock: Clock): ChatConversation {
    val cfg = settings.get()
    coach.openConversation()?.let { coach.updateConversation(it.copy(status = ConversationStatus.CLOSED, endedAt = clock.now())) }
    val created = ChatConversation(
        id = IdGen.newId(), startedAt = clock.now(), backend = cfg.backendMode, model = cfg.modelId, effort = cfg.effort,
        systemPromptHash = SystemPrompt.hash(kb), toolContractVersion = ToolContract.CONTRACT_VERSION, status = ConversationStatus.OPEN,
    )
    coach.createConversation(created)
    return created
}

/** The context/delta/summary text blocks that were really sent (read back from the stored transcript). */
fun sentContextBlocks(messages: List<ChatMessage>): List<String> {
    val out = ArrayList<String>()
    for (m in messages.filter { it.role == ChatRole.USER }) {
        val arr: JsonArray = try {
            CoreJson.stored.parseToJsonElement(m.contentJson).jsonArray
        } catch (e: Exception) {
            continue
        }
        for (block in arr) {
            val obj: JsonObject = (block as? JsonObject) ?: continue
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "text") continue
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: continue
            if (text.startsWith("<context") || text.startsWith("<delta") || text.startsWith("<summary")) out.add(text)
        }
    }
    return out
}

/** §8.17 context sheet: the exact JSON sent to the model, snapshot first, then each delta. */
@Composable
fun ContextSheet(messages: List<ChatMessage>, onDismiss: () -> Unit) {
    val blocks = sentContextBlocks(messages)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.coach_context_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.coach_context_note), style = MaterialTheme.typography.bodySmall)
            if (blocks.isEmpty()) Text(stringResource(R.string.coach_context_none), style = MaterialTheme.typography.bodyMedium)
            for (b in blocks) Text(b, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.su_close)) }
        }
    }
}
