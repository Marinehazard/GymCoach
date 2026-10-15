// core/src/main/kotlin/com/gymcoach/core/progression/ProgressionEngine.kt
package com.gymcoach.core.progression

import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.equipment.IncrementResolver
import com.gymcoach.core.equipment.LoadRounder
import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.E1rmConfidence
import com.gymcoach.core.model.E1rmEstimate
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.IncrementDirection
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionKind
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.SessionCloseResult
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SessionSummary
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.planner.PlannerConstants
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class AfterSessionInput(
    val record: SessionRecord,                 // status COMPLETED, plan frozen
    val sets: List<SetLog>,                    // non-superseded sets of the session
    val states: List<ProgressionState>,        // current states for the keys in the plan
    val profile: Profile,
    val trainingAge: TrainingAge,
    val readinessBand: ReadinessBand?,
    val weekMode: WeekMode,
    val weekIndex: Int,
    val gym: GymSet,                           // the session's gym (record.gymId resolved); the step reads GymParams(kb, SessionEquipment(gym)) only (§4.9)
    val today: LocalDate,
    val externalCause: Boolean,                // illness flag, travel week, sleep < 6 h on the day, Red readiness (the engine ORs in DELOAD/REENTRY/TRAVEL_MAINTENANCE and capped sessions itself, §4.9.3)
    val firstMesocycle: Boolean,               // Normalized.firstMesocycle (PROG-05 novice gate)
)

/**
 * §4.9 double progression per ProgressionKey. Pure and deterministic (HR-18): no wall clock — `updatedAt` /
 * `Adjustment.createdAt` are the record's `finishedAt` (else the session date at 00:00 UTC) and adjustment ids are
 * derived from the record and the key. Input SetLogs are only read (HR-17).
 */
