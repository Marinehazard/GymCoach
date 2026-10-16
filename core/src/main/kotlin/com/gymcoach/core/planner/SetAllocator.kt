package com.gymcoach.core.planner

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.RpLandmarks
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.SlotTemplate
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.volume.MuscleTiers
import com.gymcoach.core.volume.VolumeAccountant

/** One slot under allocation (mutable while the week is filled). */
class AllocSlot(
    val sessionIndex: Int,
    val slotIndex: Int,
    val slot: SlotTemplate,
    var exercise: Exercise,
    var optionIndex: Int,
    var loadType: LoadType,
    var lo: Int,
    var hi: Int,
    var sets: Int = 0,
    var frozen: Boolean = false,
    var swapped: Boolean = false,
    var group: String? = null,
    val extraRules: LinkedHashSet<String> = LinkedHashSet(),
    val notes: MutableList<String> = ArrayList(),
    val fixed: Boolean = false,                       // §3.4.1 personal-template set count: never filled, rebalanced or trimmed
) {
    val required: Boolean get() = slot.required
    val heavy: Boolean get() = slot.heavy
}

class AllocSession(val index: Int, val weekday: Int, val templateId: String, val budget: Double, val slots: List<AllocSlot>)

/** REBAL alternatives for a slot: (exercise, optionIndex, loadType) whose pattern is in the slot's pattern list. */
data class RebalOption(val exercise: Exercise, val optionIndex: Int, val loadType: LoadType)

data class AllocationResult(val fractional: Map<Muscle, Double>, val direct: Map<Muscle, Int>, val explanations: List<Explanation>)

/**
 * Stage 4 (§4.6, A graft): week-level set allocation. Required slots start at the profile `sets.lo` (AGE-02 band for ≥ 60),
 * optional slots at 0; then the greedy fill adds one set at a time where Σ w × deficit / target × tierWeight is largest
 * (R4-4.5 soft cap for NOVICE/INTERMEDIATE), respecting the per-exercise band, the session minute budget, VOL-06 per session
 * per muscle (default stop 10, hard cap 12) and SESS-02. An optional slot opens with two sets at once (VOL-07). REBAL then
 * moves surplus sets of non-required ACCESSORY/ISOLATION/OPTIONAL slots to deficits (swapping inside the slot's pattern list,
 * invariant 15) or drops them, and the freed minutes are refilled.
 */
