@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.ExplanationChip
import com.gymcoach.app.ui.components.MuscleBandBars
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.PlannedSlot
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.planner.realize.RirStack
import com.gymcoach.core.units.Units
import java.time.format.TextStyle
import java.util.Locale

/** Week selector chips ("1 … N", deload marked). */
@Composable
fun WeekSelector(weeks: List<MesoWeek>, selected: Int, deloadIndex: Int?, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (w in weeks) FilterChip(selected = w.index == selected, onClick = { onSelect(w.index) }, label = { Text((if (w.index == deloadIndex) "D" else "") + w.index) })
    }
}

/** Seven-day strip: sessions, cardio days and rest days of the selected week. */
@Composable
fun WeekStrip(week: MesoWeek, onOpenSession: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (day in 1..7) {
            val date = PlanViewModel.date(week, day)
            val session = week.sessions.firstOrNull { it.weekday == day }
            val cardio = week.cardio.filter { it.weekday == day }
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = session != null) { session?.let { onOpenSession(it.id) } }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (session != null) Text(session.name + " · " + Math.round(session.estimatedMinutes) + " min · " + session.slots.sumOf { it.sets } + " sets", style = MaterialTheme.typography.bodyLarge)
                    else if (cardio.isEmpty()) Text(stringResource(R.string.plan_rest), style = MaterialTheme.typography.bodyLarge)
                    for (c in cardio) Text(stringResource(R.string.plan_cardio_line, c.minutes, c.kind.name.lowercase().replace('_', ' ')), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Weekly ledger: fractional/direct toggle, targets vs achieved, carryover (RP landmarks show in the direct view). */
@Composable
fun LedgerCard(view: WeeklyLedgerView, onToggleDirect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.plan_ledger_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = view.showDirect, onClick = onToggleDirect, label = { Text(stringResource(if (view.showDirect) R.string.plan_direct else R.string.plan_fractional)) })
            }
            if (view.rows.isEmpty()) Text(stringResource(R.string.plan_ledger_empty), style = MaterialTheme.typography.bodyMedium)
            else MuscleBandBars(rows = view.rows, showDirect = view.showDirect)
            val carry = view.carryover.filter { it.value > 0.0 }
            if (carry.isNotEmpty()) Text(stringResource(R.string.plan_carryover, carry.entries.joinToString { it.key.name.lowercase().replace('_', ' ') + " +" + Units.trim(it.value) }), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun OverrideRow(o: PlanOverride, onRevoke: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(o.mutation.rationale, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onRevoke) { Text(stringResource(R.string.plan_revoke)) }
    }
}

@Composable
fun ProposalRow(m: PlanMutation, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(m.rationale, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text(stringResource(R.string.action_apply)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_later)) }
            }
        }
    }
}

/** One planned slot: name, sets × reps @ RIR, current load, alternates, overflow actions (each an override with an Adjustment). */
@Composable
fun SlotCard(
    slot: PlannedSlot, week: MesoWeek, kb: KnowledgeBase, goal: com.gymcoach.core.model.Goal, states: List<ProgressionState>,
    alternates: List<Exercise>, templateId: String,
    onSwap: (to: String) -> Unit, onSets: (Int) -> Unit, onRange: (Int, Int) -> Unit, onExclude: () -> Unit, onRule: (String) -> Unit,
) {
    val ex = kb.exercise(slot.exerciseId)
    var menu by remember { mutableStateOf(false) }
    val pres = ex?.let { kb.prescription(it, goal) }
    val rir = pres?.let { (RirStack.profileTargetRir(it) + week.rirOffset).coerceAtLeast(0) }
    val load = states.firstOrNull { it.key.exerciseId == slot.exerciseId }?.workingLoadKg
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(ex?.name ?: slot.exerciseId, style = MaterialTheme.typography.titleMedium)
                    Text(
                        slot.sets.toString() + " × " + (pres?.let { "${it.range.lo}–${it.range.hi}" } ?: "–") + (rir?.let { " @$it" } ?: "") +
                            (load?.let { " · " + Units.trim(it) + " kg" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.training_cd_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val muscle = ex?.primaryMuscles?.firstOrNull()
                    if (muscle != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.plan_more_sets)) }, onClick = { menu = false; onSets(1) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.plan_fewer_sets)) }, onClick = { menu = false; onSets(-1) })
                    }
                    if (pres != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.plan_lighter_zone)) }, onClick = { menu = false; onRange(pres.range.lo + 2, pres.range.hi + 2) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.plan_heavier_zone)) }, onClick = { menu = false; onRange(maxOf(1, pres.range.lo - 2), maxOf(1, pres.range.hi - 2)) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.plan_exclude)) }, onClick = { menu = false; onExclude() })
                }
            }
            ExplanationChip(explanation = slot.explanation, onRule = onRule)
            if (alternates.isNotEmpty()) {
                Text(stringResource(R.string.plan_alternates), style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in alternates) androidx.compose.material3.AssistChip(onClick = { onSwap(a.id) }, label = { Text(a.name) })
                }
            }
        }
    }
}

@Composable
fun SessionHeader(session: PlannedSession) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(session.name, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.plan_session_meta, Math.round(session.estimatedMinutes).toInt(), session.slots.sumOf { it.sets }), style = MaterialTheme.typography.bodyLarge)
    }
}
