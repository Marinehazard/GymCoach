@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gymcoach.app.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.components.RirChips
import com.gymcoach.app.ui.components.Stepper
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.equipment.IncrementResolver
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.SetPrescription
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.units.Units
import kotlinx.coroutines.delay

// Pure logging logic (JVM-testable) lives above the UI marker; the SetLogger composable below it.

/** Draft prefill, parsing and SetLog construction. HR-17: the only place a SetLog is built from user input. */
object SetLogging {
    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    private fun roundHalf(x: Double): Double = Math.round(x * 2.0) / 2.0

    /** The text shown in the load stepper for a kg target in the display unit (lb rounded to 0.5). */
    fun formatLoad(kg: Double, unit: WeightUnit): String =
        if (unit == WeightUnit.KG) Units.trim(kg) else Units.trim(roundHalf(Units.kgToLb(kg)))

    fun stepDisplay(stepKg: Double, unit: WeightUnit): Double =
        if (unit == WeightUnit.KG) stepKg else maxOf(0.5, roundHalf(Units.kgToLb(stepKg)))

    /** text + delta (blank counts as 0), never below [min]; at most 2 decimals, trailing zeros trimmed. */
    fun bump(text: String, delta: Double, min: Double = 0.0): String {
        val v = parse(text) ?: 0.0
        return Units.trim(maxOf(min, v + delta))
    }

    fun isFindLoad(block: ExerciseBlock): Boolean = block.sets.any { it.loadSource == com.gymcoach.core.model.LoadSource.LOAD_FIND }

    /** Warm-up ramps (WU-02) are logged from the warm-up block; in a find-load block (§4.9.2) RAMP sets are the main flow. */
    fun isWarmupRamp(block: ExerciseBlock, p: SetPrescription): Boolean = p.kind == SetKind.RAMP && !isFindLoad(block)

    fun workPrescriptions(block: ExerciseBlock): List<SetPrescription> = block.sets.filter { !isWarmupRamp(block, it) }

    fun hasLogged(block: ExerciseBlock, p: SetPrescription, live: List<SetLog>): Boolean =
        live.any { it.blockId == block.blockId && it.setIndex == p.index && it.supersededById == null }

    /** Position (in block.sets) of the first work prescription without a live logged set, or null. */
    fun firstUnlogged(block: ExerciseBlock, live: List<SetLog>): Int? {
        val i = block.sets.indexOfFirst { !isWarmupRamp(block, it) && !hasLogged(block, it, live) }
        return if (i < 0) null else i
    }

    /** The next (block index, set position) to log after [from]: the superset partner first, then this block, then forward, then wrap. */
    fun nextTarget(bs: List<ExerciseBlock>, from: Int, skipped: Set<String>, live: List<SetLog>): Pair<Int, Int>? {
        if (bs.isEmpty()) return null
        fun open(i: Int): Int? = if (bs[i].blockId in skipped) null else firstUnlogged(bs[i], live)
        val start = from.coerceIn(0, bs.size - 1)
        val group = bs[start].supersetGroup
        if (group != null) {
            for (off in 1 until bs.size) {
                val i = (start + off) % bs.size
                if (bs[i].supersetGroup == group) open(i)?.let { return i to it }
            }
        }
        open(start)?.let { return start to it }
        for (i in start + 1 until bs.size) open(i)?.let { return i to it }
        for (i in 0 until start) open(i)?.let { return i to it }
        return null
    }

    fun withField(d: SetDraft, field: KeypadField, text: String): SetDraft = when (field) {
        KeypadField.LOAD -> d.copy(loadValue = text.trim())
        KeypadField.REPS -> d.copy(reps = text.trim())
        KeypadField.SECONDS -> d.copy(seconds = text.trim())
    }

    /** IncrementResolver.step for the block in today's equipment (barbell 2.5 / micro 1.0, dumbbell rack step, stack step, bodyweight +2.5). */
    fun stepKg(kb: KnowledgeBase, b: ExerciseBlock, loadKg: Double?, eq: SessionEquipment?, profile: Profile?, age: TrainingAge): Double {
        val ex = kb.exercise(b.exerciseId)
        if (ex == null || eq == null || profile == null) return 1.0
        return IncrementResolver(kb).step(ex, b.effectiveLoadType, GymParams(kb, eq), loadKg, profile.sex, age).kg.takeIf { it > 0.0 } ?: 1.0
    }

