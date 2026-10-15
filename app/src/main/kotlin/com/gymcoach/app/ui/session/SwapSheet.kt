@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.EmptyState
import com.gymcoach.app.ui.components.LoadingBox
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.Limitation
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.LoadSource
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.substitution.SubstitutionCandidate
import com.gymcoach.core.units.Units

// Pure swap logic (JVM-testable) above the UI marker.

enum class SwapReason { BUSY, NOT_HERE, PAIN, PREFER }
enum class SwapScope { THIS_SESSION, REST_OF_PROGRAM }

data class SwapCandidateRow(val candidate: SubstitutionCandidate, val name: String, val flagNote: String?)
data class SwapUiState(val loading: Boolean, val candidates: List<SwapCandidateRow>, val reason: SwapReason, val error: String?)

object SwapLogic {
    /** Flags of [ex] that matter for the user's ACTIVE limitations (R4 §5.5 mapping of §4.3). */
    fun relevantFlags(ex: Exercise, limitations: List<Limitation>): List<String> {
        val out = ArrayList<String>()
        for (l in limitations.filter { it.active }) {
            when (l.region) {
                LimitationRegion.KNEE -> if (ex.flags.kneeSensitive) out.add("knee-sensitive")
                LimitationRegion.SHOULDER -> if (ex.flags.shoulderSensitive) out.add("shoulder-sensitive")
                LimitationRegion.LOW_BACK -> if (ex.flags.lowBackSensitive || ex.flags.spinalLoading) out.add("low-back-sensitive")
                LimitationRegion.ELBOW_WRIST, LimitationRegion.HIP, LimitationRegion.ANKLE, LimitationRegion.NECK, LimitationRegion.OTHER -> Unit
            }
        }
        return out.distinct()
    }

    /** §8.8: Stage-0 normalize once, then SubstitutionEngine.rank with the stored states and today's equipment. */
    suspend fun rank(
        deps: TrainingDeps, record: SessionRecord?, gym: GymSet?, equipment: SessionEquipment?, block: ExerciseBlock,
        states: List<ProgressionState>, profile: Profile?, reason: SwapReason,
    ): SwapUiState {
        val pr = checkNotNull(profile) { "profile" }
        val eq = checkNotNull(equipment) { "equipment" }
        val c = deps.coordinator.constants()
        val norm = deps.plannerFactory(c).normalize(deps.coordinator.buildInputs(), record?.date ?: deps.clock.today())
        val ranked = deps.substitutionFactory(c).rank(
            block, eq, GymParams(deps.knowledge, eq), states, norm.goal, norm.trainingAge,
            norm.excludedExerciseIds, norm.penalizedExerciseIds, pr.allowAdvancedExercises, pr,
        )
        val rows = ranked.filter { it.available }.mapNotNull { cand ->
            val ex = deps.knowledge.exercise(cand.exerciseId) ?: return@mapNotNull null
            val flags = relevantFlags(ex, pr.limitations)
            if (reason == SwapReason.PAIN && flags.isNotEmpty()) null
            else SwapCandidateRow(cand, ex.name, if (flags.isEmpty()) null else flags.joinToString(", "))
        }
        return SwapUiState(loading = false, candidates = rows, reason = reason, error = null)
    }

