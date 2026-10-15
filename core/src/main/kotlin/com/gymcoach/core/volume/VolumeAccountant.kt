package com.gymcoach.core.volume

import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.Side
import com.gymcoach.core.model.VolumeSnapshot
import kotlin.math.abs
import kotlin.math.floor

data class VolumeView(val direct: Int, val fractional: Double, val sessions: Int)

/**
 * §4.13 volume accounting (DEF-05 fractional credits 1.0 / 0.5 / 0.25; WU-03: only WORKING, AMRAP, CALIBRATION and DROP
 * sets count; UNI-02: LEFT + RIGHT rows of the same (sessionId, blockId, setIndex) are ONE set, an unpaired LEFT or RIGHT
 * row counts 0.5). `direct` sums weight-1.0 credits only (RP landmarks compare with it); `fractional` is what the
 * planner's bands use. Values are rounded to 2 decimals at the boundary (HR-18).
 */
object VolumeAccountant {

    val COUNTED_KINDS: Set<SetKind> = setOf(SetKind.WORKING, SetKind.AMRAP, SetKind.CALIBRATION, SetKind.DROP)

    /** Set units per (sessionId, blockId, setIndex) group after UNI-02 pairing; superseded rows are ignored. */
    data class SetUnit(val sessionId: String, val exerciseId: String, val units: Double)

    fun units(sets: List<SetLog>): List<SetUnit> {
        val groups = LinkedHashMap<String, MutableList<SetLog>>()
        for (s in sets) {
            if (s.supersededById != null) continue
            if (s.kind !in COUNTED_KINDS) continue
            val key = s.sessionId + "|" + (s.blockId ?: s.exerciseId) + "|" + s.exerciseId + "|" + s.setIndex
            groups.getOrPut(key) { ArrayList() }.add(s)
        }
        val out = ArrayList<SetUnit>()
        for ((_, rows) in groups) {
            val both = rows.count { it.side == Side.BOTH }
            val lefts = rows.count { it.side == Side.LEFT }
            val rights = rows.count { it.side == Side.RIGHT }
            val paired = minOf(lefts, rights)
            val unpaired = abs(lefts - rights)
            val units = both + paired + 0.5 * unpaired
            if (units > 0.0) out.add(SetUnit(rows[0].sessionId, rows[0].exerciseId, units))
        }
        return out
    }

    fun weekly(sets: List<SetLog>, kb: KnowledgeBase): Map<Muscle, VolumeView> {
        val fractional = DoubleArray(Muscle.entries.size)
        val direct = DoubleArray(Muscle.entries.size)
        val sessions = Array(Muscle.entries.size) { LinkedHashSet<String>() }
        for (u in units(sets)) {
            val e = kb.exercise(u.exerciseId) ?: continue
            for (mw in e.muscles) {
                val i = mw.muscle.ordinal
                fractional[i] += mw.weight * u.units
                if (mw.weight >= 1.0) direct[i] += u.units
                sessions[i].add(u.sessionId)
            }
        }
        val out = LinkedHashMap<Muscle, VolumeView>()
        for (m in Muscle.entries) {
            val i = m.ordinal
            if (fractional[i] <= 0.0 && sessions[i].isEmpty()) continue
            out[m] = VolumeView(floor(direct[i] + 1e-9).toInt(), round2(fractional[i]), sessions[i].size)
        }
        return out
    }

    fun ofSets(sets: List<SetLog>, kb: KnowledgeBase): VolumeSnapshot {
        val weekly = weekly(sets, kb)
        return VolumeSnapshot(
            fractional = ordered(weekly.mapValues { it.value.fractional }),
            direct = orderedInt(weekly.mapValues { it.value.direct }),
        )
    }

    /** Working (counted) sets of the realized plan; RAMP sets never count (WU-03, invariant 8). */
    fun ofPlan(plan: SessionPlan, kb: KnowledgeBase): VolumeSnapshot = ofBlocks(plan.blocks, kb)

    fun ofBlocks(blocks: List<ExerciseBlock>, kb: KnowledgeBase): VolumeSnapshot {
        val pairs = blocks.map { b -> b.exerciseId to b.sets.count { it.kind in COUNTED_KINDS }.toDouble() }
        return snapshotOf(pairs, kb)
    }

    fun ofPlanned(session: PlannedSession, kb: KnowledgeBase): VolumeSnapshot =
        snapshotOf(session.slots.map { it.exerciseId to it.sets.toDouble() }, kb)

