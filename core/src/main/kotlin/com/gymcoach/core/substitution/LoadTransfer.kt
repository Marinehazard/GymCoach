package com.gymcoach.core.substitution

import com.gymcoach.core.equipment.IncrementResolver
import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.LoadTransferSeed
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.TrainingAge
import java.util.Locale

/**
 * Appendix C find-load seeds (TRV-04, weak — "starting guess, adjust freely"). Load basis follows LoadRounder: the pair
 * total for a bilateral dumbbell/kettlebell pair, per hand for a single implement / unilateral exercise. A seed whose note
 * says "per hand" is converted to the pair total when the target is held with two implements.
 */
class LoadTransfer(private val kb: KnowledgeBase) {
    private val increments = IncrementResolver(kb)
    private val file get() = kb.loadTransfer

    /** A known source load: an exercise with a working load (state or onboarding known load). */
    data class Source(val exercise: Exercise, val loadKg: Double, val origin: String)

    /** Appendix C: seed for `to` from a sibling's state (×factor, ×0.5 unilateral) else the body-weight fraction × sex/age factors; null = floor (§4.9.2). */
    fun seed(to: Exercise, effectiveLoadType: LoadType, states: List<ProgressionState>, profile: Profile, trainingAge: TrainingAge, bodyWeightKg: Double): Pair<Double, Explanation>? {
        if (!loadBearing(effectiveLoadType)) return null
        val sources = sources(states, profile).filter { it.exercise.id != to.id }
        // 1. Direct seeds in file order.
        for (seed in file.seeds) {
            if (seed.toExerciseId != to.id) continue
            val src = sources.firstOrNull { it.exercise.id == seed.fromExerciseId } ?: continue
            return direct(seed, src, to, effectiveLoadType)
        }
        // 2. Same pattern & load type (& laterality → 1.0; bilateral → unilateral → unilateral_factor).
        for (src in sources) {
            val g = generic(src, to, effectiveLoadType) ?: continue
            return g
        }
        // 3. Two-step: a direct seed from a source into a sibling of the same pattern.
        for (src in sources) {
            val chained = fromReplaced(src.exercise, src.loadKg, to, effectiveLoadType) ?: continue
            return chained
        }
        return bodyweight(to, effectiveLoadType, profile, trainingAge, bodyWeightKg)
    }

    /**
     * §4.12 "desired working load" from the REPLACED exercise's working load: a direct seed, the generic same-pattern rule, or a
     * direct seed into a same-pattern sibling (deadlift → RDL 0.70 → dumbbell RDL; × unilateral_factor for a unilateral target).
     */
    fun fromReplaced(from: Exercise, fromLoadKg: Double, to: Exercise, effectiveLoadType: LoadType): Pair<Double, Explanation>? {
        if (!loadBearing(effectiveLoadType)) return null
        val src = Source(from, fromLoadKg, "replaced")
        file.seeds.firstOrNull { it.fromExerciseId == from.id && it.toExerciseId == to.id }?.let { return direct(it, src, to, effectiveLoadType) }
        generic(src, to, effectiveLoadType)?.let { return it }
        // The closest sibling (highest muscle-vector cosine with `to`; file order on ties) carries the seed. A machine/sled sibling
        // never carries a free-weight target and vice versa: its factor is on the machine's own scale (back squat → leg press
        // × 2.0 "sled load" must not seed a Smith squat at 180 kg).
        val mids = file.seeds.filter { it.fromExerciseId == from.id }.mapNotNull { sd -> kb.exercise(sd.toExerciseId)?.let { sd to it } }
            .filter { (_, mid) -> mid.pattern == to.pattern && mid.id != to.id && machineScale(mid.loadType) == machineScale(effectiveLoadType) }
        val best = mids.withIndex().maxWithOrNull(compareBy<IndexedValue<Pair<LoadTransferSeed, Exercise>>>({ SubstitutionEngine.cosine(it.value.second, to) }, { -it.index }))?.value
        if (best != null) {
            val (seed, mid) = best
            var kg = fromLoadKg * seed.factor
            val uni = to.unilateral && !mid.unilateral
            if (uni) kg *= file.unilateralFactor
            return round3(kg) to Explanations.of(listOf(RuleIds.TRV_04),
                "Starting guess for ${to.name}: ${fmt(fromLoadKg)} kg ${from.name} × ${fmt(seed.factor)} (${mid.name})" + (if (uni) " × ${fmt(file.unilateralFactor)} (unilateral)" else "") + " — adjust freely.", mapOf("from" to from.id, "via" to mid.id, "factor" to fmt(seed.factor), "seed_kg" to fmt(kg)),
                Confidence.WEAK,
            )
        }
        return null
    }

    private fun machineScale(lt: LoadType): Boolean = lt == LoadType.MACHINE || lt == LoadType.CABLE || lt == LoadType.SLED