    /** The replacement block for the REMAINING sets of [old]; loads come from the engine's prescribed load (never invented). */
    fun makeBlock(deps: TrainingDeps, old: ExerciseBlock, cand: SubstitutionCandidate, gymId: String, reason: SwapReason, remaining: Int): ExerciseBlock {
        val kb = deps.knowledge
        val ex = kb.requireExercise(cand.exerciseId)
        val lt = cand.effectiveLoadType ?: ex.loadType
        val perHand = lt == LoadType.DUMBBELL || lt == LoadType.KETTLEBELL
        val work = old.sets.filter { !SetLogging.isWarmupRamp(old, it) }.takeLast(maxOf(1, remaining))
        val sets = work.mapIndexed { i, p ->
            p.copy(index = i + 1, kind = if (p.kind == SetKind.RAMP) SetKind.WORKING else p.kind, loadKg = cand.prescribedLoadKg, loadLabel = null, perHand = perHand,
                loadSource = if (cand.prescribedLoadKg != null) LoadSource.TRANSFER_SEED else LoadSource.NONE)
        }
        val rule = if (reason == SwapReason.PAIN) RuleIds.INJ_00 else RuleIds.TRV_04
        return old.copy(
            blockId = "${old.slotId}:${ex.id}", exerciseId = ex.id, optionIndex = cand.optionIndex ?: 0, effectiveLoadType = lt,
            substitutedFor = old.substitutedFor ?: old.exerciseId, sets = sets, progressionKey = ProgressionKey.forExercise(ex.id, lt, gymId), warnings = emptyList(),
            explanation = Explanations.of(rule, "Swapped ${kb.exercise(old.exerciseId)?.name ?: old.exerciseId} for ${ex.name} (${reason.name.lowercase().replace('_', ' ')}).",
                "from" to old.exerciseId, "to" to ex.id, "score" to Units.trim(cand.score)),
        )
    }
}

// ==== UI ====

@Composable
fun SwapSheetContent(
    state: SwapUiState,
    onReason: (SwapReason) -> Unit,
    onPick: (exerciseId: String, reason: SwapReason, scope: SwapScope, limitation: LimitationRegion?) -> Unit,
    onSkipExercise: () -> Unit,
    onKeep: () -> Unit,
    activeLimitationRegion: LimitationRegion?,
    ruleClick: (String) -> Unit,
) {
    var scopeChoice by remember { mutableStateOf(SwapScope.THIS_SESSION) }
    var logLimitation by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.session_swap_title), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (r in SwapReason.values()) {
                FilterChip(selected = state.reason == r, onClick = { onReason(r) }, label = { Text(stringResource(reasonLabel(r))) })
            }
        }
        if (state.reason == SwapReason.PAIN) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = logLimitation, onClick = { logLimitation = !logLimitation }, label = { Text(stringResource(R.string.session_swap_log_limitation)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = scopeChoice == SwapScope.THIS_SESSION, onClick = { scopeChoice = SwapScope.THIS_SESSION }, label = { Text(stringResource(R.string.session_swap_scope_session)) })
            FilterChip(selected = scopeChoice == SwapScope.REST_OF_PROGRAM, onClick = { scopeChoice = SwapScope.REST_OF_PROGRAM }, label = { Text(stringResource(R.string.session_swap_scope_program)) })
        }
        if (state.loading) LoadingBox()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.loading && state.error == null && state.candidates.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.SwapHoriz, title = stringResource(R.string.session_swap_empty_title), body = stringResource(R.string.session_swap_empty_body),
                actionText = stringResource(R.string.session_swap_skip), onAction = onSkipExercise,
            )
            OutlinedButton(onClick = onKeep, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.session_swap_keep)) }
        }
        for (row in state.candidates) {
            val c = row.candidate
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    Text(c.rationale, style = MaterialTheme.typography.bodyMedium)
                    c.prescribedLoadKg?.let { Text(stringResource(R.string.session_swap_load, it.toString()), style = MaterialTheme.typography.bodyMedium) }
                    row.flagNote?.let { Text(stringResource(R.string.session_swap_flag, it), color = MaterialTheme.colorScheme.tertiary) }
                    val ledger = c.ledgerDelta.filter { it.value != 0.0 }.entries.take(3).joinToString { "${it.key.name.lowercase().replace('_', ' ')} ${if (it.value > 0) "+" else ""}${it.value}" }
                    if (ledger.isNotEmpty()) Text(stringResource(R.string.session_swap_ledger, ledger), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { onPick(row.candidate.exerciseId, state.reason, scopeChoice, if (logLimitation) activeLimitationRegion ?: LimitationRegion.OTHER else null) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text(stringResource(R.string.session_swap_use, row.name)) }
                }
            }
        }
    }
}

private fun reasonLabel(r: SwapReason): Int = when (r) {
    SwapReason.BUSY -> R.string.session_swap_reason_busy
    SwapReason.NOT_HERE -> R.string.session_swap_reason_not_here
    SwapReason.PAIN -> R.string.session_swap_reason_pain
    SwapReason.PREFER -> R.string.session_swap_reason_prefer
}
