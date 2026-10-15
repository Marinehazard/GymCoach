@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.equipment.IncrementResolver
import com.gymcoach.core.model.ContentStatus
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.progression.E1rm
import com.gymcoach.core.units.Units

// Pure info-sheet data (JVM-testable) above the UI marker.

/** §8.8 info sheet. [howText]/[watchOut] are empty and [contentMissing] is true when the exercise has no authored content. */
data class InfoUiState(
    val exerciseId: String, val name: String, val contentStatus: ContentStatus, val contentMissing: Boolean,
    val howText: List<String>, val watchOut: List<String>, val numbers: List<String>, val sources: List<String>,
)

object InfoLogic {
    suspend fun build(deps: TrainingDeps, block: ExerciseBlock, equipment: SessionEquipment?, unit: WeightUnit, gymId: String): InfoUiState {
        val kb = deps.knowledge
        val ex = kb.exercise(block.exerciseId)
            ?: return InfoUiState(block.exerciseId, SessionRows.UNAVAILABLE, ContentStatus.TODO, true, emptyList(), emptyList(), emptyList(), emptyList())
        val content = ex.content
        val how = if (content == null) emptyList() else listOfNotNull(content.description.ifBlank { null }) + content.setup + content.cues
        val watch = ArrayList<String>()
        if (content != null) { watch.addAll(content.mistakes); watch.addAll(content.safety) }
        if (equipment != null && ex.flags.requiresSpotterOrSafeties) {
            val safe = EquipmentResolver(kb).resolve(ex, equipment).safetiesOrSpotter
            if (!safe) watch.add(0, "No safeties or spotter today → stop at RIR 2 (RIR-03/R4 §5.5).")
        }
        return InfoUiState(ex.id, ex.name, ex.contentStatus, content == null || ex.contentStatus == ContentStatus.TODO, how, watch, numbers(deps, ex, block, equipment, unit, gymId), content?.sources ?: ex.sources)
    }

    private suspend fun numbers(deps: TrainingDeps, ex: Exercise, block: ExerciseBlock, equipment: SessionEquipment?, unit: WeightUnit, gymId: String): List<String> {
        val kb = deps.knowledge
        val out = ArrayList<String>()
        val profile = deps.profile.get()
        val goal = profile?.goal ?: Goal.GENERAL_FITNESS
        val pres = kb.prescription(ex, goal)
        out.add("Profile for ${goal.name.lowercase().replace('_', ' ')}: ${pres.range.lo}–${pres.range.hi} reps @ RIR ${pres.rir.lo}–${pres.rir.hi}")
        if (equipment != null && profile != null) {
            val step = IncrementResolver(kb).step(ex, block.effectiveLoadType, GymParams(kb, equipment), block.sets.firstOrNull()?.loadKg, profile.sex, TrainingAge.INTERMEDIATE)
            out.add("Increment: ${step.explanation.message}")
        }
        val primary = ex.muscles.filter { it.weight >= 1.0 }.joinToString { it.muscle.name.lowercase().replace('_', ' ') }
        val fractional = ex.muscles.joinToString { "${it.muscle.name.lowercase().replace('_', ' ')} ${it.weight}" }
        out.add("Direct: $primary")
        out.add("Fractional (DEF-05): $fractional")
        val sets = deps.workouts.setsForExercise(ex.id, 5)
        val bySession = sets.filter { it.supersededById == null && it.kind != SetKind.RAMP }.groupBy { it.sessionId }.values
            .sortedByDescending { s -> s.maxOf { it.loggedAt } }.take(5)
        for (s in bySession) {
            val best = s.maxByOrNull { (it.weight?.kg ?: 0.0) * (it.reps ?: 0) } ?: continue
            val w = best.weight?.let { Units.formatWeight(it, unit) + " × " } ?: ""
            out.add("Best set: $w${best.reps ?: best.seconds ?: 0}")
        }
        val state = deps.progression.getForExercise(ex.id).firstOrNull { it.key.gymScope == null || it.key.gymScope == gymId }
        val best = sets.filter { it.supersededById == null }.mapNotNull { s ->
            val w = s.weight?.kg; val r = s.reps
            if (w == null || r == null) null else E1rm.estimate(w, r, s.rir, java.time.LocalDate.ofEpochDay(s.loggedAt.epochSecond / 86400))
        }.maxByOrNull { it.kg }
        val e = state?.bestE1rm14d ?: best?.kg
        if (e != null && ex.loadType != com.gymcoach.core.model.LoadType.BODYWEIGHT) out.add("e1RM: ${Units.formatKg(e, unit)}")
        return out
    }
}

// ==== UI ====

@Composable
fun ExerciseInfoContent(info: InfoUiState?) {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf(R.string.session_info_tab_how, R.string.session_info_tab_watch, R.string.session_info_tab_numbers, R.string.session_info_tab_sources)
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (info == null) { Text(stringResource(R.string.training_loading)); return@Column }
        Text(info.name, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.session_info_status, info.contentStatus.name.lowercase()), style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, l -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(stringResource(l)) }) }
        }
        if (info.contentMissing && tab < 2) Text(stringResource(R.string.session_info_not_written), style = MaterialTheme.typography.bodyLarge)
        val lines = when (tab) { 0 -> info.howText; 1 -> info.watchOut; 2 -> info.numbers; else -> info.sources }
        for (line in lines) Text(line, style = MaterialTheme.typography.bodyLarge)
    }
}
