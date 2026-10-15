package com.gymcoach.core.planner.realize

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.TimeModel

/** One block while the realizer decides set counts (Stage 6, before per-set prescription). */
class WorkBlock(
    val slotId: String,
    val role: SlotRole,
    val heavy: Boolean,
    val required: Boolean,
    var exercise: Exercise,
    var optionIndex: Int,
    var loadType: LoadType,
    var sets: Int,
    val plannedSets: Int,
    var group: String?,
    val backoffOf: String? = null,
    val power: Boolean = false,
) {
    var substitutedFor: String? = null
    var droppedBy: String? = null
    var keepAtMax: Boolean = false
    var endurance: Boolean = false
    var replacedLoadKg: Double? = null
    val warnings: MutableList<Explanation> = ArrayList()
    val notes: MutableList<Explanation> = ArrayList()
    val active: Boolean get() = sets > 0
}

/**
 * §4.7 step 5 (TIME-01, TIME-L1..L7): when the estimate exceeds the budget, apply the concession levels in order until a
 * minimum viable session fits (≥ time01MinViableRequiredSets on every required compound slot; ≥ 1 on the two priority lifts),
 * then refill greedily. L1 is skipped and not logged when WU-05 already trimmed the warm-up (E2's log is [L2]).
 */
class TimeConcessions(private val c: PlannerConstants) {
    private val time = TimeModel(c)

    data class Result(val level: Int, val warmupMinutes: Int, val applied: List<Explanation>, val liftingMinutes: Double)

    fun minutes(blocks: List<WorkBlock>, level: Int): Double =
        time.minutesOf(blocks.filter { it.active }.map { TimeModel.Item(time.costPerSet(it.exercise, it.loadType, it.heavy, level), it.sets, it.group) })