    fun usesWeight(block: ExerciseBlock): Boolean = when (block.effectiveLoadType) {
        LoadType.BAND, LoadType.CARDIO, LoadType.NONE -> false
        LoadType.BARBELL, LoadType.DUMBBELL, LoadType.MACHINE, LoadType.CABLE, LoadType.BODYWEIGHT, LoadType.KETTLEBELL,
        LoadType.SMITH, LoadType.SLED, LoadType.MEDICINE_BALL -> true
    }

    /** §8.8 prefill: target load; repHi for the first set, then the last logged reps of this exercise this session; target RIR. */
    fun prefill(block: ExerciseBlock, p: SetPrescription, exerciseLive: List<SetLog>, unit: WeightUnit): SetDraft {
        val lastReps = exerciseLive.filter { it.kind != SetKind.RAMP && it.reps != null }.maxByOrNull { it.loggedAt }?.reps
        val band = p.reps
        val reps = when {
            band == null -> ""
            lastReps != null -> lastReps.toString()
            else -> band.hi.toString()
        }
        return SetDraft(
            loadValue = p.loadKg?.takeIf { usesWeight(block) }?.let { formatLoad(it, unit) } ?: "",
            loadUnit = unit, reps = reps, rir = p.rir,
            seconds = p.seconds?.hi?.toString() ?: "", metres = p.metres?.hi?.toString() ?: "",
        )
    }

    fun draftOf(set: SetLog, unit: WeightUnit): SetDraft {
        val w = set.weight
        val shown = if (w == null) "" else if (w.unit == unit) Units.trim(w.entered) else formatLoad(w.kg, unit)
        return SetDraft(shown, unit, set.reps?.toString() ?: "", set.rir, set.seconds?.toString() ?: "", set.metres?.toString() ?: "")
    }

    private fun weightOf(block: ExerciseBlock?, d: SetDraft) =
        if (block != null && !usesWeight(block)) null else parse(d.loadValue)?.let { Units.enteredWeight(it, d.loadUnit) }

    /** The logged row: EnteredWeight(entered, unit, kg) + reps + RIR exactly as in [draft]; one row, side = BOTH (UNI-02). */
    fun build(sessionId: String, block: ExerciseBlock, p: SetPrescription, draft: SetDraft, now: java.time.Instant, id: String): SetLog = SetLog(
        id = id, sessionId = sessionId, blockId = block.blockId, exerciseId = block.exerciseId, setIndex = p.index, kind = p.kind,
        weight = weightOf(block, draft), perHand = p.perHand,
        reps = if (p.reps != null || (p.seconds == null && p.metres == null)) draft.reps.trim().toIntOrNull() else null,
        seconds = if (p.seconds != null) draft.seconds.trim().toIntOrNull() else null,
        metres = if (p.metres != null) draft.metres.trim().toIntOrNull() else null,
        rir = draft.rir, side = Side.BOTH, prescribedLoadKg = p.loadKg, prescribedReps = p.reps, prescribedRir = p.rir, loggedAt = now,
    )

    /** A correction is a NEW row that supersedes [old] (never an in-place update). */
    fun buildCorrection(old: SetLog, draft: SetDraft, now: java.time.Instant, id: String): SetLog = old.copy(
        id = id, weight = if (old.weight == null && parse(draft.loadValue) == null) null else parse(draft.loadValue)?.let { Units.enteredWeight(it, draft.loadUnit) },
        reps = if (old.reps != null || draft.reps.isNotBlank()) draft.reps.trim().toIntOrNull() else null,
        seconds = if (old.seconds != null) draft.seconds.trim().toIntOrNull() else null,
        metres = if (old.metres != null) draft.metres.trim().toIntOrNull() else null,
        rir = draft.rir, loggedAt = now, supersededById = null, supersedesId = old.id,
    )
}

/** Edits of the frozen SessionPlan (stored through WorkoutRepository.updateSession). */
object PlanEdits {
    fun mapBlock(plan: SessionPlan, blockId: String, f: (ExerciseBlock) -> ExerciseBlock): SessionPlan =
        plan.copy(blocks = plan.blocks.map { if (it.blockId == blockId) f(it) else it })

    fun addSet(block: ExerciseBlock): ExerciseBlock {
        val last = block.sets.lastOrNull { it.kind != SetKind.RAMP } ?: block.sets.lastOrNull() ?: return block
        val next = last.copy(index = (block.sets.maxOfOrNull { it.index } ?: 0) + 1)
        return block.copy(sets = block.sets + next)
    }

    /** Removes the last prescription without a live logged set (a planned set, never a logged one). */
    fun removeLastUnlogged(block: ExerciseBlock, live: List<SetLog>): ExerciseBlock {
        val idx = block.sets.indexOfLast { !SetLogging.isWarmupRamp(block, it) && !SetLogging.hasLogged(block, it, live) }
        if (idx < 0) return block
        return block.copy(sets = block.sets.filterIndexed { i, _ -> i != idx })
    }