class ProgressionEngine(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val increments = IncrementResolver(kb)
    private val rounder = LoadRounder(kb)
    private val plateau = PlateauDetector(c)

    /** §4.9: CALIBRATING from a known (onboarding) load, else UNKNOWN (find-load, §4.9.2). */
    fun initialState(key: ProgressionKey, exercise: Exercise, goal: Goal, knownLoadKg: Double?, now: Instant): ProgressionState {
        val p = kb.prescription(exercise, goal)
        return ProgressionState(
            key = key,
            status = if (knownLoadKg != null) ProgressionStatus.CALIBRATING else ProgressionStatus.UNKNOWN,
            workingLoadKg = knownLoadKg,
            workingReps = p.range,
            repHiBase = p.range.hi,
            targetRir = (p.rir.lo + p.rir.hi + 1) / 2,       // ceil of the RIR band midpoint (== RirStack.profileTargetRir)
            updatedAt = now,
        )
    }

    fun afterSession(input: AfterSessionInput): SessionCloseResult {
        val now = input.record.finishedAt ?: input.record.date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val params = GymParams(kb, SessionEquipment(input.gym))
        val byKey = input.states.associateBy { it.key.id }
        val outStates = ArrayList<ProgressionState>()
        val adjustments = ArrayList<Adjustment>()
        val flags = ArrayList<Explanation>()

        for (g in groups(input)) {
            val before = byKey[g.key.id] ?: initialState(g.key, g.exercise, input.profile.goal, input.profile.knownLoadsKg[g.exercise.id], now)
            val outcome = decide(input, g, before, params, now) ?: continue
            outStates += outcome.state
            flags += outcome.flags
            adjustments += Adjustment(
                id = "prog:${input.record.id}:${g.key.id}",
                sessionId = input.record.id,
                exerciseId = g.exercise.id,
                kind = AdjustmentKind.PROGRESSION,
                ruleIds = outcome.ruleIds,
                summary = outcome.summary,
                inputs = outcome.inputs,
                beforeJson = CoreJson.stored.encodeToString(ProgressionState.serializer(), before),
                afterJson = CoreJson.stored.encodeToString(ProgressionState.serializer(), outcome.state),
                source = OverrideSource.PLANNER,
                createdAt = now,
            )
        }
        return SessionCloseResult(outStates, adjustments, flags)
    }

    /** PROG-04 second clause (§4.9.4): never auto-applied; the chip offers the returned load for sets 2..n. */
    fun inSessionBackoff(block: ExerciseBlock, firstSet: SetLog, params: GymParams): Pair<Double, Explanation>? {
        val exercise = kb.exercise(block.exerciseId) ?: return null
        val target = firstSet.prescribedRir ?: block.sets.firstOrNull { it.kind == SetKind.WORKING }?.rir ?: return null
        val rir = firstSet.rir ?: return null
        if (rir > target - c.prog.prog04HardOffset) return null
        val w = firstSet.weight?.kg ?: return null
        if (w <= 0.0) return null
        val total = if (firstSet.perHand) w * increments.implements(exercise, block.effectiveLoadType) else w
        val rounded = rounder.round(total * c.prog.prog04BackoffFactor, exercise, block.effectiveLoadType, params)
        if (rounded.kg >= total - EPS) return null
        val pctOff = Math.round((1.0 - c.prog.prog04BackoffFactor) * 100.0)
        return rounded.kg to Explanations.of(
            listOf(RuleIds.PROG_04),
            "First set RIR $rir vs target $target: use −$pctOff % (${ProgressionText.kg(rounded.kg)} kg) for the remaining sets?",
            mapOf(
                "first_set_rir" to rir.toString(), "target_rir" to target.toString(), "load_kg" to ProgressionText.kg(total),
                "suggested_kg" to ProgressionText.kg(rounded.kg), "label" to rounded.label,
            ),
            Confidence.MODERATE,
        )
    }

    /** §4.9.2: true = "use this ramp set's load as the working load" (reported RIR ≤ min(target + 1, RIR cap)). */
    fun findLoadDecision(block: ExerciseBlock, rampSet: SetLog): Boolean {
        val rir = rampSet.rir ?: return false
        val target = block.sets.firstOrNull { it.kind != SetKind.RAMP }?.rir ?: rampSet.prescribedRir ?: c.rir.rirCapMax
        return rir <= minOf(target + c.trv.trv04StopRirOffset, c.rir.rirCapMax)
    }

    // ---------------------------------------------------------------------------------------------------------------

    private class Group(val key: ProgressionKey, val exercise: Exercise, val loadType: LoadType, val rows: List<SetLog>)

    /** One working set after UNI-02 pairing; `w` = 1.0 for BOTH rows and LEFT+RIGHT pairs, 0.5 for an unpaired side. */
    private class WSet(val reps: Int, val rir: Int?, val weightKg: Double?, val perHand: Boolean, val prescribedLoadKg: Double?, val w: Double)

    private class Outcome(val state: ProgressionState, val ruleIds: List<String>, val summary: String, val inputs: Map<String, String>, val flags: List<Explanation>)

    /** Blocks of the frozen plan (first block per key wins, others share its state), then free exercises in log order. */
    private fun groups(input: AfterSessionInput): List<Group> {
        val live = input.sets.filter { it.supersededById == null }
            .sortedWith(compareBy<SetLog>({ it.loggedAt }, { it.setIndex }, { it.id }))
        val used = HashSet<String>()
        val out = ArrayList<Group>()
        val seenKeys = HashSet<String>()
        val blocks = input.record.plan?.blocks?.sortedBy { it.order } ?: emptyList()
        for (block in blocks) {
            val ex = kb.exercise(block.exerciseId) ?: continue
            val rows = live.filter { it.id !in used && (it.blockId == block.blockId || (it.blockId == null && it.exerciseId == block.exerciseId)) }
            rows.forEach { used += it.id }
            if (!seenKeys.add(block.progressionKey.id)) continue
            out += Group(block.progressionKey, ex, block.effectiveLoadType, rows)
        }
        val rest = live.filter { it.id !in used }
        for (exerciseId in rest.map { it.exerciseId }.distinct()) {
            val ex = kb.exercise(exerciseId) ?: continue
            val key = ProgressionKey.forExercise(ex.id, ex.loadType, input.record.gymId)
            if (!seenKeys.add(key.id)) continue
            out += Group(key, ex, ex.loadType, rest.filter { it.exerciseId == exerciseId })
        }
        return out
    }

    private fun pair(rows: List<SetLog>): Pair<List<WSet>, Boolean> {
        val counted = rows.filter { it.kind in COUNTED }
        val out = ArrayList<WSet>()
        var unpaired = false
        val grouped = LinkedHashMap<String, MutableList<SetLog>>()
        for (r in counted.sortedWith(compareBy<SetLog>({ it.setIndex }, { it.loggedAt }, { it.id }))) {
            grouped.getOrPut("${r.sessionId}|${r.blockId}|${r.setIndex}") { ArrayList() }.add(r)
        }
        for ((_, list) in grouped) {
            val both = list.filter { it.side == Side.BOTH }
            for (b in both) out += WSet(repsOf(b), b.rir, b.weight?.kg, b.perHand, b.prescribedLoadKg, 1.0)
            val lefts = list.filter { it.side == Side.LEFT }
            val rights = list.filter { it.side == Side.RIGHT }
            val n = minOf(lefts.size, rights.size)
            for (i in 0 until n) {
                val l = lefts[i]
                val r = rights[i]
                val rir = if (l.rir == null) r.rir else if (r.rir == null) l.rir else minOf(l.rir, r.rir)
                out += WSet(minOf(repsOf(l), repsOf(r)), rir, l.weight?.kg ?: r.weight?.kg, l.perHand, l.prescribedLoadKg ?: r.prescribedLoadKg, 1.0)
            }
            for (x in lefts.drop(n) + rights.drop(n)) {
                unpaired = true
                out += WSet(repsOf(x), x.rir, x.weight?.kg, x.perHand, x.prescribedLoadKg, 0.5)
            }
        }
        return out to unpaired
    }

    private fun repsOf(s: SetLog): Int = s.reps ?: s.seconds ?: s.metres ?: 0

    private fun decide(input: AfterSessionInput, g: Group, before: ProgressionState, params: GymParams, now: Instant): Outcome? {
        val (working, unpaired) = pair(g.rows)
        if (working.isEmpty()) return null
        val ex = g.exercise
        val lt = g.loadType
        val today = input.record.date
        val impl = increments.implements(ex, lt)
        val first = working[0]
        val loggedTotal: Double? = first.weightKg?.let { if (first.perHand) it * impl else it }
        val extraIds = if (unpaired) listOf(RuleIds.UNI_02) else emptyList()
        val extraInputs = if (unpaired) mapOf("unpaired_side_sets" to "0.5 each (UNI-02)") else emptyMap()

        // Week-mode status transitions (§4.9.1).
        var state = before
        when (input.weekMode) {
            WeekMode.DELOAD -> if (state.status in FROM_TRAINING) state = state.copy(status = ProgressionStatus.DELOADING, noProgressCount = 0)
            WeekMode.REENTRY -> if (state.status in FROM_TRAINING || state.status == ProgressionStatus.DELOADING) state = state.copy(status = ProgressionStatus.REENTRY)
            WeekMode.NORMAL -> if (state.status == ProgressionStatus.DELOADING || state.status == ProgressionStatus.REENTRY) state = state.copy(status = ProgressionStatus.PROGRESSING)
            WeekMode.TRAVEL_MAINTENANCE -> Unit
        }

        val capped = working.any { s -> s.prescribedLoadKg != null && before.workingLoadKg != null && s.prescribedLoadKg < before.workingLoadKg - EPS }
        val nonNormalWeek = input.weekMode != WeekMode.NORMAL
        val unfair = input.externalCause || nonNormalWeek || capped || input.readinessBand == ReadinessBand.RED

        val audited = working.map { s -> val r = s.rir ?: state.targetRir; if (s.reps > c.rir.rir05HighRepThreshold) r + 1 else r }
        val rir05Applied = working.any { it.reps > c.rir.rir05HighRepThreshold }
        val allAtHi = working.indices.all { i -> working[i].reps >= state.workingReps.hi && audited[i] <= state.targetRir }
        val summary = SessionSummary(
            date = today,
            loadKg = loggedTotal,
            bestReps = working.maxOf { it.reps },
            minRir = working.mapNotNull { it.rir }.minOrNull(),
            allSetsAtHi = allAtHi,
            e1rm = bestE1rm(working, impl, today),
            loadXReps = if (g.key.gymScope != null) working.mapNotNull { s -> s.weightKg?.let { w -> (if (s.perHand) w * impl else w) * s.reps } }.maxOrNull() else null,
            readinessBand = input.readinessBand,
            weekMode = input.weekMode,
            fair = !unfair,
        )
        val rir05Ids = if (rir05Applied) listOf(RuleIds.RIR_05) else emptyList()

        // UNKNOWN → CALIBRATING: the find-load session fixes the working load (§4.9.2).
        if (state.status == ProgressionStatus.UNKNOWN) {
            val firstWorking = g.rows.filter { it.kind == SetKind.WORKING || it.kind == SetKind.CALIBRATION }.minByOrNull { it.setIndex }
            val load = firstWorking?.weight?.kg?.let { if (firstWorking.perHand) it * impl else it } ?: loggedTotal
            val after = finish(
                state.copy(status = ProgressionStatus.CALIBRATING, workingLoadKg = load, calibrationSessions = 1),
                summary, today, now, increment = false,
            )
            val text = "${ex.name}: find-load done — working load ${load?.let { ProgressionText.kg(it) + " kg" } ?: "body weight"} (TRV-04), calibrating"
            return Outcome(after, listOf(RuleIds.TRV_04) + extraIds, text, mapOf("working_load_kg" to (load?.let { ProgressionText.kg(it) } ?: "")) + extraInputs, emptyList())
        }

        val allEasy = audited.all { it >= state.targetRir + c.prog.prog04EasyOffset }
        val belowWeight = working.indices.filter { working[it].reps < state.workingReps.lo }.sumOf { working[it].w }
        val below = belowWeight >= 2.0 - EPS
        val coarse = lt == LoadType.DUMBBELL || lt == LoadType.KETTLEBELL || lt == LoadType.MACHINE || lt == LoadType.CABLE || lt == LoadType.SLED
        val hitsNeeded = if (ex.progression == ProgressionKind.BAND_PROGRESSION || input.trainingAge == TrainingAge.ADVANCED || coarse) 2 else 1
        val hits = if (allAtHi) state.consecutiveHits + 1 else 0
        val daysSinceInc = state.lastIncrementDate?.let { ChronoUnit.DAYS.between(it, today) }
        val cadenceOk = when (input.trainingAge) {
            TrainingAge.NOVICE -> true
            TrainingAge.INTERMEDIATE, TrainingAge.ADVANCED -> daysSinceInc == null || daysSinceInc >= c.prog.prog03IntermediateMinDays.toLong()
        }
        val noviceGate = input.trainingAge == TrainingAge.NOVICE && input.firstMesocycle && input.weekIndex <= c.prog.prog05NoviceWeeks
        val blocker: Pair<String, String>? = when {
            noviceGate -> RuleIds.PROG_05 to "novice weeks 1–${c.prog.prog05NoviceWeeks}: technique first, no increments"
            input.readinessBand == ReadinessBand.AMBER || input.readinessBand == ReadinessBand.RED ->
                RuleIds.RDY_02 to "readiness ${input.readinessBand.name.lowercase()}: no increments today"
            input.weekMode == WeekMode.DELOAD -> RuleIds.DLD_02 to "deload week: no increments"
            input.weekMode == WeekMode.REENTRY -> RuleIds.DLD_04 to "re-entry week: no increments"
            input.weekMode == WeekMode.TRAVEL_MAINTENANCE -> RuleIds.TRV_01 to "travel week: maintenance, no increments"
            capped -> RuleIds.R4_2_6 to "capped session: state kept at ${before.workingLoadKg?.let { ProgressionText.kg(it) } ?: "?"} kg"
            else -> null
        }
        val progressionAllowed = ex.progression != ProgressionKind.NONE
        val wantsIncrement = hits >= hitsNeeded || allEasy

        if (blocker == null && cadenceOk && wantsIncrement && progressionAllowed) {
            val inc = increment(input, g, state, loggedTotal, impl, params, allEasy && hits < hitsNeeded)
            if (inc != null) {
                val after = finish(calibrate(inc.state, loggedTotal, capped, nonNormalWeek), summary, today, now, increment = inc.isIncrement)
                return Outcome(after, inc.ruleIds + rir05Ids + extraIds, inc.text, inc.inputs + extraInputs, emptyList())
            }
        }

        // HOLD.
        val holdIds: List<String>
        val holdText: String
        var next = state
        if (below && !capped) {
            next = next.copy(consecutiveHits = 0)
            holdIds = listOf(RuleIds.PROG_01)
            holdText = "reps below range on ≥ 2 sets: load held"
        } else {
            next = next.copy(consecutiveHits = if (capped) before.consecutiveHits else hits)
            when {
                blocker != null -> { holdIds = listOf(blocker.first); holdText = blocker.second }
                !progressionAllowed -> { holdIds = listOf(RuleIds.PROG_01); holdText = "no automatic progression for this exercise" }
                wantsIncrement && !cadenceOk -> {
                    holdIds = listOf(RuleIds.PROG_03)
                    holdText = "target hit, but only ${daysSinceInc ?: 0} days since the last increment (< ${c.prog.prog03IntermediateMinDays}): load held"
                }
                allAtHi && hits < hitsNeeded -> {
                    holdIds = listOf(RuleIds.PROG_01, RuleIds.R4_5_4)
                    holdText = "top of the range hit $hits of $hitsNeeded sessions (coarse steps / advanced: two-session rule): load held"
                }
                else -> { holdIds = listOf(RuleIds.PROG_01); holdText = "not every set at the top of the range at ≤ target RIR: load held" }
            }
        }
        // CALIBRATING follows the logged load of a fair, uncapped session (the user is still finding it).
        if (next.status == ProgressionStatus.CALIBRATING && loggedTotal != null && !capped && !nonNormalWeek) {
            next = next.copy(workingLoadKg = loggedTotal)
        }
        val verdict = plateau.evaluate(next, summary, !unfair, input.weekMode)
        val enteredPlateau = verdict.status == ProgressionStatus.PLATEAU && next.status != ProgressionStatus.PLATEAU
        next = next.copy(noProgressCount = verdict.noProgressCount, status = verdict.status, plateauStep = if (enteredPlateau) 0 else next.plateauStep)
        next = calibrate(next, loggedTotal, capped, nonNormalWeek)
        val after = finish(next, summary, today, now, increment = false)
        val loadText = after.workingLoadKg?.let { "${ProgressionText.kg(it)} kg" } ?: "body weight"
        val ids = (holdIds + rir05Ids + extraIds + verdict.flags.flatMap { it.ruleIds }).distinct()
        val inputs = mapOf(
            "working_load_kg" to (after.workingLoadKg?.let { ProgressionText.kg(it) } ?: ""),
            "reps" to working.joinToString(",") { it.reps.toString() },
            "rir" to working.joinToString(",") { it.rir?.toString() ?: "-" },
            "target_rir" to state.targetRir.toString(),
            "rep_range" to "${state.workingReps.lo}-${state.workingReps.hi}",
            "consecutive_hits" to after.consecutiveHits.toString(),
            "no_progress_count" to after.noProgressCount.toString(),
            "fair" to (!unfair).toString(),
        ) + extraInputs
        return Outcome(after, ids, "${ex.name}: $loadText kept (${holdIds.first()}: $holdText)", inputs, verdict.flags)
    }

    private class Inc(val state: ProgressionState, val ruleIds: List<String>, val text: String, val inputs: Map<String, String>, val isIncrement: Boolean)

    private fun increment(input: AfterSessionInput, g: Group, state: ProgressionState, loggedTotal: Double?, impl: Int, params: GymParams, byEase: Boolean): Inc? {
        val ex = g.exercise
        val lt = g.loadType
        val why = if (byEase) RuleIds.PROG_04 else RuleIds.PROG_01
        val whyText = if (byEase) "RIR ≥ target+${c.prog.prog04EasyOffset} on all sets" else "all sets at ${state.workingReps.hi} reps ≤ ${state.targetRir} RIR"
        val reset = state.copy(consecutiveHits = 0, noProgressCount = 0)
        val baseBand = baseBand(state)
        return when (ex.progression) {
            ProgressionKind.LINEAR_LOAD, ProgressionKind.DOUBLE_PROGRESSION -> {
                val step = increments.step(ex, lt, params, loggedTotal, input.profile.sex, input.trainingAge)
                if (step.kg <= 0.0) return null
                val stepTotal = if (step.perHand) step.kg * impl else step.kg
                // step.kg is per implement for a pair (perHand) and total otherwise; impl == 1 in the latter case, so both share one basis.
                val perHandLoad = (loggedTotal ?: state.workingLoadKg ?: 0.0) / impl
                val stepBasis = step.kg
                if (perHandLoad > 0.0 && stepBasis / perHandLoad > c.prog.prog02MaxJumpPct / 100.0 && !state.repHiExtended) {
                    val ext = reset.copy(workingReps = Band(state.workingReps.lo, state.workingReps.hi + c.prog.prog02RepExtension), repHiExtended = true)
                    val pct = ProgressionText.num1(stepBasis / perHandLoad * 100.0)
                    return Inc(ext, listOf(RuleIds.PROG_02, why),
                        "${ex.name}: rep ceiling ${state.workingReps.hi} → ${ext.workingReps.hi} first (PROG-02: next step +${ProgressionText.kg(step.kg)} kg is $pct % > ${ProgressionText.num1(c.prog.prog02MaxJumpPct)} %)",
                        mapOf("step_kg" to ProgressionText.kg(step.kg), "jump_pct" to pct, "per_hand" to step.perHand.toString()), isIncrement = false)
                }
                val newLoad: Double = if (ex.increment.direction == IncrementDirection.ASSIST) {
                    val base = listOfNotNull(state.workingLoadKg, loggedTotal).minOrNull() ?: 0.0
                    rounder.round(maxOf(0.0, base - step.kg), ex, lt, params).kg
                } else {
                    val base = maxOf(state.workingLoadKg ?: 0.0, loggedTotal ?: 0.0)
                    val r = rounder.round(base + stepTotal, ex, lt, params).kg
                    if (r <= base + EPS) return null   // nothing heavier can be loaded here (rack max): hold
                    r
                }
                val old = state.workingLoadKg ?: loggedTotal
                val st = reset.copy(workingLoadKg = newLoad, workingReps = Band(state.workingReps.lo, state.repHiBase), repHiExtended = false)
                Inc(st, listOf(why, RuleIds.PROG_02),
                    "${ex.name}: load ${old?.let { ProgressionText.kg(it) } ?: "?"} → ${ProgressionText.kg(newLoad)} kg ($why: $whyText → +${ProgressionText.kg(step.kg)} kg${if (step.perHand) " per hand" else ""})",
                    mapOf("from_kg" to (old?.let { ProgressionText.kg(it) } ?: ""), "to_kg" to ProgressionText.kg(newLoad), "step_kg" to ProgressionText.kg(step.kg)), isIncrement = true)
            }
            ProgressionKind.REP_PROGRESSION -> {
                if (state.workingReps.hi >= state.repHiBase + 4) {
                    val ext = c.prog.prog02BodyweightExternalKg
                    val newLoad = rounder.round((state.workingLoadKg ?: 0.0) + ext, ex, lt, params).kg
                    val st = reset.copy(workingLoadKg = newLoad, workingReps = baseBand)
                    Inc(st, listOf(why, RuleIds.PROG_02), "${ex.name}: +${ProgressionText.kg(ext)} kg external load, reps back to ${baseBand.lo}-${baseBand.hi} (PROG-02 body-weight row)",
                        mapOf("to_kg" to ProgressionText.kg(newLoad)), isIncrement = true)
                } else {
                    val band = state.workingReps.shifted(2, 2)
                    Inc(reset.copy(workingReps = band), listOf(why, RuleIds.PROG_02), "${ex.name}: reps ${state.workingReps.lo}-${state.workingReps.hi} → ${band.lo}-${band.hi} ($why: $whyText)",
                        mapOf("rep_range" to "${band.lo}-${band.hi}"), isIncrement = true)
                }
            }
            ProgressionKind.BAND_PROGRESSION -> {
                val levels = params.bandLevels() ?: LoadRounder.DEFAULT_BAND_LEVELS
                val current = state.bandLevel ?: 0
                if (current + 1 > levels.size - 1) {
                    val band = state.workingReps.shifted(2, 2)
                    Inc(reset.copy(workingReps = band), listOf(why, RuleIds.PROG_02), "${ex.name}: heaviest band reached — reps ${band.lo}-${band.hi} instead",
                        mapOf("band_level" to current.toString()), isIncrement = true)
                } else {
                    Inc(reset.copy(bandLevel = current + 1, workingReps = baseBand), listOf(why, RuleIds.PROG_02),
                        "${ex.name}: next band (${levels[current]} → ${levels[current + 1]}), reps back to ${baseBand.lo}-${baseBand.hi}",
                        mapOf("band_level" to (current + 1).toString()), isIncrement = true)
                }
            }
            ProgressionKind.TIME_PROGRESSION -> {
                if (state.workingReps.hi >= TIME_CEILING_SEC) {
                    Inc(reset, listOf(why, RuleIds.PROG_02), "${ex.name}: ${state.workingReps.hi} s reached — add a harder lever or load next time",
                        mapOf("seconds_hi" to state.workingReps.hi.toString()), isIncrement = false)
                } else {
                    val band = state.workingReps.shifted(TIME_STEP_SEC, TIME_STEP_SEC)
                    Inc(reset.copy(workingReps = band), listOf(why, RuleIds.PROG_02), "${ex.name}: hold ${state.workingReps.lo}-${state.workingReps.hi} s → ${band.lo}-${band.hi} s",
                        mapOf("seconds" to "${band.lo}-${band.hi}"), isIncrement = true)
                }
            }
            ProgressionKind.DISTANCE_OR_TIME -> {
                if (state.workingReps.hi <= state.repHiBase) {
                    val band = state.workingReps.shifted(CARRY_STEP_M, CARRY_STEP_M)
                    Inc(reset.copy(workingReps = band), listOf(why, RuleIds.PROG_02), "${ex.name}: distance +$CARRY_STEP_M m (${band.lo}-${band.hi} m)",
                        mapOf("metres" to "${band.lo}-${band.hi}"), isIncrement = true)
                } else {
                    val newLoad = rounder.round((maxOf(state.workingLoadKg ?: 0.0, loggedTotal ?: 0.0)) + CARRY_STEP_KG * impl, ex, lt, params).kg
                    Inc(reset.copy(workingLoadKg = newLoad, workingReps = baseBand), listOf(why, RuleIds.PROG_02),
                        "${ex.name}: +${ProgressionText.kg(CARRY_STEP_KG)} kg per hand, distance back to ${baseBand.lo}-${baseBand.hi} m",
                        mapOf("to_kg" to ProgressionText.kg(newLoad)), isIncrement = true)
                }
            }
            ProgressionKind.NONE -> null
        }
    }

    /** CALIBRATING → PROGRESSING after c sessions with a working load (§4.9.1); capped / non-NORMAL sessions do not count. */
    private fun calibrate(s: ProgressionState, loggedTotal: Double?, capped: Boolean, nonNormalWeek: Boolean): ProgressionState {
        if (s.status != ProgressionStatus.CALIBRATING || capped || nonNormalWeek) return s
        val n = s.calibrationSessions + 1
        return s.copy(calibrationSessions = n, status = if (n >= CALIBRATION_SESSIONS) ProgressionStatus.PROGRESSING else ProgressionStatus.CALIBRATING)
    }

    /** The profile band the state started from: workingReps shifted back so that hi == repHiBase. */
    private fun baseBand(state: ProgressionState): Band {
        val shift = state.workingReps.hi - state.repHiBase
        val lo = (state.workingReps.lo - shift).coerceAtLeast(1)
        return Band(minOf(lo, state.repHiBase), state.repHiBase)
    }

    /** History, dates, counters and E1RM-04 rolling best — common to every outcome. */
    private fun finish(s: ProgressionState, summary: SessionSummary, today: LocalDate, now: Instant, increment: Boolean): ProgressionState {
        val history = (listOf(summary) + s.history).take(HISTORY_SIZE)
        val windowStart = today.minusDays((c.prog.e1rm04RollingDays - 1).toLong())
        val bestEntry = history.filter {
            it.fair && it.weekMode == WeekMode.NORMAL && it.e1rm?.confidence == E1rmConfidence.HIGH && !it.date.isBefore(windowStart) && !it.date.isAfter(today)
        }.maxByOrNull { it.e1rm?.kg ?: 0.0 }
        var out = s.copy(
            history = history,
            lastSessionDate = today,
            bestE1rm14d = bestEntry?.e1rm?.kg,
            bestE1rm14dDate = bestEntry?.date,
            updatedAt = now,
        )
        out = if (increment) out.copy(lastIncrementDate = today, sessionsSinceIncrement = 0)
        else out.copy(sessionsSinceIncrement = s.sessionsSinceIncrement + 1)
        return out
    }

    /** Best HIGH estimate over the working sets (pair total for a bilateral pair), else the best LOW one (display only). */
    private fun bestE1rm(working: List<WSet>, impl: Int, date: LocalDate): E1rmEstimate? {
        val all = working.mapNotNull { s ->
            val w = s.weightKg ?: return@mapNotNull null
            E1rm.estimate(if (s.perHand) w * impl else w, s.reps, s.rir, date, c.prog.e1rm02MaxReps)
        }
        return all.filter { it.confidence == E1rmConfidence.HIGH }.maxByOrNull { it.kg } ?: all.maxByOrNull { it.kg }
    }

    private companion object {
        const val EPS: Double = 1e-6
        const val HISTORY_SIZE: Int = 8
        const val CALIBRATION_SESSIONS: Int = 2
        const val TIME_STEP_SEC: Int = 10
        const val TIME_CEILING_SEC: Int = 60
        const val CARRY_STEP_M: Int = 5
        const val CARRY_STEP_KG: Double = 2.0
        val COUNTED: Set<SetKind> = setOf(SetKind.WORKING, SetKind.AMRAP, SetKind.CALIBRATION, SetKind.DROP)
        val FROM_TRAINING: Set<ProgressionStatus> = setOf(ProgressionStatus.PROGRESSING, ProgressionStatus.PLATEAU, ProgressionStatus.REGRESSING)
    }
}

/** Locale-independent number text for explanations ("82.5", "80", "16.7"). */
internal object ProgressionText {
    fun kg(x: Double): String = BigDecimal(x).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    fun num1(x: Double): String = BigDecimal(x).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
