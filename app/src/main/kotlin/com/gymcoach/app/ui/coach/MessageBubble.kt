@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.ProposalCard
import com.gymcoach.core.coach.ProposalApplier
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanDiff
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.mutation.PlanDiffer
import com.gymcoach.core.repo.BackendError
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.util.IdGen
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** §6.8: Apply = override appended, recompute, PlanDiffer diff, Adjustment(OVERRIDE, CHAT), finalize; Undo within five minutes. */
class ProposalController(
    private val applier: ProposalApplier,
    private val plans: PlanRepository,
    private val workouts: WorkoutRepository,
    private val coach: CoachRepository,
    private val clock: Clock,
    private val recompute: suspend (String) -> Mesocycle?,
) {
    suspend fun apply(p: Proposal) {
        if (p.status != ProposalStatus.PENDING && p.status != ProposalStatus.DEFERRED) return
        try {
            val before = plans.getCurrent()
            val override = applier.apply(p, clock.today())
            val after = recompute("proposal")
            val diff = if (before != null && after != null) PlanDiffer.between(before, after) else PlanDiff(emptyList(), emptyMap(), emptyMap())
            workouts.recordAdjustment(
                Adjustment(
                    id = IdGen.newId(), kind = AdjustmentKind.OVERRIDE, ruleIds = listOf(RuleIds.OVERRIDE),
                    summary = "Coach proposal applied: " + p.rationale.take(160), beforeJson = "{}", afterJson = p.payloadJson,
                    source = OverrideSource.CHAT, proposalId = p.id, overrideId = override.id, createdAt = clock.now(),
                ),
            )
            applier.finalize(p.id, diff, override.id)
        } catch (e: Exception) {
            applier.markFailed(p.id, e.message ?: "could not apply")
        }
    }

    fun canUndo(p: Proposal): Boolean {
        val decided = p.decidedAt ?: return false
        return p.status == ProposalStatus.APPLIED && p.overrideId != null && p.resultSummary?.contains(UNDONE) != true &&
            clock.now().isBefore(decided.plusSeconds(UNDO_WINDOW_SECONDS))
    }

    suspend fun undo(p: Proposal) {
        val overrideId = p.overrideId ?: return
        if (!canUndo(p)) return
        val now = clock.now()
        plans.revokeOverride(overrideId, now)
        workouts.observeAdjustments(UNDO_SCAN_LIMIT).first().firstOrNull { it.proposalId == p.id && it.undoneAt == null }?.let { workouts.undoAdjustment(it.id, now) }
        coach.updateProposal(p.copy(resultSummary = (p.resultSummary ?: "") + " — " + UNDONE))
        recompute("undo")
    }

    companion object {
        const val UNDONE = "undone by user"
        const val UNDO_WINDOW_SECONDS = 300L
        const val UNDO_SCAN_LIMIT = 200
    }
}