    /** PROG-04 / find-load accept: new load for every unlogged work set; RAMP sets of a find-load block become WORKING. */
    fun retarget(block: ExerciseBlock, live: List<SetLog>, loadKg: Double, rampToWorking: Boolean): ExerciseBlock = block.copy(
        sets = block.sets.map { p ->
            if (SetLogging.hasLogged(block, p, live) || SetLogging.isWarmupRamp(block, p)) p
            else p.copy(loadKg = loadKg, loadLabel = null,
                kind = if (rampToWorking && p.kind == SetKind.RAMP) SetKind.WORKING else p.kind,
                loadSource = if (rampToWorking) com.gymcoach.core.model.LoadSource.USER_OVERRIDE else p.loadSource)
        },
    )

    fun withOrder(blocks: List<ExerciseBlock>): List<ExerciseBlock> = blocks.mapIndexed { i, b -> b.copy(order = i + 1) }

    /** Replaces [old] by [new]; when sets of [old] are already logged the old block is truncated to them and [new] follows it. */
    fun splice(blocks: List<ExerciseBlock>, old: ExerciseBlock, new: ExerciseBlock, live: List<SetLog>): List<ExerciseBlock> =
        blocks.flatMap { b ->
            if (b.blockId != old.blockId) listOf(b)
            else if (live.none { it.blockId == b.blockId }) listOf(new)
            else listOf(b.copy(sets = b.sets.filter { s -> SetLogging.hasLogged(b, s, live) }), new)
        }

    /** A block added during the session: 2 sets, the goal's default profile, last known working load (no fabricated numbers). */
    fun freeBlock(kb: KnowledgeBase, ex: Exercise, goal: Goal, states: List<ProgressionState>, gymId: String, freeNo: Int, order: Int): ExerciseBlock {
        val pres = kb.prescription(ex, goal)
        val key = ProgressionKey.forExercise(ex.id, ex.loadType, gymId)
        val load = states.firstOrNull { it.key == key }?.workingLoadKg
        val sets = (1..2).map { SetPrescription(index = it, kind = SetKind.WORKING, reps = pres.range, loadKg = load, rir = pres.rir.hi, restSeconds = 90) }
        return ExerciseBlock(blockId = "free$freeNo:${ex.id}", slotId = "free$freeNo", exerciseId = ex.id, optionIndex = 0, effectiveLoadType = ex.loadType,
            slotRole = SlotRole.OPTIONAL, order = order, sets = sets, progressionKey = key,
            explanation = Explanation(listOf(RuleIds.OVERRIDE), "Added by you during the session."))
    }
}

/** Row list for the screen (pure). */
object SessionRows {
    /** "20 kg × 18 @1 (Tue)" from the newest OTHER session that logged each exercise. */
    suspend fun lastTimes(rec: SessionRecord, unit: WeightUnit, zone: java.time.ZoneId, fetch: suspend (String) -> List<SetLog>?): Map<String, String> {
        val out = HashMap<String, String>()
        for (id in rec.plan?.blocks?.map { it.exerciseId }?.distinct() ?: emptyList()) {
            val prior = fetch(id)?.filter { it.sessionId != rec.id && it.kind != SetKind.RAMP && it.supersededById == null } ?: continue
            val newest = prior.maxByOrNull { it.loggedAt }?.sessionId ?: continue
            val last = prior.filter { it.sessionId == newest }.maxByOrNull { it.loggedAt } ?: continue
            val w = last.weight?.let { Units.formatWeight(it, unit) + " × " } ?: ""
            val rir = last.rir?.let { " @$it" } ?: ""
            val day = last.loggedAt.atZone(zone).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
            out[id] = "$w${last.reps ?: last.seconds ?: 0}$rir ($day)"
        }
        return out
    }