class SetAllocator(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val time = TimeModel(c)

    fun allocate(
        sessions: List<AllocSession>,
        targets: Map<Muscle, Double>,
        tiers: Map<Muscle, MuscleTier>,
        trainingAge: TrainingAge,
        rebalOptions: (AllocSlot) -> List<RebalOption>,
    ): AllocationResult {
        val notes = ArrayList<Explanation>()
        val sessCap = sessCap(trainingAge)
        for (s in sessions) {
            for (a in s.slots) if (a.required && a.sets == 0 && !a.frozen) a.sets = a.lo
            if (s.slots.isNotEmpty() && s.slots.all { it.fixed }) continue   // the user's own routine (§3.4.1)
            fitMinimum(s, notes)
            while (setsOf(s) > sessCap) if (!dropOneSet(s)) break
        }
        fill(sessions, targets, tiers, trainingAge, sessCap)
        rebalance(sessions, targets, tiers, trainingAge, sessCap, rebalOptions, notes)
        return AllocationResult(fractional(sessions), direct(sessions), notes)
    }

    fun sessCap(trainingAge: TrainingAge): Int = when (trainingAge) {
        TrainingAge.NOVICE -> c.sess.sess02CapNovice
        TrainingAge.INTERMEDIATE -> c.sess.sess02CapDefault
        TrainingAge.ADVANCED -> c.sess.sess02CapAdvanced
    }

    fun costOf(a: AllocSlot, concession: Int = 0): Double = time.costPerSet(a.exercise, a.loadType, a.heavy, concession)

    fun minutes(s: AllocSession): Double = time.minutesOf(s.slots.map { TimeModel.Item(costOf(it), it.sets, it.group) })

    private fun minutesWith(s: AllocSession, target: AllocSlot, add: Int): Double =
        time.minutesOf(s.slots.map { TimeModel.Item(costOf(it), if (it === target) it.sets + add else it.sets, it.group) })

    private fun setsOf(s: AllocSession): Int = s.slots.sumOf { it.sets }

    /** TIME-01 at plan level: when even the required lower bounds exceed the budget, trim to VOL-07's two sets, then drop low-priority required slots. */
    private fun fitMinimum(s: AllocSession, notes: MutableList<Explanation>) {
        if (minutes(s) <= s.budget + EPS) return
        val byPriority = s.slots.withIndex().sortedWith(compareBy<IndexedValue<AllocSlot>>({ rolePriority(it.value.slot.role) }, { -it.index })).map { it.value }
        for (a in byPriority) {
            if (a.fixed) continue
            while (a.sets > c.vol.vol07MinSetsPerAppearance && minutes(s) > s.budget + EPS) a.sets--
            if (minutes(s) <= s.budget + EPS) return
        }
        for (a in byPriority) {
            if (a.slot.role == SlotRole.PRIMARY || a.fixed) continue
            if (a.sets == 0) continue
            a.sets = 0
            a.frozen = true
            a.extraRules.add(RuleIds.TIME_01)
            a.notes.add("dropped: the ${s.budget.toInt()}-min lifting budget cannot hold every required slot")
            notes.add(Explanations.of(listOf(RuleIds.TIME_01, RuleIds.SESS_01), "${s.templateId}: ${a.slot.slotId} (${a.exercise.name}) dropped to fit the session length.",
                mapOf("template_id" to s.templateId, "slot_id" to a.slot.slotId, "budget_min" to TimeModel.round2(s.budget).toString()), Confidence.WEAK))
            if (minutes(s) <= s.budget + EPS) return
        }
    }

    private fun dropOneSet(s: AllocSession): Boolean {
        val a = s.slots.withIndex().filter { it.value.sets > c.vol.vol07MinSetsPerAppearance && !it.value.fixed }
            .sortedWith(compareBy<IndexedValue<AllocSlot>>({ rolePriority(it.value.slot.role) }, { -it.index })).firstOrNull()?.value ?: return false
        a.sets--
        return true
    }

    private fun fill(sessions: List<AllocSession>, targets: Map<Muscle, Double>, tiers: Map<Muscle, MuscleTier>, trainingAge: TrainingAge, sessCap: Int) {
        for (cap in listOf(c.vol.vol06PerSessionDefaultStop.toDouble(), c.vol.vol06PerSessionCap.toDouble())) {
            var guard = 0
            while (guard++ < MAX_ITERATIONS) {
                val ledger = fractionalArray(sessions)
                val directs = directArray(sessions)
                var best: AllocSlot? = null
                var bestAdd = 0
                var bestGain = 0.0
                var bestSoft = false
                for (s in sessions) {
                    val sFrac = sessionFractional(s)
                    val sSets = setsOf(s)
                    for (a in s.slots) {
                        if (a.frozen || a.sets >= a.hi) continue
                        if (a.sets == 0 && a.required) continue
                        val add = if (a.sets == 0) maxOf(2, c.vol.vol07MinSetsPerAppearance).coerceAtMost(a.hi) else 1
                        if (sSets + add > sessCap) continue
                        if (a.exercise.muscles.any { sFrac[it.muscle.ordinal] + it.weight * add > cap + EPS }) continue
                        if (minutesWith(s, a, add) > s.budget + EPS) continue
                        var gain = 0.0
                        val l = ledger.copyOf()
                        for (k in 0 until add) {
                            gain += gainOf(a.exercise, l, targets, tiers)
                            for (mw in a.exercise.muscles) l[mw.muscle.ordinal] += mw.weight
                        }
                        gain /= add
                        var soft = false
                        if (trainingAge != TrainingAge.ADVANCED && softCapped(a.exercise, ledger, directs, targets)) {
                            gain *= c.vol.r45SoftCapFactor
                            soft = true
                        }
                        if (gain > bestGain + EPS) {
                            best = a
                            bestAdd = add
                            bestGain = gain
                            bestSoft = soft
                        }
                    }
                }
                val b = best ?: break
                if (bestGain <= 0.0) break
                b.sets += bestAdd
                if (bestSoft) b.extraRules.add(RuleIds.R4_4_5)
            }
        }
    }

    /** gain per set = Σ_m w × max(0, target − ledger) / target × tierWeight. */
    fun gainOf(e: Exercise, ledger: DoubleArray, targets: Map<Muscle, Double>, tiers: Map<Muscle, MuscleTier>): Double {
        var g = 0.0
        for (mw in e.muscles) {
            val t = targets[mw.muscle] ?: continue
            if (t <= 0.0) continue
            val d = maxOf(0.0, t - ledger[mw.muscle.ordinal])
            g += mw.weight * d / t * MuscleTiers.weight(tiers[mw.muscle], c.tier.tierTier2Factor)
        }
        return g
    }

    /** R4-4.5 soft landmark: some weight-1.0 muscle has direct ≥ RP MRV.lo and ledger ≥ target. */
    private fun softCapped(e: Exercise, ledger: DoubleArray, directs: DoubleArray, targets: Map<Muscle, Double>): Boolean {
        for (mw in e.muscles) {
            if (mw.weight < 1.0) continue
            val mrv = RpLandmarks.mrvLo(kb, mw.muscle) ?: continue
            val t = targets[mw.muscle] ?: continue
            if (directs[mw.muscle.ordinal] >= mrv - EPS && ledger[mw.muscle.ordinal] >= t - EPS) return true
        }
        return false
    }

    private fun rebalance(
        sessions: List<AllocSession>, targets: Map<Muscle, Double>, tiers: Map<Muscle, MuscleTier>, trainingAge: TrainingAge, sessCap: Int,
        rebalOptions: (AllocSlot) -> List<RebalOption>, notes: MutableList<Explanation>,
    ) {
        val s = c.sel
        var guard = 0
        while (guard++ < MAX_REBAL_STEPS) {
            val ledger = fractionalArray(sessions)
            val deficits = Muscle.entries.filter { m ->
                val t = targets[m] ?: return@filter false
                val tier = tiers[m] ?: MuscleTier.TIER_3
                (tier == MuscleTier.TIER_1 && t - ledger[m.ordinal] >= s.rebalDeficitMin - EPS) ||
                    (tier == MuscleTier.TIER_2 && ledger[m.ordinal] < minOf(c.vol.vol01Minimum.toDouble(), t) - EPS)
            }.sortedWith(compareBy<Muscle>({ if ((tiers[it] ?: MuscleTier.TIER_3) == MuscleTier.TIER_1) 0 else 1 }, { -((targets[it] ?: 0.0) - ledger[it.ordinal]) }, { it.ordinal }))
            if (deficits.isEmpty()) return
            fun surplus(m: Muscle): Double = targets[m]?.let { ledger[m.ordinal] - it } ?: 0.0
            val movable = sessions.flatMap { it.slots }.filter { a ->
                !a.required && a.sets > 0 && (a.slot.role == SlotRole.ACCESSORY || a.slot.role == SlotRole.ISOLATION || a.slot.role == SlotRole.OPTIONAL) &&
                    a.exercise.muscles.any { it.weight >= 1.0 && surplus(it.muscle) >= s.rebalSurplusMin - EPS }
            }
            if (movable.isEmpty()) return
            var changed = false
            // 1. Swap: a slot-pattern alternative crediting the deficit muscle at 1.0, at ≤ the same time cost.
            loop@ for (d in deficits) {
                for (a in movable) {
                    if (a.swapped) continue
                    val surplusMuscles = a.exercise.muscles.filter { it.weight >= 1.0 }.map { it.muscle }
                    if (surplusMuscles.any { surplus(it) - a.sets < -c.sel.rebalTolerance - EPS }) continue
                    val cost = costOf(a)
                    val option = rebalOptions(a).firstOrNull { o ->
                        o.exercise.id != a.exercise.id && o.exercise.pattern in a.slot.patterns && o.exercise.weightOf(d) >= 1.0 &&
                            time.costPerSet(o.exercise, o.loadType, a.heavy) <= cost + EPS
                    } ?: continue
                    val from = a.exercise
                    a.exercise = option.exercise
                    a.optionIndex = option.optionIndex
                    a.loadType = option.loadType
                    a.swapped = true
                    a.frozen = true
                    a.extraRules.add(RuleIds.REBAL)
                    a.notes.add("REBAL: ${from.name} → ${option.exercise.name} (${muscleKey(d)} behind target)")
                    notes.add(Explanations.of(listOf(RuleIds.REBAL), "Moved ${a.sets} sets from ${from.name} to ${option.exercise.name}: ${muscleKey(d)} was behind its target.",
                        mapOf("from" to from.id, "to" to option.exercise.id, "muscle" to muscleKey(d), "slot_id" to a.slot.slotId), Confidence.WEAK))
                    changed = true
                    break@loop
                }
            }
            // 2. Otherwise drop a surplus set (the deficit stays in the under-target report).
            if (!changed) {
                val a = movable.filter { !it.swapped || it.sets > 0 }.firstOrNull { cand ->
                    val left = if (cand.sets - 1 < c.vol.vol07MinSetsPerAppearance) cand.sets else 1
                    cand.exercise.muscles.filter { it.weight >= 1.0 }.all { surplus(it.muscle) - left >= -c.sel.rebalTolerance - EPS } &&
                        cand.exercise.muscles.filter { it.weight >= 1.0 }.any { surplus(it.muscle) >= s.rebalSurplusMin - EPS }
                } ?: return
                val removed = if (a.sets - 1 < c.vol.vol07MinSetsPerAppearance) a.sets else 1
                a.sets -= removed
                a.frozen = true
                a.extraRules.add(RuleIds.REBAL)
                a.notes.add(if (a.sets == 0) "REBAL: slot closed (surplus)" else "REBAL: −$removed set (surplus)")
                notes.add(Explanations.of(listOf(RuleIds.REBAL), "${a.exercise.name}: ${if (a.sets == 0) "slot closed" else "−$removed set"} — surplus moved to muscles behind target.",
                    mapOf("exercise_id" to a.exercise.id, "slot_id" to a.slot.slotId, "removed" to removed.toString()), Confidence.WEAK))
                changed = true
            }
            if (changed) fill(sessions, targets, tiers, trainingAge, sessCap)
        }
    }

    fun fractionalArray(sessions: List<AllocSession>): DoubleArray {
        val out = DoubleArray(Muscle.entries.size)
        for (s in sessions) for (a in s.slots) if (a.sets > 0) for (mw in a.exercise.muscles) out[mw.muscle.ordinal] += mw.weight * a.sets
        return out
    }

    private fun directArray(sessions: List<AllocSession>): DoubleArray {
        val out = DoubleArray(Muscle.entries.size)
        for (s in sessions) for (a in s.slots) if (a.sets > 0) for (mw in a.exercise.muscles) if (mw.weight >= 1.0) out[mw.muscle.ordinal] += a.sets.toDouble()
        return out
    }

    fun sessionFractional(s: AllocSession): DoubleArray {
        val out = DoubleArray(Muscle.entries.size)
        for (a in s.slots) if (a.sets > 0) for (mw in a.exercise.muscles) out[mw.muscle.ordinal] += mw.weight * a.sets
        return out
    }

    private fun fractional(sessions: List<AllocSession>): Map<Muscle, Double> {
        val arr = fractionalArray(sessions)
        val out = LinkedHashMap<Muscle, Double>()
        for (m in Muscle.entries) if (arr[m.ordinal] > 0.0) out[m] = VolumeAccountant.round2(arr[m.ordinal])
        return out
    }

    private fun direct(sessions: List<AllocSession>): Map<Muscle, Int> {
        val arr = directArray(sessions)
        val out = LinkedHashMap<Muscle, Int>()
        for (m in Muscle.entries) if (arr[m.ordinal] > 0.0) out[m] = arr[m.ordinal].toInt()
        return out
    }

    companion object {
        const val EPS: Double = 1e-9
        private const val MAX_ITERATIONS: Int = 2000
        private const val MAX_REBAL_STEPS: Int = 40

        /** Lower = trimmed first (TIME-01 at plan level, SESS-02 trimming). */
        fun rolePriority(r: SlotRole): Int = when (r) {
            SlotRole.OPTIONAL -> 0
            SlotRole.ISOLATION -> 1
            SlotRole.CORE -> 2
            SlotRole.ACCESSORY -> 3
            SlotRole.SECONDARY -> 4
            SlotRole.PRIMARY -> 5
        }

        /** INJ-04: curl / pressdown patterns whose weekly sets are halved for an elbow/wrist limitation. */
        val ELBOW_WRIST_PATTERNS: Set<MovementPattern> = setOf(MovementPattern.ELBOW_FLEXION, MovementPattern.ELBOW_EXTENSION)

        fun muscleKey(m: Muscle): String = m.name.lowercase()
    }
}