/** Reads the flat string fields of `TurnEvent.Done.stopDetailsJson` (e.g. {"phrase":…} or {"category":…}). */
fun parseStopDetails(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        CoreJson.stored.parseToJsonElement(json).jsonObject.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

fun isRetryable(e: BackendError): Boolean = when (e) {
    is BackendError.Transport, is BackendError.ServerError, is BackendError.Overloaded, is BackendError.RateLimited,
    is BackendError.BridgeUnavailable, is BackendError.Unknown, is BackendError.StreamError -> true
    BackendError.NotConfigured, BackendError.Offline, BackendError.Authentication, BackendError.Billing,
    is BackendError.Permission, is BackendError.InvalidRequest, BackendError.SpendCapReached -> false
}

private fun day(n: Int): String = DayOfWeek.of(n.coerceIn(1, 7)).getDisplayName(TextStyle.FULL, Locale.ENGLISH)

/** "Wednesday: +30 min bike, zone 2" style one-liner per mutation (exhaustive, HR-11). */
fun mutationPreview(m: PlanMutation, kb: KnowledgeBase): String {
    fun ex(id: String): String = kb.exercise(id)?.name ?: id
    return when (m) {
        is PlanMutation.SwapExercise -> "Swap " + ex(m.fromExerciseId) + " for " + ex(m.toExerciseId) + " (" + m.scope.name.lowercase().replace('_', ' ') + ")"
        is PlanMutation.ChangeVolume -> m.muscle.name.lowercase().replace('_', ' ') + ": " + (if (m.deltaSetsPerWeek >= 0) "+" else "") + m.deltaSetsPerWeek + " sets/week"
        is PlanMutation.ChangeRepRange -> ex(m.exerciseId) + ": " + m.repLo + "–" + m.repHi + " reps"
        is PlanMutation.AddCardio -> day(m.weekday) + ": +" + m.minutes + " min " + (m.exerciseId?.let { ex(it) } ?: "cardio") + ", " + m.kind.name.lowercase()
        is PlanMutation.RemoveCardio -> day(m.weekday) + ": remove cardio"
        is PlanMutation.ChangeGoal -> "Goal: " + m.goal.name.lowercase().replace('_', ' ') + if (m.startNextWeek) " from next week" else " now"
        is PlanMutation.ChangeSchedule -> listOfNotNull(m.daysPerWeek?.let { "$it days/week" }, m.sessionMinutes?.let { "$it min sessions" }, m.preferredWeekdays?.let { w -> w.joinToString(", ") { day(it).take(3) } }).joinToString(" · ")
        is PlanMutation.SetCheckinQuestion -> "New check-in question: " + m.question
        is PlanMutation.ExcludeExercise -> "Exclude " + ex(m.exerciseId) + (m.until?.let { " until $it" } ?: "")
        is PlanMutation.PinExercise -> "Pin " + ex(m.exerciseId) + (m.templateId?.let { " in $it" } ?: "")
        is PlanMutation.AdjustWorkingLoad -> ex(m.exerciseId) + ": working load " + m.newWorkingLoadKg + " kg"
        is PlanMutation.SetPriorityMuscles -> "Priority muscles: " + m.muscles.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
        is PlanMutation.ScheduleDeload -> if (m.startThisWeek) "Deload this week" else "Deload next week"
        is PlanMutation.SetLimitation -> "Limitation: " + m.limitation.region.name.lowercase().replace('_', ' ') + ", pain " + m.limitation.pain0to10 + "/10"
        is PlanMutation.SetTravelDays -> "Travel " + m.from + " to " + m.to
        is PlanMutation.UpdateNutritionTargets -> listOfNotNull(m.kcalTarget?.let { "$it kcal" }, m.proteinGPerKg?.let { "$it g protein/kg" }).joinToString(" · ")
    }
}

/** Card preview: the stored diff once applied, else the mutation one-liner. */
fun proposalPreview(p: Proposal, kb: KnowledgeBase): String? {
    val summary = p.resultSummary
    if (p.status == ProposalStatus.APPLIED && summary != null && summary.startsWith("{")) {
        try {
            val d = CoreJson.stored.decodeFromString(PlanDiff.serializer(), summary)
            val parts = ArrayList<String>()
            if (d.sessionsChanged.isNotEmpty()) parts.add("Changed: " + d.sessionsChanged.joinToString(", "))
            if (d.exercisesAdded.isNotEmpty()) parts.add("Added: " + d.exercisesAdded.joinToString(", ") { kb.exercise(it)?.name ?: it })
            if (d.exercisesRemoved.isNotEmpty()) parts.add("Removed: " + d.exercisesRemoved.joinToString(", ") { kb.exercise(it)?.name ?: it })
            if (parts.isNotEmpty()) return parts.joinToString("\n")
        } catch (e: Exception) {
            // fall through to the mutation line
        }
    }
    return p.mutation?.let { mutationPreview(it, kb) }
}

private val ID_TOKEN = Regex("[a-z][a-z0-9]*(?:_[a-z0-9]+)+")

/** Replaces known exercise ids by their names; returns the text and the distinct ids found (shown as tappable chips). */
fun exerciseMentions(text: String, kb: KnowledgeBase): Pair<String, List<String>> {
    val found = LinkedHashSet<String>()
    val out = ID_TOKEN.replace(text) { m ->
        val ex = kb.exercises[m.value]
        if (ex == null) m.value else { found.add(ex.id); ex.name }
    }
    return out to found.toList()
}

/** Markdown-lite: **bold**, `inline code`, "- " lists. */
fun markdownLite(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    lines.forEachIndexed { index, raw ->
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            append("• ")
            appendInline(trimmed.drop(2))
        } else {
            appendInline(raw)
        }
        if (index < lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInline(s: String) {
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) {
            val end = s.indexOf("**", i + 2)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > 0) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        append(s[i])
        i++
    }
}

@Composable
fun StreamingBubble(partial: String, knowledge: KnowledgeBase) {
    Bubble(user = false) { Text(markdownLite(exerciseMentions(partial, knowledge).first + "▍"), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun Bubble(user: Boolean, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) { Column(modifier = Modifier.padding(12.dp)) { content() } }
    }
}

/** One transcript entry; assistant entries carry their proposal cards and the SUP-12 caveat when needed. */
@Composable
fun MessageBubble(
    message: ChatMessage,
    proposals: List<Proposal>,
    knowledge: KnowledgeBase,
    canUndo: (Proposal) -> Boolean,
    onApply: (String) -> Unit,
    onReject: (String) -> Unit,
    onLater: (String) -> Unit,
    onUndo: (String) -> Unit,
    onExercise: (String) -> Unit,
) {
    if (message.role == ChatRole.USER) {
        Bubble(user = true) { Text(message.displayText, style = MaterialTheme.typography.bodyLarge) }
        return
    }
    val (text, mentioned) = exerciseMentions(message.displayText, knowledge)
    if (text.isNotBlank()) {
        Bubble(user = false) {
            Text(markdownLite(text), style = MaterialTheme.typography.bodyLarge)
            if (mentioned.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (id in mentioned) AssistChip(onClick = { onExercise(id) }, label = { Text(knowledge.exercise(id)?.name ?: id) })
                }
            }
            if (message.caveatAppended) {
                Text(
                    knowledge.supplements.caveatSup12.joinToString(" ").ifBlank { stringResource(R.string.coach_sup12_fallback) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    for (p in proposals) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            ProposalCard(
                proposal = p, diffPreview = proposalPreview(p, knowledge),
                onApply = { onApply(p.id) }, onReject = { onReject(p.id) }, onLater = { onLater(p.id) },
                onUndo = if (canUndo(p)) ({ onUndo(p.id) }) else null,
            )
        }
    }
}