    fun build(kb: com.gymcoach.core.knowledge.KnowledgeBase, plan: SessionPlan?, all: List<SetLog>, current: Int, skipped: Set<String>,
              lastTime: Map<String, String>, stepKg: Double, unit: WeightUnit): List<ExerciseRow> {
        if (plan == null) return emptyList()
        val live = all.filter { it.supersededById == null }
        return plan.blocks.mapIndexed { i, b ->
            val ex: Exercise? = kb.exercise(b.exerciseId)
            val work = SetLogging.workPrescriptions(b)
            val complete = work.isNotEmpty() && work.all { SetLogging.hasLogged(b, it, live) }
            val status = when {
                b.blockId in skipped -> RowStatus.SKIPPED
                complete -> RowStatus.DONE
                i == current -> RowStatus.CURRENT
                else -> RowStatus.PENDING
            }
            val logged = all.filter { it.blockId == b.blockId }.sortedWith(compareBy({ it.setIndex }, { it.loggedAt }))
            ExerciseRow(
                block = b, name = ex?.name ?: UNAVAILABLE, logged = logged, status = status, lastTimeSummary = lastTime[b.exerciseId],
                hint = topOfRangeHint(b, work, live, stepKg, unit, complete), substitutedFrom = b.substitutedFor?.let { kb.exercise(it)?.name ?: it },
                warnings = b.warnings, findLoad = SetLogging.isFindLoad(b),
            )
        }
    }

    const val UNAVAILABLE = "Exercise unavailable in this version"

    private fun topOfRangeHint(b: ExerciseBlock, work: List<SetPrescription>, live: List<SetLog>, stepKg: Double, unit: WeightUnit, complete: Boolean): Explanation? {
        if (!complete || !SetLogging.usesWeight(b)) return null
        val hit = work.all { p ->
            val s = live.firstOrNull { it.blockId == b.blockId && it.setIndex == p.index }
            val hi = p.reps?.hi
            val actual = s?.rir
            val planned = p.rir
            s != null && hi != null && (s.reps ?: 0) >= hi && (actual == null || planned == null || actual <= planned)
        }
        if (!hit) return null
        val step = SetLogging.formatLoad(stepKg, unit)
        return Explanation(listOf(RuleIds.PROG_01), "All sets hit the top of the range: next time +$step ${if (unit == WeightUnit.KG) "kg" else "lb"}.")
    }
}


// ==== UI ====

/** Load stepper (long-press → plates, tap → keypad), reps stepper (or seconds), RIR chips. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetLogger(
    state: SessionUiState,
    row: ExerciseRow,
    onLoadMinus: () -> Unit, onLoadPlus: () -> Unit, onOpenKeypad: (KeypadField) -> Unit, onOpenPlates: () -> Unit,
    onRepsMinus: () -> Unit, onRepsPlus: () -> Unit, onSecondsMinus: () -> Unit, onSecondsPlus: () -> Unit,
    onRir: (Int?) -> Unit,
    perSide: Boolean = false,
    onSecondsSet: (String) -> Unit = {},
) {
    var running by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) { while (running) { delay(1000); elapsed += 1 } }
    val block = row.block
    val p = block.sets.getOrNull(state.currentSetIndex)
    val draft = state.draft
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (SetLogging.usesWeight(block)) {
            val unit = if (draft.loadUnit == WeightUnit.KG) "kg" else "lb"
            val perHand = p?.perHand == true
            Text(
                text = stringResource(R.string.session_load_label, unit) + if (perHand) " · " + stringResource(R.string.session_per_hand) else "",
                style = MaterialTheme.typography.labelLarge,
            )
            Stepper(
                value = draft.loadValue.ifBlank { "0" }, onMinus = onLoadMinus, onPlus = onLoadPlus,
                onTap = { onOpenKeypad(KeypadField.LOAD) },
                modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenKeypad(KeypadField.LOAD) }, onLongClick = onOpenPlates),
            )
        } else {
            val label = p?.loadLabel
            if (label != null) Text(text = label, style = MaterialTheme.typography.titleLarge)
        }
        if (p?.seconds != null && p.reps == null) {
            Text(stringResource(R.string.session_seconds_label), style = MaterialTheme.typography.labelLarge)
            Stepper(draft.seconds.ifBlank { "0" }, onSecondsMinus, onSecondsPlus, { onOpenKeypad(KeypadField.SECONDS) }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = { if (running) { running = false; onSecondsSet(elapsed.toString()) } else { elapsed = 0; running = true } }, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) stringResource(R.string.session_stopwatch_stop, elapsed) else stringResource(R.string.session_stopwatch_start))
            }
        } else {
            Text(
                text = stringResource(R.string.session_reps_label) + if (perSide) " · " + stringResource(R.string.session_per_side) else "",
                style = MaterialTheme.typography.labelLarge,
            )
            Stepper(draft.reps.ifBlank { "0" }, onRepsMinus, onRepsPlus, { onOpenKeypad(KeypadField.REPS) }, Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.session_rir_label), style = MaterialTheme.typography.labelLarge)
        RirChips(selected = draft.rir, target = p?.rir, onSelect = onRir)
    }
}
