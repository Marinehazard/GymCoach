@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.BigPrimaryButton
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.app.ui.components.NumberField
import com.gymcoach.app.ui.components.RirChips
import com.gymcoach.app.ui.components.Stepper
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.units.Units
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class SessionPhase { LOADING, WARMUP, WORKING, FINISHING, DONE, RECOVERED, ERROR }
enum class RowStatus { PENDING, CURRENT, DONE, SKIPPED }
enum class SessionSheet { PLATES, SWAP, INFO, NOTES, RED_FLAG, FINISH, KEYPAD, CORRECT_SET, ADD_EXERCISE }
enum class KeypadField { LOAD, REPS, SECONDS }

@Serializable
data class SetDraft(
    @SerialName("load_value") val loadValue: String, @SerialName("load_unit") val loadUnit: WeightUnit, val reps: String,
    val rir: Int?, val seconds: String, val metres: String,
)

data class ExerciseRow(
    val block: ExerciseBlock, val name: String, val logged: List<SetLog>, val status: RowStatus,
    val lastTimeSummary: String?, val hint: Explanation?, val substitutedFrom: String?, val warnings: List<Explanation>, val findLoad: Boolean,
)

data class RestTimerState(val endsAtEpochMs: Long, val totalSec: Int, val forBlockId: String)

data class SessionUiState(
    val phase: SessionPhase, val record: SessionRecord?, val plan: SessionPlan?, val gymName: String,
    val elapsedSec: Int, val progressText: String, val warmupDone: Boolean, val rows: List<ExerciseRow>, val currentIndex: Int,
    val currentSetIndex: Int, val draft: SetDraft, val stepKg: Double, val unit: UnitSystem, val rest: RestTimerState?,
    val modifiers: List<Explanation>, val backoffChip: Pair<Double, Explanation>?, val sheet: SessionSheet?, val error: String?,
)

/** Persisted as AppSettings.sessionDraftJson on every stepper / pointer change (§8.8 RECOVERED). */
@Serializable
data class SessionDraftDoc(
    @SerialName("session_id") val sessionId: String, val draft: SetDraft,
    @SerialName("current_index") val currentIndex: Int, @SerialName("current_set_index") val currentSetIndex: Int,
    @SerialName("warmup_done") val warmupDone: Boolean = false, @SerialName("skipped_blocks") val skippedBlocks: List<String> = emptyList(),
)

sealed class SessionNav {
    data class Summary(val sessionId: String) : SessionNav()
    object Back : SessionNav()
    object SetLogged : SessionNav()
    object RestOver : SessionNav()
}


// ==== UI ====

private fun fmtSecs(total: Int): String = "%d:%02d".format(total / 60, total % 60)

/** Modifier banner: every SessionPlan.modifiers explanation with RuleTags (§8.6 / §8.8, POL-01). */
@Composable
fun ModifierBanner(modifiers: List<Explanation>, onRule: (String) -> Unit) {
    if (modifiers.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in modifiers) ExplanationChip(explanation = m, onRule = onRule)
        }
    }
}

@Composable
fun SessionTopBar(state: SessionUiState, onPause: () -> Unit, onNotes: () -> Unit, onRedFlag: () -> Unit, onAbandon: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val title = (state.plan?.name ?: "") + if (state.gymName.isBlank()) "" else " · " + state.gymName
    TopAppBar(
        title = { Column { Text(title, maxLines = 1); Text(fmtSecs(state.elapsedSec), style = MaterialTheme.typography.labelMedium) } },
        navigationIcon = { IconButton(onClick = onPause) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.session_pause)) } },
        actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.training_cd_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.session_notes)) }, onClick = { menu = false; onNotes() })
                DropdownMenuItem(text = { Text(stringResource(R.string.session_something_wrong)) }, onClick = { menu = false; onRedFlag() })
                DropdownMenuItem(text = { Text(stringResource(R.string.session_abandon)) }, onClick = { menu = false; onAbandon() })
            }
        },
    )
}

