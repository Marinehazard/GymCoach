package com.gymcoach.core.planner

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.SessionTemplates
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.SlotTemplate
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.substitution.SubstitutionEngine
import com.gymcoach.core.volume.MuscleTiers
import java.time.LocalDate
import java.util.Locale

/** Everything Stage 3 needs about the week being planned. */
data class SelectionContext(
    val normalized: Normalized,
    val goal: Goal,
    val trainingAge: TrainingAge,
    val equipment: SessionEquipment,
    val targets: Map<Muscle, Double>,
    val tiers: Map<Muscle, MuscleTier>,
    val states: List<ProgressionState>,
    val profile: Profile,
    val today: LocalDate,
    val travel: Boolean = false,
)

/** One selected slot of the week. */
data class SlotPick(
    val sessionIndex: Int,
    val weekday: Int,
    val templateId: String,
    val slotIndex: Int,
    val slot: SlotTemplate,
    val exercise: Exercise,
    val optionIndex: Int,
    val loadType: LoadType,
    val explanation: Explanation,
)

/** An available candidate with the equipment option it uses in the plan gym. */
data class Candidate(val exercise: Exercise, val optionIndex: Int, val loadType: LoadType, val noRack: Boolean)

/**
 * Stages 3 (§4.6): exercise selection per slot. Order is deterministic and asserted by ExerciseSelectorTest: the heavy-slot
 * pre-pass (STRENGTH rows: every `heavy` slot referenced by a `backoff_of`, weekday then template order, ledgerSoFar = 0),
 * then the walk over the sessions in weekday order and each session's slots in template order. Pins, permanent swaps and
 * `backoff_of` references take a slot outright; otherwise the SEL score decides with ties broken by knowledge order.
 */
