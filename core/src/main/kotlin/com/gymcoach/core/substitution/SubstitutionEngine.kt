package com.gymcoach.core.substitution

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.equipment.LoadRounder
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Difficulty
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.volume.VolumeAccountant
import kotlin.math.sqrt

/** One ranked alternative for the swap sheet (§8.8) and the realizer (§4.12). `terms` keys: "pattern", "cosine", "loadFit", "continuity", "difficulty", "rankBonus", "profilePenalty", "limitationPenalty". */
data class SubstitutionCandidate(
    val exerciseId: String,
    val score: Double,
    val terms: Map<String, Double>,
    val available: Boolean,
    val optionIndex: Int?,
    val effectiveLoadType: LoadType?,
    val prescribedLoadKg: Double?,          // what the planner would prescribe (state, seed or achievable max)
    val ledgerDelta: Map<Muscle, Double>,   // fractional credit change for the week if this candidate replaces the block
    val rationale: String,                  // content.substitutionRationale[...] or the generated line
)

/**
 * §4.12 steps 1–3 (SUB, TRV-04, LOAD-01): the R4-ranked substitutions that are available and not excluded (+ rankBonus for the
 * first), united with the library search in the pattern family, scored by pattern, muscle-vector cosine, load fit, continuity
 * and difficulty, minus the profile and mild-limitation penalties. Sorted by score desc, then knowledge order. Unavailable
 * R4-ranked entries follow with `available = false` (the swap sheet shows them greyed out); the block's own exercise is never returned.
 */
