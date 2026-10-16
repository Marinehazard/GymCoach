package com.gymcoach.core.planner

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.Difficulty
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.planner.realize.SessionRealizer
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.volume.VolumeAccountant
import java.time.LocalDate

/**
 * §4.16 invariants 1–18. Each violation is returned as "INV-<n>: <detail>"; empty = valid. Invariants 5, 8 and 11 are
 * properties of realized sessions: [validateSession] checks one SessionPlan, and [validate] realizes the current week's
 * sessions on a neutral (Green, as-planned) day at the plan gym to check them for the whole plan.
 */
class PlanValidator(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val resolver = EquipmentResolver(kb)
    private val allocator = SetAllocator(kb, c)

    fun validate(m: Mesocycle, inputs: PlanInputs, n: Normalized, today: LocalDate, realize: Boolean = true): List<String> {
        val out = ArrayList<String>()
        val planGym = inputs.gyms.firstOrNull { it.id == m.gymId } ?: inputs.gyms.firstOrNull()
        val currentStart = Weeks.weekStart(today, inputs.weekStartsOnMonday)
        val current = m.weeks.firstOrNull { it.startDate == currentStart }
        val sessCap = allocator.sessCap(n.trainingAge)

        // 1. TIER_1/TIER_2 ≥ VOL-01 (or the muscle's own lower target), or reported with a VOL-01 entry (PLANNER card).
        if (current != null && current.mode != WeekMode.DELOAD) {
            val vol = weekVolume(current)
            for (mu in Muscle.entries) {
                val t = current.targets[mu] ?: continue
                if ((n.tiers[mu] ?: MuscleTier.TIER_3) == MuscleTier.TIER_3) continue
                val floor = minOf(c.vol.vol01Minimum.toDouble(), t)
                if ((vol[mu] ?: 0.0) >= floor - EPS) continue
                val reported = m.underTargetReport.any { it.inputs["muscle"] == mu.name.lowercase() && RuleIds.VOL_01 in it.ruleIds }
                if (!reported) out.add("INV-1: ${mu.name.lowercase()} ${fmt(vol[mu] ?: 0.0)} < VOL-01 floor ${fmt(floor)} without an under-target entry")
            }
        }
        for (w in m.weeks) {
            val gym = if (w.mode == WeekMode.TRAVEL_MAINTENANCE) travelGymOf(w, inputs) ?: planGym else planGym
            val equipment = gym?.let { SessionEquipment(it) }
            val minutes = if (w.mode == WeekMode.TRAVEL_MAINTENANCE) minOf(n.sessionMinutes, gym?.defaultSessionMinutes ?: n.sessionMinutes).coerceIn(c.trv.trv01Minutes.lo, c.trv.trv01Minutes.hi) else n.sessionMinutes
            for (s in w.sessions) {
                val template = kb.templates[s.templateId]
                // §3.4.1: a session whose every slot has a template-fixed set count is the user's own routine — its length, set
                // total (SESS-02) and per-muscle sets (VOL-06) are explained with planner notes (Planner.allocateWeek), not enforced.
                val ownRoutine = template != null && s.slots.isNotEmpty() && s.slots.all { p -> template.slots.firstOrNull { it.slotId == p.slotId }?.sets != null }
                val sessionBudget = if (w.mode == WeekMode.TRAVEL_MAINTENANCE) minutes else template?.minutes ?: minutes
                // 2. minutes; 3. SESS-02 and VOL-06.
                if (!ownRoutine && s.estimatedMinutes > sessionBudget + c.sess.sess01BudgetToleranceMin + EPS) out.add("INV-2: ${s.id} ${fmt(s.estimatedMinutes)} min > $sessionBudget")
                val sets = s.slots.sumOf { it.sets }
                if (!ownRoutine && sets > sessCap) out.add("INV-3: ${s.id} $sets sets > SESS-02 cap $sessCap")
                if (!ownRoutine) for ((mu, v) in s.volumeFractional) if (v > c.vol.vol06PerSessionCap + EPS) out.add("INV-3: ${s.id} ${mu.name.lowercase()} ${fmt(v)} > VOL-06 ${c.vol.vol06PerSessionCap}")
                for (slot in s.slots) {
                    val e = kb.exercise(slot.exerciseId)
                    if (e == null) { out.add("INV-4: ${s.id}/${slot.slotId} unknown exercise ${slot.exerciseId}"); continue }
                    // 4. availability and exclusions.
                    if (equipment != null && !resolver.resolve(e, equipment).available) out.add("INV-4: ${s.id}/${slot.slotId} ${e.id} unavailable at ${gym?.id}")
                    if (e.id in n.excludedExerciseIds) out.add("INV-4: ${s.id}/${slot.slotId} ${e.id} is excluded")
                    // 13. VOL-07 — per exercise per session appearance (a §3.4.1 top set + its back-off slot are one appearance).
                    val appearance = s.slots.filter { it.exerciseId == slot.exerciseId }.sumOf { it.sets }
                    if (slot.sets >= 1 && appearance < c.vol.vol07MinSetsPerAppearance) out.add("INV-13: ${s.id}/${slot.slotId} ${slot.sets} set(s) < VOL-07 ${c.vol.vol07MinSetsPerAppearance}")
                    // 14. difficulty gate.
                    if (n.beginnerOnly && e.difficulty != Difficulty.BEGINNER) out.add("INV-14: ${s.id}/${slot.slotId} ${e.id} is ${e.difficulty.name.lowercase()} in a beginner-only plan")
                    if (e.difficulty == Difficulty.ADVANCED && !inputs.profile.allowAdvancedExercises) out.add("INV-14: ${s.id}/${slot.slotId} ${e.id} is advanced")
                    // 15. pattern inside the template slot's list (synthetic AGE-02 slots and user overrides excepted).
                    val ts = template?.slots?.firstOrNull { it.slotId == slot.slotId }
                    if (ts == null) {
                        if (RuleIds.AGE_02 !in slot.explanation.ruleIds) out.add("INV-15: ${s.id}/${slot.slotId} is not a slot of ${s.templateId}")
                    } else if (e.pattern !in ts.patterns && RuleIds.OVERRIDE !in slot.explanation.ruleIds) {
                        out.add("INV-15: ${s.id}/${slot.slotId} ${e.id} (${e.pattern.name.lowercase()}) outside ${ts.patterns.joinToString("|") { it.name.lowercase() }}")
                    }
                    if (slot.pattern != e.pattern) out.add("INV-15: ${s.id}/${slot.slotId} pattern field ${slot.pattern.name.lowercase()} ≠ ${e.pattern.name.lowercase()}")
                    // 10. explanations.
                    checkExplanation(slot.explanation, "${s.id}/${slot.slotId}", out)
                }
                // 16. superset pairing.
                val groups = s.slots.mapNotNull { it.supersetGroup }.groupBy { it }
                for ((g, members) in groups) {
                    val slots = s.slots.filter { it.supersetGroup == g }
                    if (members.size != 2) { out.add("INV-16: ${s.id} group $g has ${members.size} slots"); continue }
                    val a = template?.slots?.firstOrNull { it.slotId == slots[0].slotId }
                    if (a?.supersetWith != slots[1].slotId) out.add("INV-16: ${s.id} group $g pairs ${slots[0].slotId}+${slots[1].slotId}, not linked by superset_with")
                }
            }
            // 6. MIX-02 pattern coverage (groups a required template slot can host and the gym can serve).
            if (w.mode != WeekMode.DELOAD && equipment != null) checkCoverage(w, equipment, n, out)
            // 9. CAR-06 cardio placement.
            checkCardio(w, out)
            // 7. SPLIT-02 (≤ 5 days).
            if (w.mode != WeekMode.TRAVEL_MAINTENANCE && w.sessions.size <= 5 && m.explanations.none { RuleIds.SPLIT_02 in it.ruleIds }) {
                val byDay = w.sessions.associateBy { it.weekday }
                for (s in w.sessions) {
                    val next = byDay[if (s.weekday == 7) 1 else s.weekday + 1] ?: continue
                    if (next === s) continue
                    val shared = heavyGroups(s).intersect(heavyGroups(next))
                    if (shared.isNotEmpty()) out.add("INV-7: heavy ${shared.joinToString("+")} on consecutive days ${s.weekday}/${next.weekday} without SPLIT-02 note")
                }
            }
            w.notes.forEach { checkExplanation(it, "week ${w.index} note", out) }
            w.cardio.forEach { checkExplanation(it.explanation, "week ${w.index} cardio", out) }
        }
        m.explanations.forEach { checkExplanation(it, "mesocycle", out) }
        m.underTargetReport.forEach { checkExplanation(it, "under-target", out) }

        // 12. JSON round trip.
        val json = CoreJson.stored.encodeToString(Mesocycle.serializer(), m)
        val again = CoreJson.stored.encodeToString(Mesocycle.serializer(), CoreJson.stored.decodeFromString(Mesocycle.serializer(), json))
        if (json != again) out.add("INV-12: Mesocycle JSON does not round-trip byte-identically")

        // 17. STRENGTH back-off structure / FRQ-02 note.
        if (m.goal == Goal.STRENGTH) checkStrength(m, planGym, out)

        // 18. unique PlannedSession ids.
        val ids = m.weeks.flatMap { w -> w.sessions.map { it.id } }
        val dup = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) out.add("INV-18: duplicate PlannedSession ids ${dup.joinToString(", ")}")

        // 5, 8, 11 on the realized current week.
        if (realize && current != null && planGym != null) {
            val realizer = SessionRealizer(kb, c)
            for (s in current.sessions) {
                val date = Weeks.dateFor(current.startDate, s.weekday)
                val gymId = if (current.mode == WeekMode.TRAVEL_MAINTENANCE) (travelGymOf(current, inputs) ?: planGym).id else planGym.id
                val plan = try {
                    realizer.realize(s, current, inputs, TodayContext(date = date, gymId = gymId), inputs.progressionStates, n)
                } catch (e: IllegalStateException) {
                    out.add("INV-5: ${s.id} cannot be realized: ${e.message}")
                    continue
                }
                val eq = inputs.gyms.firstOrNull { it.id == gymId }?.let { SessionEquipment(it) }
                out.addAll(validateSession(plan, n, current, eq))
            }
        }
        return out
    }

    /** Invariants 5, 8, 10 and 11 for one realized session. */
    fun validateSession(plan: SessionPlan, n: Normalized, week: MesoWeek?, equipment: SessionEquipment? = null): List<String> {
        val out = ArrayList<String>()
        val deload = week?.mode == WeekMode.DELOAD
        for (b in plan.blocks) {
            val e = kb.exercise(b.exerciseId) ?: continue
            checkExplanation(b.explanation, "${plan.id}/${b.blockId}", out)
            b.warnings.forEach { checkExplanation(it, "${plan.id}/${b.blockId} warning", out) }
            val noRack = e.flags.requiresSpotterOrSafeties && equipment != null && !resolver.resolve(e, equipment).safetiesOrSpotter
            for (s in b.sets) {
                if (s.kind == SetKind.RAMP) continue
                val rir = s.rir ?: continue
                // 5. RIR rules.
                if (e.flags.requiresSpotterOrSafeties && rir == 0) out.add("INV-5: ${b.blockId} RIR 0 on a spotter/safeties exercise")
                if (noRack && rir < c.rir.rir03SpWithoutRackFloor && !deload) out.add("INV-5: ${b.blockId} RIR $rir < ${c.rir.rir03SpWithoutRackFloor} without rack_or_spotter")
                if (rir > c.rir.rirCapMax) out.add("INV-5: ${b.blockId} RIR $rir > ${c.rir.rirCapMax}")
                if (deload && rir != c.rir.rir04DeloadRir) out.add("INV-5: ${b.blockId} deload RIR $rir ≠ ${c.rir.rir04DeloadRir}")
            }
            // 11. rep ranges.
            val prescription = kb.prescription(e, n.goal)
            val allowedLo = minOf(prescription.range.lo, c.age.age02CompoundReps.lo, c.prog.load03BackoffReps.lo, c.trv.trv01EnduranceReps.lo, ALL_REP_LO)
            val ext = c.prog.prog02RepExtension + c.sel.r426KeepAtMaxRepExtension
            val shifted = SessionRealizer.shiftedBand(kb, e, n.goal, prescription.range, 2)
            val allowedHi = maxOf(prescription.range.hi, shifted.hi, c.prog.load03BackoffReps.hi, c.trv.trv01EnduranceReps.hi, c.age.age02CompoundReps.hi) + ext
            for (s in b.sets) {
                if (s.kind == SetKind.RAMP) continue
                val r = s.reps ?: continue
                if (r.lo < allowedLo || r.hi > allowedHi) out.add("INV-11: ${b.blockId} reps ${r.lo}-${r.hi} outside ${allowedLo}-${allowedHi}")
            }
        }
        plan.modifiers.forEach { checkExplanation(it, "${plan.id} modifier", out) }
        // 8. RAMP sets never count.
        val expected = VolumeAccountant.ofBlocks(plan.blocks, kb)
        if (expected != plan.volume) out.add("INV-8: ${plan.id} volume snapshot differs from its counted (non-RAMP) sets")
        return out
    }

    private fun checkExplanation(e: Explanation, where: String, out: MutableList<String>) {
        if (e.ruleIds.isEmpty()) { out.add("INV-10: $where has no rule ids"); return }
        for (id in e.ruleIds) if (kb.rules.get(id) == null) out.add("INV-10: $where cites unknown rule $id")
    }

    private fun checkCoverage(w: MesoWeek, equipment: SessionEquipment, n: Normalized, out: MutableList<String>) {
        val present = w.sessions.flatMap { s -> s.slots.filter { it.sets > 0 }.map { it.pattern } }
        val dropped = w.notes.filter { RuleIds.TIME_01 in it.ruleIds }.mapNotNull { e -> e.inputs["template_id"]?.let { t -> e.inputs["slot_id"]?.let { "$t.$it" } } }.toSet()
        val hostable = w.sessions.flatMap { s -> kb.templates[s.templateId]?.slots?.filter { it.required && "${s.templateId}.${it.slotId}" !in dropped }?.flatMap { it.patterns } ?: emptyList() }.toSet()
        for ((name, group) in MIX02_GROUPS) {
            if (group.none { it in hostable }) continue
            val serviceable = kb.exerciseOrder.mapNotNull { kb.exercises[it] }.any { it.pattern in group && it.id !in n.excludedExerciseIds && resolver.resolve(it, equipment).available }
            if (!serviceable) continue
            val ok = if (name == "vertical_pull") present.any { it == MovementPattern.VERTICAL_PULL } || present.count { it == MovementPattern.HORIZONTAL_PULL } >= 2
            else present.any { it in group }
            if (!ok) out.add("INV-6: week ${w.index} lacks MIX-02 pattern $name")
        }
    }

    private fun checkCardio(w: MesoWeek, out: MutableList<String>) {
        val lowerDays = w.sessions.filter { s -> s.slots.any { it.pattern == MovementPattern.SQUAT || it.pattern == MovementPattern.HINGE || it.pattern == MovementPattern.LUNGE_SPLIT } }.map { it.weekday }.toSet()
        for (cp in w.cardio) {
            val nextDay = if (cp.weekday == 7) 1 else cp.weekday + 1
            val hiit = cp.kind == CardioKind.HIIT_4X4 || cp.kind == CardioKind.HIIT_10X1 || cp.kind == CardioKind.SIT
            if (hiit && nextDay in lowerDays) out.add("INV-9: HIIT on day ${cp.weekday} before a lower session")
            val running = kb.cardioModality(cp.exerciseId, true)?.isRunning == true && cp.kind != CardioKind.ZONE2 && cp.kind != CardioKind.WALK
            if (running && nextDay in lowerDays) out.add("INV-9: running on day ${cp.weekday} before a lower session")
        }
    }

    private fun checkStrength(m: Mesocycle, gym: GymSet?, out: MutableList<String>) {
        val hasVariant = m.split in STRENGTH_VARIANT_ROWS
        if (!hasVariant) {
            if (m.explanations.none { RuleIds.FRQ_02 in it.ruleIds }) out.add("INV-17: ${m.split.name.lowercase()} has no strength variant and no FRQ-02 note")
            return
        }
        val equipment = gym?.let { SessionEquipment(it) }
        for (w in m.weeks) {
            if (w.mode == WeekMode.TRAVEL_MAINTENANCE || w.mode == WeekMode.DELOAD) continue
            for (s in w.sessions) {
                val t = kb.templates[s.templateId] ?: continue
                for (ts in t.slots) {
                    if (!ts.heavy) continue
                    val pattern = ts.patterns.first()
                    if (pattern !in MAIN_LIFT_PATTERNS) continue
                    val heavy = s.slots.firstOrNull { it.slotId == ts.slotId } ?: continue
                    val ref = "${t.id}.${ts.slotId}"
                    val backoffs = w.sessions.flatMap { o -> (kb.templates[o.templateId]?.slots ?: emptyList()).filter { it.backoffOf == ref }.mapNotNull { b -> o.slots.firstOrNull { it.slotId == b.slotId } } }
                    val referenced = w.sessions.any { o -> kb.templates[o.templateId]?.slots?.any { it.backoffOf == ref } == true }
                    if (!referenced) continue
                    val available = equipment == null || kb.exercise(heavy.exerciseId)?.let { resolver.resolve(it, equipment).available } == true
                    if (!available) continue
                    if (backoffs.none { it.exerciseId == heavy.exerciseId }) out.add("INV-17: week ${w.index} ${ref} (${heavy.exerciseId}) has no back-off slot holding the same exercise")
                }
            }
        }
    }

    private fun heavyGroups(s: PlannedSession): Set<String> = s.slots.filter { it.heavy && it.sets > 0 }.mapNotNull { SplitSelector.groupOf(it.pattern) }.toSet()

    private fun weekVolume(w: MesoWeek): Map<Muscle, Double> {
        val arr = DoubleArray(Muscle.entries.size)
        for (s in w.sessions) for ((mu, v) in s.volumeFractional) arr[mu.ordinal] += v
        return Muscle.entries.associateWith { VolumeAccountant.round2(arr[it.ordinal]) }
    }

    private fun travelGymOf(w: MesoWeek, inputs: PlanInputs): GymSet? {
        val id = w.notes.firstOrNull { RuleIds.TRV_01 in it.ruleIds }?.inputs?.get("gym_id") ?: return null
        return inputs.gyms.firstOrNull { it.id == id }
    }

    companion object {
        const val EPS: Double = 1e-6
        const val ALL_REP_LO: Int = 1
        val STRENGTH_VARIANT_ROWS: Set<SplitKind> = setOf(SplitKind.UPPER_LOWER_4, SplitKind.UL_PPL_5, SplitKind.UL_6, SplitKind.UPPER_LOWER_FULL_3)
        val MAIN_LIFT_PATTERNS: Set<MovementPattern> = setOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.HORIZONTAL_PUSH, MovementPattern.VERTICAL_PUSH)
        val MIX02_GROUPS: List<Pair<String, Set<MovementPattern>>> = listOf(
            "squat_or_lunge" to setOf(MovementPattern.SQUAT, MovementPattern.LUNGE_SPLIT),
            "hinge" to setOf(MovementPattern.HINGE),
            "horizontal_push" to setOf(MovementPattern.HORIZONTAL_PUSH),
            "vertical_or_incline_push" to setOf(MovementPattern.VERTICAL_PUSH, MovementPattern.INCLINE_PUSH),
            "horizontal_pull" to setOf(MovementPattern.HORIZONTAL_PULL),
            "vertical_pull" to setOf(MovementPattern.VERTICAL_PULL),
        )

        fun fmt(x: Double): String = java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
