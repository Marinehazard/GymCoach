package com.gymcoach.core.planner

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.volume.VolumeAccountant

/**
 * §4.6 time model (SESS-01, REST-03, UNI-02, WU-01/05/06): minutes per working set including rest. RAMP sets are charged
 * by the warm-up minutes, never per set (WU-03).
 */
class TimeModel(private val c: PlannerConstants) {

    fun costPerSet(exercise: Exercise, effectiveLoadType: LoadType, slotHeavy: Boolean, concessionLevel: Int = 0): Double {
        val s = c.sess
        val barbell = effectiveLoadType == LoadType.BARBELL || effectiveLoadType == LoadType.SMITH
        val base = when {
            exercise.pattern in HEAVY_LOWER && barbell && exercise.flags.compound -> s.sess01HeavyLowerMin
            slotHeavy && barbell -> s.sess01HeavyUpperMin
            exercise.flags.compound -> s.sess01CompoundMin
            else -> if (concessionLevel >= 4) s.sess01IsolationRestCutMin else s.sess01IsolationMin
        }
        return if (exercise.unilateral) base * s.sess01UnilateralFactor else base
    }

    /** One (cost, sets) entry per block; blocks sharing a supersetGroup are priced as pairs (REST-03). */
    data class Item(val cost: Double, val sets: Int, val group: String?)

    fun minutesOf(items: List<Item>): Double {
        var total = 0.0
        val seen = HashSet<String>()
        for (item in items) {
            val g = item.group
            if (g == null) {
                total += item.cost * item.sets
                continue
            }
            if (!seen.add(g)) continue
            val partners = items.withIndex().filter { it.value.group == g }.map { it.value }
            if (partners.size < 2) {
                total += item.cost * item.sets
                continue
            }
            val a = partners[0]
            val b = partners[1]
            total += pairMinutes(a.cost, a.sets, b.cost, b.sets)
            for (extra in partners.drop(2)) total += extra.cost * extra.sets
        }
        return total
    }

    /** Superset pair: per pair of sets max(sess01SupersetPairMin, sess01SupersetFactor × (cost_a + cost_b)); the longer partner's leftover sets at their own price. */
    fun pairMinutes(costA: Double, setsA: Int, costB: Double, setsB: Int): Double {
        val pairs = minOf(setsA, setsB)
        val pairCost = maxOf(c.sess.sess01SupersetPairMin, c.sess.sess01SupersetFactor * (costA + costB))
        var total = pairs * pairCost
        if (setsA > pairs) total += (setsA - pairs) * costA
        if (setsB > pairs) total += (setsB - pairs) * costB
        return total
    }

    /** Whole session incl. superset pairing (REST-03), warm-up and finisher. Only counted (non-RAMP) sets are charged. */
    fun sessionMinutes(blocks: List<ExerciseBlock>, kb: KnowledgeBase, warmupMinutes: Int, finisherMinutes: Int, concessionLevel: Int = 0): Double {
        val items = blocks.map { b ->
            val e = kb.requireExercise(b.exerciseId)
            val heavy = b.explanation.inputs[HEAVY_KEY] == "true"
            Item(costPerSet(e, b.effectiveLoadType, heavy, concessionLevel), b.sets.count { it.kind in VolumeAccountant.COUNTED_KINDS }, b.supersetGroup)
        }
        return round2(minutesOf(items) + warmupMinutes + finisherMinutes)
    }

    /** WU-01 10 min; WU-05 5 min when the session is ≤ 45 min; WU-06 +2 min. */
    fun warmupMinutes(sessionMinutes: Int, wu06: Boolean): Int {
        val base = if (sessionMinutes <= c.wu.wu05TrimAtOrBelowMinutes) c.wu.wu05TrimmedMinutes else c.wu.wu01Minutes
        return base + if (wu06) c.wu.wu06ExtraMinutes else 0
    }

    companion object {
        val HEAVY_LOWER: Set<MovementPattern> = setOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.LUNGE_SPLIT)
        /** ExerciseBlock carries no heavy flag; the realizer records the slot's `heavy` in the block explanation inputs under this key. */
        const val HEAVY_KEY: String = "heavy"
        fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
    }
}