    /** R4 body-weight-fraction seed (male novice basis; female × 0.6, intermediate × 1.4, advanced × 1.7). */
    fun bodyweight(to: Exercise, effectiveLoadType: LoadType, profile: Profile, trainingAge: TrainingAge, bodyWeightKg: Double): Pair<Double, Explanation>? {
        if (bodyWeightKg <= 0.0) return null
        val lt = if (effectiveLoadType == LoadType.SLED) LoadType.MACHINE else effectiveLoadType
        val bw = file.bodyweightSeeds.firstOrNull { it.pattern == to.pattern && it.loadType == lt }
            ?: (if (lt == LoadType.KETTLEBELL) file.bodyweightSeeds.firstOrNull { it.pattern == to.pattern && it.loadType == LoadType.DUMBBELL } else null)
            ?: return null
        var factor = 1.0
        if (profile.sex == Sex.FEMALE) factor *= file.femaleFactor
        factor *= when (trainingAge) {
            TrainingAge.NOVICE -> 1.0
            TrainingAge.INTERMEDIATE -> file.intermediateFactor
            TrainingAge.ADVANCED -> file.advancedFactor
        }
        val perHand = bw.fraction * bodyWeightKg * factor
        val n = increments.implements(to, effectiveLoadType)
        val total = if (bw.perHand && n == 2) perHand * 2 else perHand
        return round3(total) to Explanations.of(listOf(RuleIds.TRV_04),
            "Starting guess for ${to.name}: ${fmt(bw.fraction)} × ${fmt(bodyWeightKg)} kg body weight" + (if (factor != 1.0) " × ${fmt(factor)}" else "") + (if (bw.perHand) " per hand" else "") + " — adjust freely.", mapOf("fraction" to fmt(bw.fraction), "body_weight_kg" to fmt(bodyWeightKg), "factor" to fmt(factor), "seed_kg" to fmt(total)),
            Confidence.WEAK,
        )
    }

    fun sources(states: List<ProgressionState>, profile: Profile): List<Source> {
        val out = ArrayList<Source>()
        val seen = HashSet<String>()
        for (s in states.sortedBy { it.key.id }) {
            val kg = s.workingLoadKg ?: continue
            val e = kb.exercise(s.key.exerciseId) ?: continue
            if (seen.add(e.id)) out.add(Source(e, kg, "state"))
        }
        for ((id, kg) in profile.knownLoadsKg.entries.sortedBy { it.key }) {
            val e = kb.exercise(id) ?: continue
            if (seen.add(e.id)) out.add(Source(e, kg, "known_load"))
        }
        return out
    }

    private fun direct(seed: LoadTransferSeed, src: Source, to: Exercise, lt: LoadType): Pair<Double, Explanation> {
        var kg = src.loadKg * seed.factor
        val perHandNote = seed.note?.lowercase(Locale.ROOT)?.contains("per hand") == true
        if (perHandNote && increments.implements(to, lt) == 2) kg *= 2.0
        return round3(kg) to Explanations.of(listOf(RuleIds.TRV_04),
            "Starting guess for ${to.name}: ${fmt(src.loadKg)} kg ${src.exercise.name} × ${fmt(seed.factor)}" + (if (perHandNote) " per hand" else "") + " — adjust freely.", mapOf("from" to src.exercise.id, "factor" to fmt(seed.factor), "seed_kg" to fmt(kg)),
            Confidence.WEAK,
        )
    }

    private fun generic(src: Source, to: Exercise, lt: LoadType): Pair<Double, Explanation>? {
        if (src.exercise.pattern != to.pattern) return null
        if (src.exercise.loadType != lt && src.exercise.loadType != to.loadType) return null
        val factor = when {
            src.exercise.unilateral == to.unilateral -> 1.0
            !src.exercise.unilateral && to.unilateral -> file.unilateralFactor
            else -> return null
        }
        val kg = src.loadKg * factor
        return round3(kg) to Explanations.of(listOf(RuleIds.TRV_04),
            "Starting guess for ${to.name}: same pattern and load type as ${src.exercise.name} (${fmt(src.loadKg)} kg × ${fmt(factor)}) — adjust freely.", mapOf("from" to src.exercise.id, "factor" to fmt(factor), "seed_kg" to fmt(kg)),
            Confidence.WEAK,
        )
    }

    companion object {
        /** Load types whose prescription is an external load in kg (bodyweight/band/cardio progress by reps, levels or time). */
        fun loadBearing(lt: LoadType): Boolean = when (lt) {
            LoadType.BARBELL, LoadType.DUMBBELL, LoadType.MACHINE, LoadType.CABLE, LoadType.KETTLEBELL, LoadType.SMITH, LoadType.SLED, LoadType.MEDICINE_BALL -> true
            LoadType.BODYWEIGHT, LoadType.BAND, LoadType.CARDIO, LoadType.NONE -> false
        }

        fun round3(x: Double): Double = Math.round(x * 1000.0) / 1000.0
        fun fmt(x: Double): String = java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