    /**
     * @param sessionMinutes the day's total minutes; budget = sessionMinutes − warm-up − finisher.
     * @param weekCompoundCredit fractional credit per muscle from COMPOUND sets of the week's other sessions (L2 test).
     * @param priorityIds slot ids of the two priority lifts (never below 1 set).
     */
    fun apply(blocks: MutableList<WorkBlock>, sessionMinutes: Int, warmupMinutes: Int, finisherMinutes: Int, wu05Applied: Boolean, weekCompoundCredit: DoubleArray, priorityIds: Set<String>): Result {
        var warm = warmupMinutes
        var level = 0
        val applied = ArrayList<Explanation>()
        fun budget(): Double = (sessionMinutes - warm - finisherMinutes).toDouble()
        fun fits(): Boolean = minutes(blocks, level) <= budget() + EPS
        if (fits()) return Result(0, warm, applied, minutes(blocks, level))
        val estimate = minutes(blocks, level) + warm + finisherMinutes

        // L1 warm-up trim (WU-05) — skipped and unlogged when WU-05 already applies.
        if (!wu05Applied && warm > c.wu.wu05TrimmedMinutes) {
            val old = warm
            warm = c.wu.wu05TrimmedMinutes
            level = 1
            applied.add(log(RuleIds.TIME_L1, "Warm-up trimmed $old → $warm min.", "warmup_min" to warm.toString()))
        }
        // L2 drop isolation sets for muscles that already have ≥ VOL-01 from compounds this week.
        if (!fits()) {
            val dropped = ArrayList<String>()
            for (b in blocks.reversed()) {
                if (fits()) break
                if (!b.active || b.exercise.flags.compound || b.exercise.isCardioOrMobility) continue
                val primaries = b.exercise.muscles.filter { it.weight >= 1.0 }.map { it.muscle }
                if (primaries.isEmpty() || primaries.any { weekCompoundCredit[it.ordinal] < c.vol.vol01Minimum - EPS }) continue
                b.sets = 0
                b.droppedBy = RuleIds.TIME_L2
                dropped.add(b.exercise.id)
            }
            if (dropped.isNotEmpty()) {
                level = maxOf(level, 2)
                applied.add(logs(listOf(RuleIds.TIME_L2, RuleIds.MIX_01), "Isolation dropped (muscle already ≥ ${c.vol.vol01Minimum} sets from compounds this week): ${dropped.joinToString(", ")}.", "dropped" to dropped.joinToString(",")))
            }
        }
        // L3 antagonist / non-competing supersets for ISOLATION/ACCESSORY/CORE blocks.
        if (!fits()) {
            val loose = blocks.filter { it.active && it.group == null && (it.role == SlotRole.ISOLATION || it.role == SlotRole.ACCESSORY || it.role == SlotRole.CORE || it.role == SlotRole.OPTIONAL) }
            val paired = ArrayList<String>()
            var i = 0
            while (i + 1 < loose.size) {
                val a = loose[i]
                val b = loose[i + 1]
                if (competing(a.exercise, b.exercise)) { i++; continue }
                val g = "tc:" + a.slotId + "+" + b.slotId
                a.group = g
                b.group = g
                paired.add(g)
                i += 2
            }
            if (paired.isNotEmpty()) {
                level = maxOf(level, 3)
                applied.add(log(RuleIds.TIME_L3, "Non-competing accessories paired as supersets: ${paired.joinToString(", ")}.", "pairs" to paired.joinToString(",")))
            }
        }
        // L4 rest cut on machine/isolation work (REST-04).
        if (!fits()) {
            level = 4
            applied.add(log(RuleIds.TIME_L4, "Rest on machine/isolation sets cut to ${c.rest.rest04CutSec} s (main lifts keep ≥ ${c.rest.rest04MainLiftFloorSec} s).", "rest_sec" to c.rest.rest04CutSec.toString()))
        }
        // L5 one exercise per pattern.
        if (!fits()) {
            val seen = HashSet<String>()
            val dropped = ArrayList<String>()
            for (b in blocks) {
                if (!b.active) continue
                val key = b.exercise.pattern.name
                if (!seen.add(key) && b.role != SlotRole.PRIMARY) {
                    b.sets = 0
                    b.droppedBy = RuleIds.TIME_L5
                    dropped.add(b.exercise.id)
                }
            }
            if (dropped.isNotEmpty()) {
                level = maxOf(level, 5)
                applied.add(log(RuleIds.TIME_L5, "One exercise per pattern: ${dropped.joinToString(", ")} dropped.", "dropped" to dropped.joinToString(",")))
            }
        }
        // L6 two sets per exercise (never < 1 on the two priority lifts).
        if (!fits()) {
            var changed = false
            for (b in blocks) if (b.active && b.sets > TWO_SETS) { b.sets = TWO_SETS; changed = true }
            if (changed) {
                level = maxOf(level, 6)
                applied.add(log(RuleIds.TIME_L6, "Two sets per exercise today.", "sets" to TWO_SETS.toString()))
            }
        }
        // L7 move the lowest-priority slots to another session this week (ledger carryover).
        if (!fits()) {
            val moved = ArrayList<String>()
            val order = blocks.withIndex().filter { it.value.active && it.value.slotId !in priorityIds && !(it.value.required && it.value.exercise.flags.compound) }
                .sortedWith(compareBy<IndexedValue<WorkBlock>>({ rolePriority(it.value.role) }, { -it.index })).map { it.value }
            for (b in order) {
                if (fits()) break
                b.sets = 0
                b.droppedBy = RuleIds.TIME_L7
                moved.add(b.exercise.id)
            }
            if (moved.isNotEmpty()) {
                level = maxOf(level, 7)
                applied.add(log(RuleIds.TIME_L7, "Moved to another session this week (carryover): ${moved.joinToString(", ")}.", "moved" to moved.joinToString(",")))
            }
        }
        // Minimum viable session: required compounds keep ≥ time01MinViableRequiredSets, priority lifts ≥ time01MinViablePrioritySets.
        if (!fits()) {
            for (b in blocks.reversed()) {
                if (fits()) break
                if (!b.active) continue
                val floor = when {
                    b.slotId in priorityIds -> c.trv.time01MinViablePrioritySets
                    b.required && b.exercise.flags.compound -> c.trv.time01MinViableRequiredSets
                    else -> 0
                }
                while (b.sets > floor && !fits()) b.sets--
                if (b.sets == 0) b.droppedBy = RuleIds.TIME_01
            }
        }
        // Refill in block order while the budget allows (never above the planned sets; L2/L5/L7 drops stay dropped).
        var refilled = 0
        var progress = true
        while (progress) {
            progress = false
            for (b in blocks) {
                if (b.droppedBy != null && b.sets == 0) continue
                if (b.sets >= b.plannedSets) continue
                if (level >= 6 && b.sets >= TWO_SETS) continue
                b.sets++
                if (fits()) { refilled++; progress = true } else b.sets--
            }
        }
        applied.add(0, log(RuleIds.TIME_01, "Planned ${fmt(estimate)} min > ${sessionMinutes} min available: concessions applied.", "estimate_min" to fmt(estimate), "budget_min" to sessionMinutes.toString()))
        if (refilled > 0) applied.add(log(RuleIds.TIME_01, "Refilled $refilled set(s) into the freed minutes.", "refilled" to refilled.toString()))
        return Result(level, warm, applied, minutes(blocks, level))
    }