class ExerciseSelector(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val resolver = EquipmentResolver(kb)
    private val order: Map<String, Int> = kb.exerciseOrder.withIndex().associate { it.value to it.index }

    fun selectWeek(ctx: SelectionContext, schedule: List<ScheduledTemplate>): List<List<SlotPick?>> {
        val cache = HashMap<String, Candidate?>()
        val ledger = DoubleArray(Muscle.entries.size)
        val prePinned = HashMap<String, SlotPick>()
        val chosen = ArrayList<Pair<Int, String>>()   // (sessionIndex, exerciseId) already selected this week

        // Heavy-slot pre-pass (STRENGTH variants; a no-op when no template of the week carries a backoff_of).
        val referenced = schedule.flatMap { st -> st.template.slots.mapNotNull { it.backoffOf } }.mapNotNull { SessionTemplates.parseRef(it) }.toSet()
        if (referenced.isNotEmpty()) {
            for ((si, st) in schedule.withIndex()) {
                for ((ii, slot) in st.template.slots.withIndex()) {
                    if (!slot.heavy) continue
                    val key = st.template.id + "." + slot.slotId
                    if (referenced.none { it.toString() == key } || prePinned.containsKey(key)) continue
                    val pick = pickFor(ctx, si, st, ii, slot, DoubleArray(Muscle.entries.size), emptyList(), emptyList(), cache, prePass = true)
                    if (pick != null) prePinned[key] = pick
                }
            }
        }

        val out = ArrayList<List<SlotPick?>>()
        for ((si, st) in schedule.withIndex()) {
            val sessionPicks = ArrayList<SlotPick?>()
            val pinUsed = HashSet<String>()
            for ((ii, slot) in st.template.slots.withIndex()) {
                val key = st.template.id + "." + slot.slotId
                val pick: SlotPick? = when {
                    prePinned.containsKey(key) -> prePinned.getValue(key).copy(sessionIndex = si, weekday = st.weekday)
                    slot.backoffOf != null -> {
                        val heavy = prePinned[slot.backoffOf]
                        val cand = heavy?.let { candidateOf(it.exercise, ctx, cache) }
                        if (heavy != null && cand != null && heavy.exercise.pattern in slot.patterns) {
                            SlotPick(si, st.weekday, st.template.id, ii, slot, heavy.exercise, cand.optionIndex, cand.loadType,
                                Explanations.of(listOf(RuleIds.SEL, RuleIds.LOAD_03, RuleIds.PROG_03, RuleIds.VOL_05), "Back-off day for ${heavy.exercise.name} (${slot.backoffOf}): same exercise, 4–8 reps at 85 % of the heavy-day load.",
                                    mapOf("backoff_of" to (slot.backoffOf ?: ""), "exercise_id" to heavy.exercise.id), Confidence.WEAK))
                        } else pickFor(ctx, si, st, ii, slot, ledger, sessionPicks.filterNotNull(), chosen, cache, prePass = false)
                    }
                    else -> pinnedPick(ctx, si, st, ii, slot, pinUsed, cache)
                        ?: pickFor(ctx, si, st, ii, slot, ledger, sessionPicks.filterNotNull(), chosen, cache, prePass = false)
                }
                val final = pick?.let { applySwap(ctx, it, cache) }
                sessionPicks.add(final)
                if (final != null) {
                    chosen.add(si to final.exercise.id)
                    if (slot.required) {
                        val lo = (kb.prescription(final.exercise, ctx.goal).sets?.lo ?: DEFAULT_SETS_LO).toDouble()
                        for (mw in final.exercise.muscles) ledger[mw.muscle.ordinal] += mw.weight * lo
                    }
                }
            }
            out.add(sessionPicks)
        }
        return out
    }

    /** Every available, not excluded candidate whose pattern is in the slot's pattern list, in knowledge order. */
    fun candidates(slot: SlotTemplate, ctx: SelectionContext, cache: HashMap<String, Candidate?> = HashMap()): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (id in kb.exerciseOrder) {
            val e = kb.exercises[id] ?: continue
            if (e.pattern !in slot.patterns) continue
            if (e.isCardioOrMobility) continue
            if (e.id in ctx.normalized.excludedExerciseIds) continue
            val cand = candidateOf(e, ctx, cache) ?: continue
            out.add(cand)
        }
        return out
    }

    fun candidateOf(e: Exercise, ctx: SelectionContext, cache: HashMap<String, Candidate?>): Candidate? =
        cache.getOrPut(e.id) {
            val a = resolver.resolve(e, ctx.equipment)
            if (!a.available || a.optionIndex == null) null
            else {
                val noRack = e.flags.requiresSpotterOrSafeties && !a.safetiesOrSpotter
                Candidate(e, a.optionIndex, a.effectiveLoadType ?: e.loadType, noRack)
            }
        }

    /** Score terms of one candidate (§4.6). `coverageRaw` is normalised by the caller with the slot's maximum. */
    data class Scored(val cand: Candidate, val total: Double, val terms: Map<String, Double>)

    private fun pickFor(
        ctx: SelectionContext, si: Int, st: ScheduledTemplate, ii: Int, slot: SlotTemplate, ledger: DoubleArray,
        sessionPicks: List<SlotPick>, chosen: List<Pair<Int, String>>, cache: HashMap<String, Candidate?>, prePass: Boolean,
    ): SlotPick? {
        var cands = candidates(slot, ctx, cache)
        var priorityMuscle: Muscle? = null
        if (isPrioritySlot(slot)) {
            val taken = sessionPicks.filter { isPrioritySlot(it.slot) }.mapNotNull { p -> PRIORITY_PATTERNS.entries.firstOrNull { p.exercise.pattern in it.value }?.key }.toSet()
            val ranked = Muscle.entries
                .filter { (ctx.tiers[it] ?: MuscleTier.TIER_3) != MuscleTier.TIER_3 && it !in taken }
                .map { m -> m to deficitOf(m, ctx, ledger) }
                .sortedWith(compareByDescending<Pair<Muscle, Double>> { it.second }.thenBy { it.first.ordinal })
            for ((m, _) in ranked) {
                val pats = PRIORITY_PATTERNS[m] ?: continue
                val sub = cands.filter { it.exercise.pattern in pats && isPriorityExercise(m, it.exercise) }
                if (sub.isNotEmpty()) {
                    cands = sub
                    priorityMuscle = m
                    break
                }
            }
        }
        if (cands.isEmpty()) return null

        // STRENGTH heavy slots: the main lift of the slot's first pattern when available and not excluded (§4.5).
        if (ctx.goal == Goal.STRENGTH && slot.heavy) {
            val main = MAIN_LIFTS[slot.patterns.first()].orEmpty().firstNotNullOfOrNull { id -> cands.firstOrNull { it.exercise.id == id } }
            if (main != null) {
                return SlotPick(si, st.weekday, st.template.id, ii, slot, main.exercise, main.optionIndex, main.loadType,
                    Explanations.of(listOf(RuleIds.SEL, RuleIds.FRQ_02, RuleIds.VOL_05), "Main lift ${main.exercise.name} on the heavy day.",
                        mapOf("exercise_id" to main.exercise.id, "pre_pass" to prePass.toString()), Confidence.WEAK))
            }
            val barbell = cands.filter { it.loadType == LoadType.BARBELL && it.exercise.flags.compound && it.exercise.pattern == slot.patterns.first() }
            if (barbell.isNotEmpty()) cands = barbell
        }

        val scored = score(cands, slot, ctx, ledger, sessionPicks, chosen, si, priorityMuscle)
        val best = scored.first()
        val terms = best.terms
        val message = "Picked ${best.cand.exercise.name}: " + terms.entries.joinToString(", ") { "${it.key} ${fmt(it.value)}" } + " = ${fmt(best.total)}."
        val inputs = LinkedHashMap<String, String>()
        for ((k, v) in terms) inputs[k] = fmt(v)
        inputs["score"] = fmt(best.total)
        inputs["exercise_id"] = best.cand.exercise.id
        if (priorityMuscle != null) inputs["priority_muscle"] = priorityMuscle.name.lowercase()
        if (prePass) inputs["pre_pass"] = "true"
        val rules = ArrayList<String>()
        rules.add(RuleIds.SEL)
        rules.add(RuleIds.VOL_04)
        rules.add(RuleIds.VOL_06)
        if (best.cand.noRack) rules.add(RuleIds.R4_5_5)
        if (best.cand.exercise.id in ctx.normalized.penalizedExerciseIds) rules.add(RuleIds.INJ_00)
        return SlotPick(si, st.weekday, st.template.id, ii, slot, best.cand.exercise, best.cand.optionIndex, best.cand.loadType,
            Explanations.of(rules, message, inputs, Confidence.WEAK))
    }

    /** Full §4.6 score, sorted best first (ties: knowledge order). */
    fun score(
        cands: List<Candidate>, slot: SlotTemplate, ctx: SelectionContext, ledger: DoubleArray, sessionPicks: List<SlotPick>,
        chosen: List<Pair<Int, String>>, si: Int, priorityMuscle: Muscle?,
    ): List<Scored> {
        val s = c.sel
        val focus: Set<Muscle>? = when {
            priorityMuscle != null -> setOf(priorityMuscle)
            slot.targetMuscles.isNotEmpty() -> slot.targetMuscles.toSet()
            else -> null
        }
        val raw = cands.map { coverageRaw(it.exercise, ctx, ledger, focus) }
        val maxCoverage = raw.maxOrNull() ?: 0.0
        val out = ArrayList<Scored>()
        for ((i, cand) in cands.withIndex()) {
            val e = cand.exercise
            val terms = LinkedHashMap<String, Double>()
            val patternPref = if (priorityMuscle != null || e.pattern == slot.patterns.first()) 1.0 else s.selFamilyPatternScore
            terms["pattern"] = s.selPatternPref * patternPref
            terms["coverage"] = if (maxCoverage > 0.0) s.selCoverage * raw[i] / maxCoverage else 0.0
            terms["continuity"] = s.selContinuity * continuity(e, ctx)
            terms["preference"] = s.selPreference * (if (e.id in ctx.profile.likedExerciseIds) 1.0 else 0.0)
            terms["loadType"] = s.selLoadTypePref * loadTypeFit(cand.loadType, slot, ctx.goal)
            terms["difficulty"] = s.selDifficultyFit * (if (SubstitutionEngine.difficultyLevel(e.difficulty) <= SubstitutionEngine.trainingLevel(ctx.trainingAge)) 1.0 else 0.5)
            if (sessionPicks.any { it.exercise.id == e.id }) terms["repeatPenalty"] = -s.selRepeatInSessionPenalty
            if ((slot.role == SlotRole.SECONDARY || slot.role == SlotRole.ACCESSORY) && chosen.any { it.first != si && it.second == e.id }) terms["varietyPenalty"] = -s.selVarietyPenalty
            if (e.id in ctx.normalized.penalizedExerciseIds) terms["limitationPenalty"] = -s.selMildLimitationPenalty
            if (cand.noRack) terms["noRackPenalty"] = -s.selNoRackPenalty
            out.add(Scored(cand, round4(terms.values.sum()), terms.mapValues { round4(it.value) }))
        }
        return out.sortedWith(compareByDescending<Scored> { it.total }.thenBy { order[it.cand.exercise.id] ?: Int.MAX_VALUE })
    }

    /**
     * Σ_m w(m) × deficit(m), deficit = max(0, target − ledgerSoFar) × tierWeight. A slot that names `target_muscles` (or a
     * priority slot's chosen muscle) counts only those muscles: the slot exists to cover them (§3.4 notation, §4.6 example
     * "the chest_fly|elbow_extension accessory slot picks a fly when chest is behind target and a triceps extension when triceps is").
     */
    fun coverageRaw(e: Exercise, ctx: SelectionContext, ledger: DoubleArray, focus: Set<Muscle>? = null): Double {
        var sum = 0.0
        for (mw in e.muscles) {
            if (focus != null && mw.muscle !in focus) continue
            sum += mw.weight * deficitOf(mw.muscle, ctx, ledger)
        }
        return sum
    }

    private fun deficitOf(m: Muscle, ctx: SelectionContext, ledger: DoubleArray): Double {
        val t = ctx.targets[m] ?: return 0.0
        return maxOf(0.0, t - ledger[m.ordinal]) * MuscleTiers.weight(ctx.tiers[m], c.tier.tierTier2Factor)
    }

    private fun continuity(e: Exercise, ctx: SelectionContext): Double {
        val states = ctx.states.filter { it.key.exerciseId == e.id }
        val recent = ctx.today.minusWeeks(CONTINUITY_WEEKS)
        if (states.any { (it.status == ProgressionStatus.PROGRESSING || it.status == ProgressionStatus.PLATEAU) && it.lastSessionDate != null && !it.lastSessionDate.isBefore(recent) }) return 1.0
        if (states.isNotEmpty() || ctx.profile.knownLoadsKg.containsKey(e.id)) return 0.5
        return 0.0
    }

    private fun loadTypeFit(lt: LoadType, slot: SlotTemplate, goal: Goal): Double {
        if (slot.preferLoadTypes.isNotEmpty()) return if (lt in slot.preferLoadTypes) 1.0 else 0.5
        val defaults: Set<LoadType> = when (goal) {
            Goal.STRENGTH -> setOf(LoadType.BARBELL)
            Goal.GENERAL_FITNESS, Goal.FAT_LOSS -> setOf(LoadType.MACHINE, LoadType.DUMBBELL)
            Goal.HYPERTROPHY, Goal.ENDURANCE_SUPPORT -> emptySet()
        }
        return if (lt in defaults) 1.0 else 0.5
    }

    private fun pinnedPick(ctx: SelectionContext, si: Int, st: ScheduledTemplate, ii: Int, slot: SlotTemplate, pinUsed: MutableSet<String>, cache: HashMap<String, Candidate?>): SlotPick? {
        val id = ctx.normalized.pinned[st.template.id] ?: ctx.normalized.pinned["*"] ?: return null
        if (id in pinUsed) return null
        val e = kb.exercise(id) ?: return null
        if (e.pattern !in slot.patterns || e.id in ctx.normalized.excludedExerciseIds) return null
        val cand = candidateOf(e, ctx, cache) ?: return null
        pinUsed.add(id)
        return SlotPick(si, st.weekday, st.template.id, ii, slot, e, cand.optionIndex, cand.loadType,
            Explanations.of(listOf(RuleIds.SEL, RuleIds.OVERRIDE), "${e.name} pinned by you.", mapOf("exercise_id" to e.id, "pinned" to "true"), Confidence.WEAK))
    }

    private fun applySwap(ctx: SelectionContext, pick: SlotPick, cache: HashMap<String, Candidate?>): SlotPick {
        val toId = ctx.normalized.permanentSwaps[pick.exercise.id] ?: return pick
        val to = kb.exercise(toId) ?: return pick
        if (to.id in ctx.normalized.excludedExerciseIds) return pick
        val cand = candidateOf(to, ctx, cache) ?: return pick
        return pick.copy(
            exercise = to, optionIndex = cand.optionIndex, loadType = cand.loadType,
            explanation = Explanations.of(listOf(RuleIds.SEL, RuleIds.OVERRIDE), "${to.name} instead of ${pick.exercise.name} (your swap).",
                mapOf("from" to pick.exercise.id, "exercise_id" to to.id), Confidence.WEAK),
        )
    }

    companion object {
        const val DEFAULT_SETS_LO: Int = 2
        const val CONTINUITY_WEEKS: Long = 8

        /** FULL_* "priority" isolation slots: empty target_muscles and the long priority pattern list of templates.json. */
        fun isPrioritySlot(slot: SlotTemplate): Boolean = slot.role == SlotRole.ISOLATION && slot.targetMuscles.isEmpty() && slot.patterns.size >= 3

        /** §4.6 priority mapping: muscle → isolation pattern(s). */
        val PRIORITY_PATTERNS: Map<Muscle, Set<MovementPattern>> = linkedMapOf(
            Muscle.SIDE_DELTS to setOf(MovementPattern.SHOULDER_ABDUCTION),
            Muscle.REAR_DELTS to setOf(MovementPattern.SHOULDER_ER_REAR_DELT),
            Muscle.BICEPS to setOf(MovementPattern.ELBOW_FLEXION),
            Muscle.TRICEPS to setOf(MovementPattern.ELBOW_EXTENSION),
            Muscle.HAMSTRINGS to setOf(MovementPattern.KNEE_FLEXION_ISOLATION),
            Muscle.QUADS to setOf(MovementPattern.KNEE_EXTENSION_ISOLATION),
            Muscle.CALVES to setOf(MovementPattern.CALF),
            Muscle.CHEST to setOf(MovementPattern.CHEST_FLY),
            Muscle.GLUTES to setOf(MovementPattern.HIP_EXTENSION_ISOLATION),
            Muscle.ABS to setOf(MovementPattern.CORE_ANTI_EXTENSION, MovementPattern.CORE_FLEXION),
            Muscle.LATS to setOf(MovementPattern.VERTICAL_PULL),
        )

        /** Lats in a priority slot: straight-arm (non-compound) vertical pulls only. */
        fun isPriorityExercise(m: Muscle, e: Exercise): Boolean = if (m == Muscle.LATS) !e.flags.compound else e.weightOf(m) >= 1.0

        /** §4.5 main lifts per heavy-slot pattern, in preference order. */
        val MAIN_LIFTS: Map<MovementPattern, List<String>> = mapOf(
            MovementPattern.SQUAT to listOf("back_squat", "safety_bar_squat"),
            MovementPattern.HINGE to listOf("conventional_deadlift", "trap_bar_deadlift"),
            MovementPattern.HORIZONTAL_PUSH to listOf("barbell_bench_press"),
            MovementPattern.VERTICAL_PUSH to listOf("overhead_press_barbell"),
        )

        fun round4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
        fun fmt(x: Double): String = String.format(Locale.ROOT, "%.3f", x)
    }
}