class SubstitutionEngine(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val resolver = EquipmentResolver(kb)
    private val rounder = LoadRounder(kb)
    private val transfer = LoadTransfer(kb)

    fun rank(
        block: ExerciseBlock, equipment: SessionEquipment, params: GymParams, states: List<ProgressionState>, goal: Goal,
        trainingAge: TrainingAge, excluded: Set<String>, penalized: Set<String>, allowAdvanced: Boolean, profile: Profile,
    ): List<SubstitutionCandidate> {
        val ex = kb.requireExercise(block.exerciseId)
        val sets = block.sets.count { it.kind in VolumeAccountant.COUNTED_KINDS }.coerceAtLeast(1)
        val replacedLoad = replacedWorkingLoad(block, ex, states, profile)
        return rankFor(ex, replacedLoad, sets, equipment, params, states, trainingAge, excluded, penalized, allowAdvanced, profile, equipment.gym.id)
    }

    /** Core of [rank], also used by the realizer and the mutation validator without an ExerciseBlock. */
    fun rankFor(
        ex: Exercise, replacedLoadKg: Double?, sets: Int, equipment: SessionEquipment, params: GymParams, states: List<ProgressionState>,
        trainingAge: TrainingAge, excluded: Set<String>, penalized: Set<String>, allowAdvanced: Boolean, profile: Profile, gymId: String,
        bodyWeightKg: Double = 0.0,
    ): List<SubstitutionCandidate> {
        val order = kb.exerciseOrder.withIndex().associate { it.value to it.index }
        val rankedAvailable = ArrayList<Exercise>()
        val rankedUnavailable = ArrayList<Exercise>()
        for (id in ex.substitutions) {
            val cand = kb.exercise(id) ?: continue
            if (cand.id == ex.id || cand.id in excluded) continue
            if (resolver.resolve(cand, equipment).available) rankedAvailable.add(cand) else rankedUnavailable.add(cand)
        }
        val fam = family(ex.pattern)
        val library = kb.exerciseOrder.mapNotNull { kb.exercises[it] }.filter { cand ->
            cand.id != ex.id && cand.pattern in fam && cand.id !in excluded && !gated(cand, allowAdvanced) &&
                rankedAvailable.none { it.id == cand.id } && resolver.resolve(cand, equipment).available
        }
        val out = ArrayList<SubstitutionCandidate>()
        for ((i, cand) in (rankedAvailable + library).withIndex()) {
            out.add(score(ex, cand, i == 0 && rankedAvailable.isNotEmpty(), replacedLoadKg, sets, equipment, params, states, trainingAge, penalized, profile, gymId, true, bodyWeightKg))
        }
        val sorted = out.sortedWith(compareByDescending<SubstitutionCandidate> { it.score }.thenBy { order[it.exerciseId] ?: Int.MAX_VALUE })
        val unavailable = rankedUnavailable.map { cand -> score(ex, cand, false, replacedLoadKg, sets, equipment, params, states, trainingAge, penalized, profile, gymId, false, bodyWeightKg) }
        return sorted + unavailable
    }

    /** The working load of the replaced block: its state, else the onboarding known load, else its prescribed working load. */
    fun replacedWorkingLoad(block: ExerciseBlock, ex: Exercise, states: List<ProgressionState>, profile: Profile): Double? {
        states.firstOrNull { it.key == block.progressionKey }?.workingLoadKg?.let { return it }
        states.filter { it.key.exerciseId == ex.id }.sortedBy { it.key.id }.firstOrNull { it.workingLoadKg != null }?.workingLoadKg?.let { return it }
        profile.knownLoadsKg[ex.id]?.let { return it }
        return block.sets.firstOrNull { it.kind == SetKind.WORKING && it.loadKg != null }?.loadKg
    }

    private fun score(
        ex: Exercise, cand: Exercise, rankFirst: Boolean, replacedLoadKg: Double?, sets: Int, equipment: SessionEquipment, params: GymParams,
        states: List<ProgressionState>, trainingAge: TrainingAge, penalized: Set<String>, profile: Profile, gymId: String, available: Boolean,
        bodyWeightKg: Double,
    ): SubstitutionCandidate {
        val s = c.sel
        val patternScore = when {
            cand.pattern == ex.pattern -> 1.0
            cand.pattern in family(ex.pattern) -> s.selFamilyPatternScore
            else -> 0.0
        }
        val (optionIndex, lt) = if (available) chooseOption(cand, equipment, replacedLoadKg != null) else (null to null)
        val effLt = lt ?: cand.loadType
        val desired = desiredLoad(ex, cand, effLt, replacedLoadKg, states, profile, trainingAge, gymId, bodyWeightKg)
        val achievable = if (available) rounder.achievableMaxKg(cand, effLt, params) else null
        val ratio = if (desired != null && desired > 0.0 && achievable != null) achievable / desired else null
        val fit = loadFitOf(ratio)
        val continuity = continuity(cand, effLt, states, gymId)
        val difficulty = if (difficultyLevel(cand.difficulty) <= trainingLevel(trainingAge)) 1.0 else 0.5
        val profilePenalty = if (ex.flags.compound && !ex.isCardioOrMobility && cand.repProfile in PENALIZED_PROFILES) s.subProfilePenalty else 0.0
        val limitationPenalty = if (cand.id in penalized) s.selMildLimitationPenalty else 0.0
        val terms = LinkedHashMap<String, Double>()
        terms["pattern"] = r4(s.subPattern * patternScore)
        terms["cosine"] = r4(s.subCosine * cosine(ex, cand))
        terms["loadFit"] = r4(s.subLoadFit * fit)
        terms["continuity"] = r4(s.subContinuity * continuity)
        terms["difficulty"] = r4(s.subDifficulty * difficulty)
        terms["rankBonus"] = if (rankFirst) s.subRankBonus else 0.0
        terms["profilePenalty"] = -profilePenalty
        terms["limitationPenalty"] = -limitationPenalty
        val score = r4(terms.values.sum())
        val prescribed = when {
            !LoadTransfer.loadBearing(effLt) -> null
            desired == null && achievable == null -> null
            desired == null -> achievable
            achievable == null -> rounder.round(desired, cand, effLt, params).kg
            else -> rounder.round(minOf(desired, achievable), cand, effLt, params).kg
        }
        val delta = LinkedHashMap<Muscle, Double>()
        for (m in Muscle.entries) {
            val d = (cand.weightOf(m) - ex.weightOf(m)) * sets
            if (d != 0.0) delta[m] = VolumeAccountant.round2(d)
        }
        val rationale = ex.content?.substitutionRationale?.get(cand.id)
            ?: "${cand.name}: ${if (patternScore >= 1.0) "same pattern" else if (patternScore > 0.0) "same pattern family" else "different pattern"}, muscle overlap ${Math.round(cosine(ex, cand) * 100.0)} %" +
            (if (ratio != null) ", load fit ${String.format(java.util.Locale.ROOT, "%.2f", ratio)}" else "") + "."
        return SubstitutionCandidate(cand.id, score, terms, available, optionIndex, if (available) effLt else null, prescribed, delta, rationale)
    }

    /** loadFit tiers (§4.12): ≥ subLoadFitTier1 → 1.0; ≥ tier2 → 0.7; ≥ tier3 → 0.4 (endurance band); else 0.1. No load question → 1.0. */
    fun loadFitOf(ratio: Double?): Double {
        if (ratio == null) return 1.0
        val s = c.sel
        return when {
            ratio >= s.subLoadFitTier1 -> 1.0
            ratio >= s.subLoadFitTier2 -> 0.7
            ratio >= s.subLoadFitTier3 -> 0.4
            else -> 0.1
        }
    }

    /** The candidate's desired working load: its own state, else a seed from the replaced exercise, else a sibling seed / body-weight seed. */
    fun desiredLoad(ex: Exercise, cand: Exercise, lt: LoadType, replacedLoadKg: Double?, states: List<ProgressionState>, profile: Profile, trainingAge: TrainingAge, gymId: String, bodyWeightKg: Double = 0.0): Double? {
        if (!LoadTransfer.loadBearing(lt)) return null
        val key = ProgressionKey.forExercise(cand.id, lt, gymId)
        states.firstOrNull { it.key == key }?.workingLoadKg?.let { return it }
        profile.knownLoadsKg[cand.id]?.let { return it }
        if (replacedLoadKg != null) transfer.fromReplaced(ex, replacedLoadKg, cand, lt)?.let { return it.first }
        return transfer.seed(cand, lt, states, profile, trainingAge, bodyWeightKg)?.first
    }

    /**
     * The equipment option the candidate would use today: the first satisfied option, except that a load-bearing option is
     * preferred over a body-weight-only one when the replaced block carries a load (glute bridge with a dumbbell, single-leg
     * calf raise holding a dumbbell).
     */
    fun chooseOption(cand: Exercise, equipment: SessionEquipment, wantLoad: Boolean): Pair<Int?, LoadType?> {
        val satisfied = cand.equipmentOptions.withIndex().filter { (_, o) -> o.allOf.all { resolver.isItemSatisfied(it, equipment) } }
        if (satisfied.isEmpty()) return null to null
        if (wantLoad) {
            val loaded = satisfied.firstOrNull { LoadTransfer.loadBearing(it.value.loadType ?: cand.loadType) }
            if (loaded != null) return loaded.index to (loaded.value.loadType ?: cand.loadType)
        }
        val first = satisfied.first()
        return first.index to (first.value.loadType ?: cand.loadType)
    }

    private fun continuity(cand: Exercise, lt: LoadType, states: List<ProgressionState>, gymId: String): Double {
        val key = ProgressionKey.forExercise(cand.id, lt, gymId)
        val same = states.firstOrNull { it.key == key }
        if (same != null && (same.status == ProgressionStatus.PROGRESSING || same.status == ProgressionStatus.CALIBRATING)) return 1.0
        return if (states.any { it.key.exerciseId == cand.id }) 0.5 else 0.0
    }

    private fun gated(e: Exercise, allowAdvanced: Boolean): Boolean = e.difficulty == Difficulty.ADVANCED && !allowAdvanced

    companion object {
        val PENALIZED_PROFILES: Set<String> = setOf("power", "carry", "cardio")

        private val FAMILIES: List<Set<MovementPattern>> = listOf(
            setOf(MovementPattern.SQUAT, MovementPattern.LUNGE_SPLIT, MovementPattern.KNEE_EXTENSION_ISOLATION),
            setOf(MovementPattern.HINGE, MovementPattern.HIP_EXTENSION_ISOLATION, MovementPattern.KNEE_FLEXION_ISOLATION),
            setOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.INCLINE_PUSH, MovementPattern.CHEST_FLY),
            setOf(MovementPattern.VERTICAL_PUSH, MovementPattern.INCLINE_PUSH),
            setOf(MovementPattern.HORIZONTAL_PULL, MovementPattern.VERTICAL_PULL),
            setOf(MovementPattern.CORE_ANTI_EXTENSION, MovementPattern.CORE_ANTI_ROTATION, MovementPattern.CORE_FLEXION, MovementPattern.CORE_ROTATION, MovementPattern.CARRY),
        )

        /** §4.12 family map; INCLINE_PUSH belongs to both push families. Every other pattern is its own family. */
        fun family(p: MovementPattern): Set<MovementPattern> {
            val out = LinkedHashSet<MovementPattern>()
            out.add(p)
            for (f in FAMILIES) if (p in f) out.addAll(f)
            return out
        }

        fun sameFamily(a: MovementPattern, b: MovementPattern): Boolean = b in family(a)

        /** Cosine of the 21-dim fractional muscle-weight vectors. */
        fun cosine(a: Exercise, b: Exercise): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (m in Muscle.entries) {
                val x = a.weightOf(m)
                val y = b.weightOf(m)
                dot += x * y
                na += x * x
                nb += y * y
            }
            if (na <= 0.0 || nb <= 0.0) return 0.0
            return dot / (sqrt(na) * sqrt(nb))
        }

        fun difficultyLevel(d: Difficulty): Int = when (d) {
            Difficulty.BEGINNER -> 0
            Difficulty.INTERMEDIATE -> 1
            Difficulty.ADVANCED -> 2
        }

        fun trainingLevel(t: TrainingAge): Int = when (t) {
            TrainingAge.NOVICE -> 0
            TrainingAge.INTERMEDIATE -> 1
            TrainingAge.ADVANCED -> 2
        }

        fun r4(x: Double): Double = Math.round(x * 10000.0) / 10000.0
    }
}