/** Collapsible warm-up: raise suggestion + Done, drills, "Skip warm-up" with a reason chip (records an Adjustment). */
@Composable
fun WarmupBlock(plan: SessionPlan, done: Boolean, drillName: (String) -> String, onDone: () -> Unit, onSkip: (String) -> Unit, onRule: (String) -> Unit) {
    var open by remember(done) { mutableStateOf(!done) }
    var skipping by remember { mutableStateOf(false) }
    val w = plan.warmup
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.session_warmup, w.totalMinutes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (done) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.session_done))
                IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.training_cd_toggle)) }
            }
            if (open) {
                if (w.raiseMinutes > 0) Text(stringResource(R.string.session_warmup_raise, w.raiseMinutes, w.raiseExerciseId ?: ""))
                for (d in w.drills) Text("• " + drillName(d.drillId) + " " + d.sets + "×" + (d.reps?.let { "${it.lo}–${it.hi}" } ?: d.seconds?.let { "${it.lo}–${it.hi} s" } ?: ""))
                ExplanationChip(explanation = w.explanation, onRule = onRule)
                if (!done) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDone) { Text(stringResource(R.string.session_warmup_done)) }
                        OutlinedButton(onClick = { skipping = !skipping }) { Text(stringResource(R.string.session_warmup_skip)) }
                    }
                    if (skipping) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (r in listOf(R.string.session_skip_reason_time, R.string.session_skip_reason_warm, R.string.session_skip_reason_pain)) {
                                val text = stringResource(r)
                                FilterChip(selected = false, onClick = { onSkip(text) }, label = { Text(text) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One logged set: "✓ 1 · 20 kg × 17 @1"; a superseded row is struck through and marked "edited". */
@Composable
fun LoggedSetRow(set: SetLog, unit: WeightUnit, onClick: () -> Unit) {
    val old = set.supersededById != null
    val ew = set.weight
    val w = ew?.let { Units.formatWeight(it, unit) + " × " } ?: ""
    val text = "${if (old) "·" else "✓"} ${set.setIndex} · $w${set.reps ?: set.seconds ?: 0}" + (set.rir?.let { " @$it" } ?: "") + if (set.kind == SetKind.RAMP) " (ramp)" else ""
    Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !old, onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (old) TextDecoration.LineThrough else TextDecoration.None, modifier = Modifier.weight(1f))
        if (old) Text(stringResource(R.string.session_edited), style = MaterialTheme.typography.labelMedium)
        else if (ew != null && ew.unit != unit) Text(Units.formatWeight(ew, unit), style = MaterialTheme.typography.labelMedium)
    }
}

/** Upcoming / done / skipped exercise: one collapsed line; tap to jump. */
@Composable
fun CollapsedExerciseRow(row: ExerciseRow, onClick: () -> Unit) {
    val total = SetLogging.workPrescriptions(row.block).size
    val done = row.logged.count { it.supersededById == null && it.kind != SetKind.RAMP }
    val status = when (row.status) {
        RowStatus.DONE -> "✓"; RowStatus.SKIPPED -> "–"; RowStatus.CURRENT -> "▶"; RowStatus.PENDING -> "○"
    }
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$status ${row.name}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("$done/$total", style = MaterialTheme.typography.labelLarge)
    }
}

/** Hint chips above LOG SET: PROG-04 back-off, find-load, top-of-range, warnings. Never auto-applied. */
@Composable
fun HintChips(row: ExerciseRow, backoff: Pair<Double, Explanation>?, findLoadKg: Double?, unit: WeightUnit,
              onApplyBackoff: () -> Unit, onDismissBackoff: () -> Unit, onFindLoad: () -> Unit, onRule: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (w in row.warnings) ExplanationChip(explanation = w, onRule = onRule)
        row.hint?.let { ExplanationChip(explanation = it, onRule = onRule) }
        if (backoff != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExplanationChip(explanation = backoff.second, onRule = onRule)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onApplyBackoff) { Text(stringResource(R.string.session_backoff_apply, SetLogging.formatLoad(backoff.first, unit))) }
                        TextButton(onClick = onDismissBackoff) { Text(stringResource(R.string.training_action_dismiss)) }
                    }
                }
            }
        }
        if (findLoadKg != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.session_find_load, SetLogging.formatLoad(findLoadKg, unit)), modifier = Modifier.weight(1f))
                    Button(onClick = onFindLoad) { Text(stringResource(R.string.training_action_yes)) }
                }
            }
        }
    }
}