    /** (exerciseId, sets) pairs → snapshot; shared by the planner (Stage 4) and the realizer (Stage 6). */
    fun snapshotOf(pairs: List<Pair<String, Double>>, kb: KnowledgeBase): VolumeSnapshot {
        val fractional = DoubleArray(Muscle.entries.size)
        val direct = DoubleArray(Muscle.entries.size)
        for ((id, n) in pairs) {
            if (n <= 0.0) continue
            val e = kb.exercise(id) ?: continue
            for (mw in e.muscles) {
                fractional[mw.muscle.ordinal] += mw.weight * n
                if (mw.weight >= 1.0) direct[mw.muscle.ordinal] += n
            }
        }
        val f = LinkedHashMap<Muscle, Double>()
        val d = LinkedHashMap<Muscle, Int>()
        for (m in Muscle.entries) {
            if (fractional[m.ordinal] > 0.0) f[m] = round2(fractional[m.ordinal])
            if (direct[m.ordinal] > 0.0) d[m] = floor(direct[m.ordinal] + 1e-9).toInt()
        }
        return VolumeSnapshot(f, d)
    }

    /**
     * Progress-screen aggregates. From the per-muscle view alone the "back" aggregate (lats ∪ upper_back, a set counted
     * once at its larger weight) cannot be reconstructed exactly; it is therefore the larger of the two rows per field
     * (exact whenever every back set credits lats and upper_back at the same weight, R4 §4.1). [aggregatesOfSets] is exact.
     */
    fun aggregates(weekly: Map<Muscle, VolumeView>): Map<String, VolumeView> {
        val out = LinkedHashMap<String, VolumeView>()
        val lats = weekly[Muscle.LATS]
        val ub = weekly[Muscle.UPPER_BACK]
        out[BACK] = VolumeView(
            direct = maxOf(lats?.direct ?: 0, ub?.direct ?: 0),
            fractional = round2(maxOf(lats?.fractional ?: 0.0, ub?.fractional ?: 0.0)),
            sessions = maxOf(lats?.sessions ?: 0, ub?.sessions ?: 0),
        )
        out[DELTS] = sum(weekly, listOf(Muscle.FRONT_DELTS, Muscle.SIDE_DELTS, Muscle.REAR_DELTS))
        out[ARMS] = sum(weekly, listOf(Muscle.BICEPS, Muscle.TRICEPS, Muscle.FOREARMS))
        out[LEGS] = sum(weekly, listOf(Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.CALVES, Muscle.ADDUCTORS, Muscle.GLUTE_MED))
        return out
    }

    /** Exact aggregates from the set rows: a set crediting both lats and upper_back counts once at the larger weight. */
    fun aggregatesOfSets(sets: List<SetLog>, kb: KnowledgeBase): Map<String, VolumeView> {
        val weekly = weekly(sets, kb)
        var frac = 0.0
        var dir = 0.0
        val sessions = LinkedHashSet<String>()
        for (u in units(sets)) {
            val e = kb.exercise(u.exerciseId) ?: continue
            val w = maxOf(e.weightOf(Muscle.LATS), e.weightOf(Muscle.UPPER_BACK))
            if (w <= 0.0) continue
            frac += w * u.units
            if (w >= 1.0) dir += u.units
            sessions.add(u.sessionId)
        }
        val out = LinkedHashMap(aggregates(weekly))
        out[BACK] = VolumeView(floor(dir + 1e-9).toInt(), round2(frac), sessions.size)
        return out
    }

    private fun sum(weekly: Map<Muscle, VolumeView>, muscles: List<Muscle>): VolumeView {
        var d = 0
        var f = 0.0
        var s = 0
        for (m in muscles) {
            val v = weekly[m] ?: continue
            d += v.direct
            f += v.fractional
            s = maxOf(s, v.sessions)
        }
        return VolumeView(d, round2(f), s)
    }

    const val BACK: String = "back"
    const val DELTS: String = "delts"
    const val ARMS: String = "arms"
    const val LEGS: String = "legs"

    fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    /** Copies a muscle map in enum order (byte-stable JSON, HR-18), dropping zero entries. */
    fun ordered(map: Map<Muscle, Double>): Map<Muscle, Double> {
        val out = LinkedHashMap<Muscle, Double>()
        for (m in Muscle.entries) {
            val v = map[m] ?: continue
            if (abs(v) < 1e-9) continue
            out[m] = round2(v)
        }
        return out
    }

    fun orderedInt(map: Map<Muscle, Int>): Map<Muscle, Int> {
        val out = LinkedHashMap<Muscle, Int>()
        for (m in Muscle.entries) {
            val v = map[m] ?: continue
            if (v == 0) continue
            out[m] = v
        }
        return out
    }
}