    /** Two blocks compete when they share a weight-1.0 muscle (REST-03: antagonist / non-competing pairs only). */
    fun competing(a: Exercise, b: Exercise): Boolean {
        val pa = a.muscles.filter { it.weight >= 1.0 }.map { it.muscle }.toSet()
        val pb = b.muscles.filter { it.weight >= 1.0 }.map { it.muscle }.toSet()
        return pa.intersect(pb).isNotEmpty()
    }

    /**
     * TIME-03: drop sets and rest-pause are allowed as a time saver only on machine, cable or isolation (single-joint) work —
     * never on barbell or other free-weight compounds, never on cardio/mobility. v1 does not prescribe them automatically
     * (the ladder above never uses them); the session screen or the coach may offer them on an eligible block and cite this.
     * Returns the explanation when [e] loaded as [loadType] is eligible, else null.
     */
    fun advancedTechniqueAllowed(e: Exercise, loadType: LoadType): Explanation? {
        if (e.isCardioOrMobility) return null
        val machineOrCable = loadType == LoadType.MACHINE || loadType == LoadType.CABLE
        if (!machineOrCable && e.flags.compound) return null
        return Explanations.of(
            listOf(RuleIds.TIME_03),
            "Short on time: a drop set or rest-pause set is fine on ${e.name} (machine, cable or isolation work).",
            mapOf("exercise_id" to e.id, "load_type" to loadType.name.lowercase()),
            Confidence.MODERATE,
        )
    }

    /** The ladder levels actually logged (rule ids TIME-L1..L7) of a result. */
    fun levelsOf(r: Result): List<String> = r.applied.flatMap { it.ruleIds }.filter { it.startsWith(LEVEL_PREFIX) }

    private fun log(rule: String, message: String, vararg inputs: Pair<String, String>): Explanation =
        Explanations.of(listOf(rule), message, inputs.toMap(), Confidence.WEAK)

    private fun logs(rules: List<String>, message: String, vararg inputs: Pair<String, String>): Explanation =
        Explanations.of(rules, message, inputs.toMap(), Confidence.WEAK)

    companion object {
        const val EPS: Double = 1e-6
        const val TWO_SETS: Int = 2
        const val LEVEL_PREFIX: String = "TIME-L"

        fun rolePriority(r: SlotRole): Int = when (r) {
            SlotRole.OPTIONAL -> 0
            SlotRole.ISOLATION -> 1
            SlotRole.CORE -> 2
            SlotRole.ACCESSORY -> 3
            SlotRole.SECONDARY -> 4
            SlotRole.PRIMARY -> 5
        }

        fun fmt(x: Double): String = java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