/** The current exercise, pinned and expanded (§8.8). */
@Composable
fun CurrentExerciseCard(
    state: SessionUiState, row: ExerciseRow, unit: WeightUnit, perSide: Boolean,
    onInfo: () -> Unit, onSwap: () -> Unit, onCorrect: (String) -> Unit, onAddSet: () -> Unit, onRemoveSet: () -> Unit, onSkip: () -> Unit,
    logger: @Composable () -> Unit,
) {
    val p = row.block.sets.getOrNull(state.currentSetIndex)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleLarge)
                    row.substitutedFrom?.let { Text(stringResource(R.string.session_was, it), style = MaterialTheme.typography.labelMedium) }
                }
                IconButton(onClick = onInfo) { Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.session_info)) }
                IconButton(onClick = onSwap) { Icon(Icons.Filled.SwapHoriz, contentDescription = stringResource(R.string.session_swap)) }
            }
            row.lastTimeSummary?.let { Text(stringResource(R.string.session_last, it), style = MaterialTheme.typography.bodyMedium) }
            if (p != null) {
                val reps = p.reps?.let { "${it.lo}–${it.hi}" } ?: p.seconds?.let { "${it.lo}–${it.hi} s" } ?: p.metres?.let { "${it.lo}–${it.hi} m" } ?: ""
                Text(stringResource(R.string.session_target, reps, p.rir?.toString() ?: "–") + if (perSide) " · " + stringResource(R.string.session_per_side) else "",
                    style = MaterialTheme.typography.titleMedium)
            }
            for (s in row.logged) LoggedSetRow(s, unit, onClick = { onCorrect(s.id) })
            if (p != null) Text("○ ${p.index} · ${stringResource(R.string.session_you_are_here)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            logger()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAddSet) { Text(stringResource(R.string.session_add_set)) }
                TextButton(onClick = onRemoveSet) { Text(stringResource(R.string.session_remove_set)) }
                TextButton(onClick = onSkip) { Text(stringResource(R.string.session_skip_exercise)) }
            }
        }
    }
}

@Composable
fun NotesContent(initial: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.session_notes), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        BigPrimaryButton(text = stringResource(R.string.training_action_save), onClick = { onSave(text) })
    }
}

/** Correct a logged set: the saved correction is a NEW row that supersedes the old one (the old one stays, struck through). */
@Composable
fun CorrectSetContent(set: SetLog, unit: WeightUnit, onSave: (SetDraft) -> Unit) {
    var d by remember(set.id) { mutableStateOf(SetLogging.draftOf(set, unit)) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.session_correct_title, set.setIndex), style = MaterialTheme.typography.titleLarge)
        if (set.weight != null) NumberField(value = d.loadValue, onValueChange = { d = d.copy(loadValue = it) }, label = stringResource(R.string.session_load_label, if (unit == WeightUnit.KG) "kg" else "lb"), unit = if (unit == WeightUnit.KG) "kg" else "lb", modifier = Modifier.fillMaxWidth())
        if (set.reps != null) Stepper(d.reps.ifBlank { "0" }, { d = d.copy(reps = SetLogging.bump(d.reps, -1.0)) }, { d = d.copy(reps = SetLogging.bump(d.reps, 1.0)) }, {}, Modifier.fillMaxWidth())
        RirChips(selected = d.rir, target = set.prescribedRir, onSelect = { d = d.copy(rir = it) })
        BigPrimaryButton(text = stringResource(R.string.training_action_save), onClick = { onSave(d) })
    }
}

@Composable
fun AddExerciseContent(kb: KnowledgeBase, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.session_add_exercise), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = q, onValueChange = { q = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.training_search)) })
        for (e in kb.searchByNameOrAlias(q).filter { !it.isCardioOrMobility }.take(20)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { onPick(e.id) }.padding(vertical = 12.dp))
        }
    }
}
