package com.gymcoach.core.planner

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.equipment.LoadRounder
import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.CardioPlacement
import com.gymcoach.core.model.CardioPrescription
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MuscleTier
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOutput
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.PlannedSlot
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.SplitTemplate
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.model.WeekRollResult
import com.gymcoach.core.planner.realize.CardioFinisher
import com.gymcoach.core.planner.realize.SessionRealizer
import com.gymcoach.core.progression.E1rm
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.util.Hashing
import com.gymcoach.core.volume.MuscleTiers
import com.gymcoach.core.volume.TargetPolicy
import com.gymcoach.core.volume.VolumeAccountant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** Thrown when no split row yields a plan that passes PlanValidator (§4.16: a failing plan never ships). */
class PlanGenerationException(val violations: List<String>) : IllegalStateException("no valid plan: " + violations.joinToString("; "))

/** Planner facade (§4.2): Stages 0–5 (`plan`), Stage 6 (`realize`), the week rollover and validation. Pure and deterministic (HR-18). */
class Planner(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val normalizer = Normalizer(kb, c)
    private val builder = MesocycleBuilder(c)
    private val splits = SplitSelector(kb)
    private val selector = ExerciseSelector(kb, c)
    private val allocator = SetAllocator(kb, c)
    private val targetPolicy = TargetPolicy(c)
    private val time = TimeModel(c)
    private val agePolicy = AgePolicy(c)
    private val cardio = CardioFinisher(kb, c)
    private val validator = PlanValidator(kb, c)

    /** Stages 0–5. Pure; same inputs → same output. */
    fun plan(inputs: PlanInputs, today: LocalDate): PlanOutput {
        val n = normalize(inputs, today)
        val skel = builder.build(inputs, n, today)
        val hash = inputsHash(inputs)
        val mesoId = "meso-" + hash.take(16)
        val gym = planGym(inputs)
        val chain = splits.chain(n.daysPerWeek, n.goal, n.trainingAge)
        val failures = ArrayList<String>()
        val fallbackNotes = ArrayList<Explanation>()
        var firstValid: Pair<Built, List<String>>? = null
        for ((i, row) in chain.withIndex()) {
            val built = build(inputs, n, skel, row, gym, mesoId, hash, today, fallbackNotes)
            val hard = validator.validate(built.mesocycle, inputs, n, today)
            val soft = frq03(built)
            if (hard.isEmpty() && soft.isEmpty()) return PlanOutput(built.mesocycle, skel.currentWeekIndex, built.mesocycle.explanations, built.proposals)
            if (hard.isEmpty() && firstValid == null) firstValid = built to soft
            val violations = hard + soft
            failures.add("${row.kind.name.lowercase()}: " + violations.joinToString(" | "))
            val next = chain.getOrNull(i + 1) ?: break
            fallbackNotes.add(
                Explanations.of(
                    listOf(RuleIds.SPLIT_01, RuleIds.FRQ_03),
                    "${row.kind.name.lowercase()} (${row.daysPerWeek} days) did not validate; using ${next.kind.name.lowercase()} (${next.daysPerWeek} days)" +
                        (if (next.daysPerWeek < row.daysPerWeek) " — the dropped day becomes a rest/cardio day." else "."),
                    mapOf("from" to row.kind.name.lowercase(), "to" to next.kind.name.lowercase(), "reason" to violations.first()),
                    Confidence.WEAK,
                ),
            )
        }
        // FRQ-03 is a split-selection criterion: when no row satisfies it, the first row that passes every §4.16 invariant ships
        // with the FRQ-03 shortfall explained (the frequency the budget allows is the normal outcome, H23).
        val fallback = firstValid
        if (fallback != null) {
            val (built, soft) = fallback
            val note = Explanations.of(listOf(RuleIds.FRQ_03, RuleIds.SPLIT_01), "Not every large muscle can be trained twice a week with this schedule: " + soft.joinToString("; ") { it.substringAfter(": ") } + ".",
                mapOf("split" to built.split.kind.name.lowercase()), Confidence.WEAK)
            val meso = built.mesocycle.copy(explanations = built.mesocycle.explanations + note)
            return PlanOutput(meso, skel.currentWeekIndex, meso.explanations, built.proposals)
        }
        throw PlanGenerationException(failures)
    }

    /** Stage 6. Pure. `planned` may be null on a rest day (returns a mobility/cardio-only SessionPlan) or for a free session. */
    fun realize(planned: PlannedSession?, week: MesoWeek, inputs: PlanInputs, today: TodayContext, states: List<ProgressionState>): SessionPlan =
        SessionRealizer(kb, c).realize(planned, week, inputs, today, states, normalize(inputs, today.date))

    /** Week rollover: VOL-08 gating, travel mode and the PLAT-02 ladder ONLY (§4.11). Pure; deloadTriggers is always empty. */
    fun rollWeek(inputs: PlanInputs, weekStart: LocalDate): WeekRollResult {
        val n = normalize(inputs, weekStart)
        val skel = builder.build(inputs, n, weekStart)
        val week = skel.weekAt(weekStart)
        val nextMode = week?.mode ?: WeekMode.NORMAL
        val holds = holds(inputs, n, weekStart)
        val volumeHolds = targetPolicy.holdExplanations(holds, inputs.deloadTriggersActive, week?.index ?: skel.currentWeekIndex)
        val gym = planGym(inputs)
        val equipment = SessionEquipment(gym)
        val params = GymParams(kb, equipment)
        val rounder = LoadRounder(kb)
        val resolver = EquipmentResolver(kb)
        val states = ArrayList<ProgressionState>()
        val notes = ArrayList<Explanation>()
        val proposals = ArrayList<PlanMutation>()
        val stamp = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant()
        for (s in inputs.progressionStates.sortedBy { it.key.id }) {
            val ex = kb.exercise(s.key.exerciseId) ?: continue
            if (s.status != ProgressionStatus.PLATEAU) {
                if (s.plateauStep != 0 || s.repZoneShift != 0) states.add(s.copy(plateauStep = 0, repZoneShift = 0, updatedAt = stamp))
                continue
            }
            if (s.plateauStep >= MAX_PLATEAU_STEP) continue
            val step = s.plateauStep + 1
            var next = s.copy(plateauStep = step, updatedAt = stamp)
            val msg: String
            when (step) {
                1 -> msg = "${ex.name} plateaued: sleep, protein and stress questions added to the next check-in."
                2 -> {
                    val prescription = kb.prescription(ex, n.goal)
                    val shifted = SessionRealizer.shiftedBand(kb, ex, n.goal, prescription.range, 1)
                    val best = s.bestE1rm14d
                    var load = s.workingLoadKg
                    if (best != null) {
                        val mid = ((shifted.lo + shifted.hi) / 2.0).roundToInt()
                        val lt = resolver.resolve(ex, equipment).effectiveLoadType ?: ex.loadType
                        load = rounder.round(E1rm.loadFor(best, mid, s.targetRir), ex, lt, params).kg
                    }
                    next = next.copy(repZoneShift = 1, workingLoadKg = load)
                    msg = "${ex.name}: one rep zone lighter for two weeks (${shifted.lo}–${shifted.hi})" + (if (load != null) ", load re-seeded to ${fmt(load)} kg from e1RM." else ".")
                }
                3 -> {
                    // The first available substitutions[] entry (R4 rank order), not excluded.
                    val swap = ex.substitutions.mapNotNull { kb.exercise(it) }.firstOrNull { it.id !in n.excludedExerciseIds && resolver.resolve(it, equipment).available }
                    if (swap != null) proposals.add(PlanMutation.SwapExercise(ex.id, swap.id, null, MutationScope.REST_OF_MESOCYCLE,
                        "Plateau ladder step 3: variation swap ${ex.name} → ${swap.name} for the rest of the mesocycle."))
                    msg = "${ex.name}: variation swap proposed (plateau ladder step 3)."
                }
                4 -> {
                    next = next.copy(repZoneShift = 0)
                    msg = "${ex.name}: +1–2 weekly sets for its muscles while under the VOL-04 upper bound (plateau ladder step 4)."
                }
                else -> {
                    proposals.add(PlanMutation.ScheduleDeload(true, "Plateau ladder step 5: ${ex.name} has not progressed through the ladder — deload this week?"))
                    msg = "${ex.name}: reactive deload proposed (plateau ladder step 5)."
                }
            }
            states.add(next)
            notes.add(Explanations.of(if (step == 2) listOf(RuleIds.PLAT_02, RuleIds.E1RM_03) else listOf(RuleIds.PLAT_02), msg, mapOf("exercise_id" to ex.id, "step" to step.toString()), Confidence.WEAK))
        }
        return WeekRollResult(weekStart, nextMode, volumeHolds, emptyList(), proposals, states, notes)
    }

    /** Validation used by tests and before any plan is shown; empty = valid. */
    fun validate(mesocycle: Mesocycle, inputs: PlanInputs): List<Explanation> {
        val today = mesocycle.weeks.firstOrNull()?.startDate ?: mesocycle.startDate
        val n = normalize(inputs, today)
        return validator.validate(mesocycle, inputs, n, today).map { v ->
            Explanations.of(listOf(RuleIds.SEL), v, mapOf("invariant" to v.substringBefore(':')), Confidence.WEAK)
        }
    }

    /** Stage 0 only (§4.3). Pure. */
    fun normalize(inputs: PlanInputs, today: LocalDate): Normalized = normalizer.normalize(inputs, today)

    /** DEF-01 (H11) alone. Pure. */
    fun trainingAgeOf(profile: Profile, today: LocalDate): TrainingAge = normalizer.trainingAgeOf(profile, today)

    // ------------------------------------------------------------------------------------------------------------ build

    data class Built(val mesocycle: Mesocycle, val proposals: List<PlanMutation>, val currentAllocation: Map<Muscle, Double>, val split: SplitTemplate)

    private fun build(
        inputs: PlanInputs, n: Normalized, skel: Skeleton, row: SplitTemplate, gym: GymSet, mesoId: String, hash: String,
        today: LocalDate, fallbackNotes: List<Explanation>,
    ): Built {
        val choice = splits.choose(row, n.preferredWeekdays)
        val equipment = SessionEquipment(gym)
        val profile = inputs.profile
        val accustomed = if (n.goal == Goal.FAT_LOSS) accustomed(inputs, skel.anchor) else null
        val holds = holds(inputs, n, skel.currentWeekStart)
        val baseTargets = { k: Int, withHolds: Boolean ->
            val t = targetPolicy.weekTargets(n.goal, n.trainingAge, n.tiers, k, if (withHolds) holds.keys else emptySet(), accustomed, n.daysPerWeek, n.age,
                if (withHolds) inputs.deloadTriggersActive else emptyList())
            adjustTargets(t, n, inputs)
        }
        val selectionTargets = baseTargets(1, false)
        val ctx = SelectionContext(n, n.goal, n.trainingAge, equipment, selectionTargets, n.tiers, inputs.progressionStates, profile, today)
        val picks = selector.selectWeek(ctx, choice.schedule)

        // Cardio placement (one pattern for every non-travel week).
        val lifting = choice.schedule.map { it.weekday }
        val heavyLegs = choice.schedule.filter { st -> st.template.slots.any { it.heavy && SplitSelector.groupOf(it.patterns.first())?.startsWith("legs") == true } }.map { it.weekday }
        val upper = choice.schedule.filter { st -> st.template.slots.none { s -> s.patterns.any { SplitSelector.groupOf(it)?.startsWith("legs") == true } } }.map { it.weekday }
        val cardioWeek = cardio.weekly(n.goal, profile, n.age, lifting, heavyLegs, upper, n.activeOverrides, equipment, n.medicalGate)

        val allocCache = HashMap<Map<Muscle, Double>, List<PlannedSessionDraft>>()
        val weeks = ArrayList<MesoWeek>()
        var currentAlloc: Map<Muscle, Double> = emptyMap()
        var underTarget: List<Explanation> = emptyList()
        var proposals: List<PlanMutation> = emptyList()
        val lastAccumulation = skel.accumulationWeeks
        for (ws in skel.weeks) {
            val isCurrent = ws.startDate == skel.currentWeekStart
            val after = !ws.startDate.isBefore(skel.currentWeekStart)
            if (ws.travel) {
                val travel = travelWeek(inputs, n, ws, mesoId, profile)
                weeks.add(travel.week)
                if (isCurrent) {
                    currentAlloc = travel.allocation
                    underTarget = underTargetReport(travel.week.targets, travel.allocation, n.tiers)
                    proposals = proposalsFor(travel.week.targets, travel.allocation, n)
                }
                continue
            }
            val k = if (ws.mode == WeekMode.DELOAD) maxOf(1, minOf(lastAccumulation, ws.index - 1)) else ws.index
            val targets = baseTargets(k, after)
            val drafts = allocCache.getOrPut(targets) { allocateWeek(picks, choice.schedule, targets, n, profile, cardioWeek, equipment, gym) }
            val sessions = drafts.map { it.toPlanned(mesoId, ws.index) }
            val alloc = weekVolume(sessions)
            val notes = ArrayList<Explanation>()
            when (ws.mode) {
                WeekMode.DELOAD -> notes.add(Explanations.of(listOf(RuleIds.DLD_02), "Deload week: sets halved, compound loads × ${fmt(c.plat.dld02CompoundLoadFactor)}, RIR ${c.rir.rir04DeloadRir}.", mapOf("week" to ws.index.toString()), Confidence.MODERATE))
                WeekMode.REENTRY -> notes.add(Explanations.of(listOf(RuleIds.DLD_04), "Re-entry week: loads × ${fmt(c.plat.dld04LoadFactor)}, lower-bound sets.", mapOf("week" to ws.index.toString()), Confidence.WEAK))
                WeekMode.NORMAL, WeekMode.TRAVEL_MAINTENANCE -> Unit
            }
            if (ws.rirOffset != 0) notes.add(Explanations.of(listOf(RuleIds.RIR_04), if (ws.rirOffset > 0) "Week 1: target RIR +${ws.rirOffset}." else "Last accumulation week: closest to failure of the mesocycle.", mapOf("rir_offset" to ws.rirOffset.toString()), Confidence.MODERATE))
            drafts.flatMap { it.notes }.let { notes.addAll(it) }
            if (isCurrent) {
                notes.addAll(targetPolicy.holdExplanations(holds, inputs.deloadTriggersActive, ws.index))
                currentAlloc = alloc
                underTarget = underTargetReport(targets, alloc, n.tiers)
                proposals = proposalsFor(targets, alloc, n)
            }
            weeks.add(MesoWeek(ws.index, ws.startDate, ws.mode, ws.rirOffset, VolumeAccountant.ordered(targets), sessions, cardioWeek, notes))
        }

        val explanations = ArrayList<Explanation>()
        explanations.addAll(n.explanations)
        explanations.addAll(skel.explanations)
        explanations.addAll(fallbackNotes)
        explanations.addAll(choice.explanations)
        if (n.goal == Goal.STRENGTH && row.kind in NO_STRENGTH_VARIANT || row.kind == SplitKind.FULL_BODY_1 || row.kind == SplitKind.FULL_BODY_2) {
            explanations.add(Explanations.of(listOf(RuleIds.FRQ_02), "${row.kind.name.lowercase()} has no strength variant: one exposure per main lift per week.", mapOf("split" to row.kind.name.lowercase()), Confidence.MODERATE))
        }
        explanations.add(Explanations.of(listOf(RuleIds.SESS_01, RuleIds.SESS_02, RuleIds.VOL_06, RuleIds.VOL_07, RuleIds.DEF_05, RuleIds.VOL_03, RuleIds.TIME_02),
            "Sets are fitted into ${n.sessionMinutes}-minute sessions (≈ 1.75–4 min per set incl. rest), ≤ ${allocator.sessCap(n.trainingAge)} sets per session, ≤ ${c.vol.vol06PerSessionCap} sets per muscle per session and ≥ ${c.vol.vol07MinSetsPerAppearance} sets per exercise; a prime mover earns 1 set, a synergist 0.5.",
            mapOf("session_minutes" to n.sessionMinutes.toString(), "session_cap" to allocator.sessCap(n.trainingAge).toString()), Confidence.WEAK))
        weeks.firstOrNull { it.startDate == skel.currentWeekStart && it.mode != WeekMode.TRAVEL_MAINTENANCE }?.let { cw ->
            var compound = 0
            var all = 0
            for (s in cw.sessions) for (slot in s.slots) { all += slot.sets; if (kb.exercise(slot.exerciseId)?.flags?.compound == true) compound += slot.sets }
            val share = if (all > 0) Math.round(100.0 * compound / all).toInt() else 0
            explanations.add(Explanations.of(listOf(RuleIds.MIX_02, RuleIds.MIX_03), "Each week covers knee-dominant, hinge, horizontal and vertical/incline push and pull patterns; $share % of the sets are compounds (target ${c.vol.mix03CompoundShare.lo}–${c.vol.mix03CompoundShare.hi} %).",
                mapOf("compound_share_pct" to share.toString()), Confidence.MODERATE))
        }
        if (n.goal == Goal.FAT_LOSS) explanations.add(Explanations.of(listOf(RuleIds.VOL_09), "In a deficit, weekly sets stay at 60–100 % of your accustomed volume (never below ${c.vol.vol01Minimum}); trim sets before load.",
            mapOf("history" to (accustomed != null).toString()), Confidence.MODERATE))
        if (n.daysPerWeek <= 1) explanations.add(Explanations.of(listOf(RuleIds.FRQ_03, RuleIds.VOL_01), "One session per week: targets are the VOL-01 floor (4 sets) for every tier-1/2 muscle; no ramp.", mapOf("days" to "1"), Confidence.WEAK))
        if (n.elbowWristHalved) explanations.add(Explanations.of(listOf(RuleIds.INJ_04), "Curl and pressdown sets halved while the elbow/wrist limitation is active.", mapOf("pattern" to "elbow"), Confidence.WEAK))
        val meso = Mesocycle(
            id = mesoId, startDate = skel.anchor, goal = n.goal, trainingAge = n.trainingAge, split = row.kind, gymId = gym.id,
            weeks = weeks, deloadWeekIndex = skel.deloadWeekIndex, inputsHash = hash, underTargetReport = underTarget, explanations = explanations,
        )
        return Built(meso, proposals, currentAlloc, row)
    }

    /** ChangeVolume overrides and the PLAT-02 step-4 "+1 set" on top of the policy targets (never below VOL-01). */
    private fun adjustTargets(t: Map<Muscle, Double>, n: Normalized, inputs: PlanInputs): Map<Muscle, Double> {
        val out = LinkedHashMap(t)
        for (o in n.activeOverrides) {
            val m = o.mutation as? PlanMutation.ChangeVolume ?: continue
            if (m.scope == MutationScope.TODAY) continue
            val cur = out[m.muscle] ?: continue
            out[m.muscle] = maxOf(c.vol.vol01Minimum.toDouble(), cur + m.deltaSetsPerWeek)
        }
        for (s in inputs.progressionStates) {
            if (s.status != ProgressionStatus.PLATEAU || s.plateauStep != 4) continue
            val ex = kb.exercise(s.key.exerciseId) ?: continue
            for (mw in ex.muscles) {
                if (mw.weight < 1.0) continue
                val cur = out[mw.muscle] ?: continue
                val hi = targetPolicy.volBand(n.trainingAge).hi.toDouble()
                if (cur < hi) out[mw.muscle] = minOf(hi, cur + 1.0)
            }
        }
        return out
    }

    /** One session of the allocated week before ids are attached. */
    class PlannedSessionDraft(
        val weekday: Int, val templateId: String, val name: String, val slots: List<PlannedSlot>, val minutes: Double, val notes: List<Explanation>,
        private val kb: KnowledgeBase,
    ) {
        fun toPlanned(mesoId: String, week: Int): PlannedSession {
            val snap = VolumeAccountant.snapshotOf(slots.map { it.exerciseId to it.sets.toDouble() }, kb)
            return PlannedSession("$mesoId:w$week:d$weekday:$templateId", templateId, name, weekday, slots, minutes, snap.fractional, snap.direct)
        }
    }

    private fun allocateWeek(
        picks: List<List<SlotPick?>>, schedule: List<ScheduledTemplate>, targets: Map<Muscle, Double>, n: Normalized, profile: Profile,
        cardioWeek: List<CardioPrescription>, equipment: SessionEquipment, gym: GymSet,
        minutesOverride: Int? = null, travel: Boolean = false,
    ): List<PlannedSessionDraft> {
        val sessions = ArrayList<AllocSession>()
        var powerAdded = false
        val warmups = IntArray(schedule.size)
        val finishers = IntArray(schedule.size)
        for ((si, st) in schedule.withIndex()) {
            val minutes = minutesOverride ?: n.sessionMinutes
            val warm = time.warmupMinutes(minutes, n.age >= c.wu.wu06AgeThreshold)
            val fin = if (profile.cardioInsideSessionMinutes) cardioWeek.filter { it.weekday == st.weekday && it.placement == CardioPlacement.FINISHER }.sumOf { it.minutes + FINISHER_TRANSITION_MIN } else 0
            warmups[si] = warm
            finishers[si] = fin
            val slots = ArrayList<AllocSlot>()
            for ((ii, pick) in picks[si].withIndex()) {
                if (pick == null) continue
                val band = setsBand(pick, n, travel)
                slots.add(AllocSlot(si, ii, pick.slot, pick.exercise, pick.optionIndex, pick.loadType, band.lo, band.hi))
            }
            // AGE-02 (e): one POWER slot per week as the first accessory of the first lower session.
            if (n.over60 && !travel && !powerAdded && st.template.slots.any { t -> t.patterns.first() in LOWER_PATTERNS && t.role == com.gymcoach.core.model.SlotRole.PRIMARY }) {
                val power = powerExercise(n, equipment, profile)
                if (power != null) {
                    val (ex, cand) = power
                    val tpl = com.gymcoach.core.model.SlotTemplate(AGE_POWER_SLOT, com.gymcoach.core.model.SlotRole.ACCESSORY, listOf(ex.pattern), required = true)
                    val at = slots.indexOfFirst { it.slot.role != com.gymcoach.core.model.SlotRole.PRIMARY && it.slot.role != com.gymcoach.core.model.SlotRole.SECONDARY }.let { if (it < 0) slots.size else it }
                    val sets = maxOf(c.vol.vol07MinSetsPerAppearance, c.age.age02PowerSetsPerWeek.hi)
                    slots.add(at, AllocSlot(si, AGE_POWER_INDEX, tpl, ex, cand.optionIndex, cand.loadType, sets, sets))
                    powerAdded = true
                }
            }
            for (a in slots) {
                val partner = a.slot.supersetWith ?: continue
                if (slots.any { it.slot.slotId == partner }) a.group = listOf(a.slot.slotId, partner).sorted().joinToString("+")
            }
            sessions.add(AllocSession(si, st.weekday, st.template.id, (minutes - warm - fin).toDouble(), slots))
        }
        val ctx = SelectionContext(n, n.goal, n.trainingAge, equipment, targets, n.tiers, emptyList(), profile, LocalDate.MIN)
        val cache = HashMap<String, Candidate?>()
        val result = allocator.allocate(sessions, targets, n.tiers, n.trainingAge) { a ->
            selector.candidates(a.slot, ctx, cache).map { RebalOption(it.exercise, it.optionIndex, it.loadType) }
        }
        val out = ArrayList<PlannedSessionDraft>()
        for ((si, s) in sessions.withIndex()) {
            val st = schedule[si]
            val present = s.slots.filter { it.sets > 0 }
            val slots = present.map { a ->
                val pick = if (a.slotIndex >= 0) picks[si][a.slotIndex] else null
                val base = when {
                    a.slot.slotId == AGE_POWER_SLOT -> Explanations.of(listOf(RuleIds.AGE_02, RuleIds.SEL), "Power slot (age ≥ ${c.age.age02Threshold}): ${a.exercise.name}, fast concentric at 40–60 % of your max, RIR ≥ ${c.age.age02PowerMinRir}.",
                        mapOf("exercise_id" to a.exercise.id, "power" to "true"), Confidence.MODERATE)
                    pick != null && pick.exercise.id == a.exercise.id -> pick.explanation
                    else -> Explanations.of(listOf(RuleIds.SEL), "${a.exercise.name} selected.", mapOf("exercise_id" to a.exercise.id), Confidence.WEAK)
                }
                val rules = LinkedHashSet<String>()
                rules.addAll(base.ruleIds)
                rules.add(RuleIds.SEL)
                rules.add(RuleIds.VOL_04)
                rules.add(RuleIds.VOL_06)
                rules.addAll(a.extraRules)
                if (travel) rules.add(RuleIds.TRV_01)
                val inputs = LinkedHashMap(base.inputs)
                inputs["sets"] = a.sets.toString()
                inputs["sets_band"] = "${a.lo}-${a.hi}"
                val msg = base.message + (if (a.notes.isEmpty()) "" else " " + a.notes.joinToString("; ") + ".")
                val group = a.group?.takeIf { g -> present.count { it.group == g } == 2 }
                PlannedSlot(a.slot.slotId, a.slot.role, a.exercise.pattern, a.exercise.id, a.optionIndex, a.sets, a.heavy, group,
                    Explanations.of(rules.toList(), msg, inputs, base.confidence))
            }
            val minutes = TimeModel.round2(allocator.minutes(s) + warmups[si] + finishers[si])
            val notes = result.explanations.filter { it.inputs["template_id"] == st.template.id || (it.inputs["slot_id"] != null && it.inputs["template_id"] == null && s.slots.any { a -> a.slot.slotId == it.inputs["slot_id"] && a.exercise.id in listOf(it.inputs["to"], it.inputs["exercise_id"]) }) }
            out.add(PlannedSessionDraft(st.weekday, st.template.id, st.template.name, slots, minutes, notes, kb))
        }
        return out
    }

    /** AGE-02 power candidates: a `power`-profile exercise available at the gym and not excluded, knowledge order. */
    private fun powerExercise(n: Normalized, equipment: SessionEquipment, profile: Profile): Pair<com.gymcoach.core.model.Exercise, Candidate>? {
        val ctx = SelectionContext(n, n.goal, n.trainingAge, equipment, emptyMap(), n.tiers, emptyList(), profile, LocalDate.MIN)
        val cache = HashMap<String, Candidate?>()
        for (id in kb.exerciseOrder) {
            val e = kb.exercises[id] ?: continue
            if (e.repProfile != POWER_PROFILE || e.id in n.excludedExerciseIds) continue
            val cand = selector.candidateOf(e, ctx, cache) ?: continue
            return e to cand
        }
        return null
    }

    /** Per-exercise sets band: profile `sets` (AGE-02 band for ≥ 60; TRV-01 2–3 in travel weeks; INJ-04 halves curls/pressdowns). */
    private fun setsBand(pick: SlotPick, n: Normalized, travel: Boolean): Band {
        var band = kb.prescription(pick.exercise, n.goal).sets ?: Band(ExerciseSelector.DEFAULT_SETS_LO, 4)
        band = agePolicy.setsBand(n.age, band)
        if (travel) band = Band(c.trv.trv01Sets.lo, minOf(c.trv.trv01Sets.hi, maxOf(c.trv.trv01Sets.lo, band.hi)))
        if (n.elbowWristHalved && pick.exercise.pattern in SetAllocator.ELBOW_WRIST_PATTERNS) {
            val hi = maxOf(c.vol.vol07MinSetsPerAppearance, (band.hi + 1) / 2)
            band = Band(minOf(band.lo, hi), hi)
        }
        val lo = maxOf(band.lo, c.vol.vol07MinSetsPerAppearance)
        return Band(minOf(lo, maxOf(band.hi, lo)), maxOf(band.hi, lo))
    }

    private data class TravelBuilt(val week: MesoWeek, val allocation: Map<Muscle, Double>)

    /** §4.14 TRAVEL_MAINTENANCE week: travel_2 at the travel gym, TRV-01 minutes/sets, VOL-01 floors as targets. */
    private fun travelWeek(inputs: PlanInputs, n: Normalized, ws: WeekSkeleton, mesoId: String, profile: Profile): TravelBuilt {
        val row = checkNotNull(splits.travelRow()) { "templates.json lacks the travel_2 row" }
        val gym = travelGym(inputs, n, ws.startDate)
        val equipment = SessionEquipment(gym)
        val choice = splits.choose(row, emptyList())
        val targets = LinkedHashMap<Muscle, Double>()
        for ((m, tier) in n.tiers) {
            when (tier) {
                MuscleTier.TIER_1 -> targets[m] = c.vol.vol01Minimum.toDouble()
                MuscleTier.TIER_2 -> targets[m] = c.vol.vol01Minimum / 2.0
                MuscleTier.TIER_3 -> Unit
            }
        }
        val minutes = minOf(n.sessionMinutes, gym.defaultSessionMinutes ?: n.sessionMinutes).coerceIn(c.trv.trv01Minutes.lo, c.trv.trv01Minutes.hi)
        val ctx = SelectionContext(n, n.goal, n.trainingAge, equipment, targets, n.tiers, inputs.progressionStates, profile, ws.startDate, travel = true)
        val picks = selector.selectWeek(ctx, choice.schedule)
        val drafts = allocateWeek(picks, choice.schedule, targets, n, profile, emptyList(), equipment, gym, minutes, travel = true)
        val sessions = drafts.map { it.toPlanned(mesoId, ws.index) }
        val notes = listOf(
            Explanations.of(listOf(RuleIds.TRV_01, RuleIds.TRV_02), "Travel week at ${gym.name}: two full-body sessions of $minutes min, 2–3 sets each; the mesocycle week counter pauses.",
                mapOf("gym_id" to gym.id, "minutes" to minutes.toString(), "paused_week" to ws.index.toString()), Confidence.WEAK),
            Explanations.of(listOf(RuleIds.TRV_03), "About a third of your usual volume maintains muscle for a few weeks.", mapOf("week" to ws.index.toString()), Confidence.UNVERIFIED),
        )
        val week = MesoWeek(ws.index, ws.startDate, WeekMode.TRAVEL_MAINTENANCE, 0, VolumeAccountant.ordered(targets), sessions, emptyList(), notes)
        return TravelBuilt(week, weekVolume(sessions))
    }

    /** The gym of a travel week: an active SetTravelDays override's gym, else a hotel-preset gym, else the plan gym. */
    fun travelGym(inputs: PlanInputs, n: Normalized, weekStart: LocalDate): GymSet {
        val end = weekStart.plusDays(6)
        val byOverride = n.activeOverrides.mapNotNull { it.mutation as? PlanMutation.SetTravelDays }
            .lastOrNull { !it.to.isBefore(weekStart) && !it.from.isAfter(end) && it.gymId != null }?.gymId
        return inputs.gyms.firstOrNull { it.id == byOverride && !it.archived }
            ?: inputs.gyms.filter { !it.archived }.firstOrNull { it.presetOrigin == "hotel_gym" }
            ?: planGym(inputs)
    }

    fun planGym(inputs: PlanInputs): GymSet =
        inputs.gyms.firstOrNull { it.id == inputs.defaultGymId } ?: inputs.gyms.firstOrNull { it.isDefault } ?: checkNotNull(inputs.gyms.firstOrNull()) { "PlanInputs.gyms is empty" }

    private fun weekVolume(sessions: List<PlannedSession>): Map<Muscle, Double> {
        val arr = DoubleArray(Muscle.entries.size)
        for (s in sessions) for ((m, v) in s.volumeFractional) arr[m.ordinal] += v
        val out = LinkedHashMap<Muscle, Double>()
        for (m in Muscle.entries) if (arr[m.ordinal] > 0.0) out[m] = VolumeAccountant.round2(arr[m.ordinal])
        return out
    }

    /** "Under-target report is the normal outcome, not an alarm" (H23): quiet cards for TIER_1/TIER_2 deficits. */
    private fun underTargetReport(targets: Map<Muscle, Double>, alloc: Map<Muscle, Double>, tiers: Map<Muscle, MuscleTier>): List<Explanation> {
        val out = ArrayList<Explanation>()
        for (m in Muscle.entries) {
            val t = targets[m] ?: continue
            if ((tiers[m] ?: MuscleTier.TIER_3) == MuscleTier.TIER_3) continue
            val a = alloc[m] ?: 0.0
            val d = VolumeAccountant.round2(a - t)
            if (d >= -UNDER_TARGET_EPS) continue
            val floor = minOf(c.vol.vol01Minimum.toDouble(), t)
            val rules = if (a < floor - 1e-9) listOf(RuleIds.VOL_04, RuleIds.DEF_05, RuleIds.VOL_01) else listOf(RuleIds.VOL_04, RuleIds.DEF_05)
            out.add(Explanations.of(rules, "${TargetPolicy.muscleName(m)} ${fmt(d)} this week (${fmt(a)} of ${fmt(t)} sets).",
                mapOf("muscle" to TargetPolicy.muscleKey(m), "deficit" to fmt(d), "target" to fmt(t), "planned" to fmt(a)), Confidence.WEAK))
        }
        return out
    }

    /** A TIER_1/TIER_2 muscle projected below VOL-01 (or its own lower target) → one PLANNER card (§4.4). */
    private fun proposalsFor(targets: Map<Muscle, Double>, alloc: Map<Muscle, Double>, n: Normalized): List<PlanMutation> {
        val below = Muscle.entries.filter { m ->
            val t = targets[m] ?: return@filter false
            (n.tiers[m] ?: MuscleTier.TIER_3) != MuscleTier.TIER_3 && (alloc[m] ?: 0.0) < minOf(c.vol.vol01Minimum.toDouble(), t) - 1e-9
        }
        if (below.isEmpty()) return emptyList()
        val names = below.joinToString(", ") { TargetPolicy.muscleName(it) }
        return if (n.daysPerWeek < MAX_DAYS) listOf(PlanMutation.ChangeSchedule(daysPerWeek = n.daysPerWeek + 1, rationale = "$names below the VOL-01 minimum of ${c.vol.vol01Minimum} sets: +1 training day (or +10 min per session, a 20-min micro-session, or accept)."))
        else listOf(PlanMutation.ChangeSchedule(sessionMinutes = n.sessionMinutes + EXTRA_MINUTES, rationale = "$names below the VOL-01 minimum of ${c.vol.vol01Minimum} sets: +10 min per session (or a 20-min micro-session, or accept)."))
    }

    /** FRQ-03: every TIER_1 muscle with a target ≥ 10 appears in ≥ 2 sessions of the current week. */
    private fun frq03(b: Built): List<String> {
        val week = b.mesocycle.weeks.firstOrNull { it.mode != WeekMode.TRAVEL_MAINTENANCE } ?: return emptyList()
        if (week.sessions.size < 2) return emptyList()
        val out = ArrayList<String>()
        for ((m, t) in week.targets) {
            if (t < FRQ03_TARGET) continue
            val sessionsHitting = week.sessions.count { s -> (s.volumeFractional[m] ?: 0.0) >= 1.0 }
            if (sessionsHitting < 2) out.add("FRQ-03: ${m.name.lowercase()} (target ${fmt(t)}) trained in $sessionsHitting session(s)")
        }
        return out
    }

    /** VOL-08 holds for the week starting at [weekStart] (muscle → reason, enum order). */
    fun holds(inputs: PlanInputs, n: Normalized, weekStart: LocalDate): Map<Muscle, String> {
        val out = LinkedHashMap<Muscle, String>()
        val reasons = HashMap<Muscle, String>()
        inputs.weeklyCheckins.maxByOrNull { it.weekStart }?.holds?.forEach { reasons.putIfAbsent(it, "named in the weekly check-in") }
        // Reported vs prescribed RIR over last week's working sets.
        val dates = inputs.recentSessions.associate { it.id to it.date }
        val lastStart = weekStart.minusDays(7)
        val sumRep = DoubleArray(Muscle.entries.size)
        val sumPre = DoubleArray(Muscle.entries.size)
        val cnt = IntArray(Muscle.entries.size)
        for (s in inputs.recentSets) {
            if (s.supersededById != null || s.kind !in VolumeAccountant.COUNTED_KINDS) continue
            val d = dates[s.sessionId] ?: s.loggedAt.atZone(ZoneOffset.UTC).toLocalDate()
            if (d.isBefore(lastStart) || !d.isBefore(weekStart)) continue
            val rep = s.rir ?: continue
            val pre = s.prescribedRir ?: continue
            val e = kb.exercise(s.exerciseId) ?: continue
            for (mw in e.muscles) {
                if (mw.weight < 1.0) continue
                sumRep[mw.muscle.ordinal] += rep.toDouble()
                sumPre[mw.muscle.ordinal] += pre.toDouble()
                cnt[mw.muscle.ordinal]++
            }
        }
        for (m in Muscle.entries) {
            val k = cnt[m.ordinal]
            if (k == 0) continue
            if (sumRep[m.ordinal] / k < sumPre[m.ordinal] / k - c.vol.vol08RirTolerance) reasons.putIfAbsent(m, "reported RIR below the prescribed RIR last week")
        }
        for (r in inputs.readinessHistory) {
            if (r.date.isBefore(lastStart) || !r.date.isBefore(weekStart)) continue
            val region = r.sorenessRegion ?: continue
            if ((r.soreness ?: 0) < c.vol.vol08SorenessGate) continue
            for (m in groupOf(region)) reasons.putIfAbsent(m, "soreness ${r.soreness}/5")
        }
        for (s in inputs.progressionStates) {
            if (s.status != ProgressionStatus.REGRESSING) continue
            val e = kb.exercise(s.key.exerciseId) ?: continue
            for (mw in e.muscles) if (mw.weight >= 1.0) reasons.putIfAbsent(mw.muscle, "${e.name} regressing (PLAT-03)")
        }
        for (m in Muscle.entries) {
            val r = reasons[m] ?: continue
            if ((n.tiers[m] ?: MuscleTier.TIER_3) == MuscleTier.TIER_3) continue
            out[m] = r
        }
        return out
    }

    /** VOL-09 accustomed volume: mean weekly fractional sets per muscle over the logged weeks before the current mesocycle. */
    private fun accustomed(inputs: PlanInputs, anchor: LocalDate): Map<Muscle, Double>? {
        val dates = inputs.recentSessions.associate { it.id to it.date }
        val byWeek = LinkedHashMap<LocalDate, MutableList<SetLog>>()
        for (s in inputs.recentSets) {
            val d = dates[s.sessionId] ?: s.loggedAt.atZone(ZoneOffset.UTC).toLocalDate()
            if (!d.isBefore(anchor)) continue
            byWeek.getOrPut(Weeks.weekStart(d, inputs.weekStartsOnMonday)) { ArrayList() }.add(s)
        }
        if (byWeek.isEmpty()) return null
        val sum = DoubleArray(Muscle.entries.size)
        for ((_, sets) in byWeek) for ((m, v) in VolumeAccountant.weekly(sets, kb)) sum[m.ordinal] += v.fractional
        val out = LinkedHashMap<Muscle, Double>()
        for (m in Muscle.entries) if (sum[m.ordinal] > 0.0) out[m] = VolumeAccountant.round2(sum[m.ordinal] / byWeek.size)
        return out
    }

    companion object {
        const val MAX_PLATEAU_STEP: Int = 5
        const val MAX_DAYS: Int = 6
        const val EXTRA_MINUTES: Int = 10
        const val FINISHER_TRANSITION_MIN: Int = 3
        const val FRQ03_TARGET: Double = 10.0
        const val UNDER_TARGET_EPS: Double = 0.005
        const val AGE_POWER_SLOT: String = "age_power"
        const val AGE_POWER_INDEX: Int = -1
        const val POWER_PROFILE: String = "power"
        val LOWER_PATTERNS: Set<com.gymcoach.core.model.MovementPattern> = setOf(com.gymcoach.core.model.MovementPattern.SQUAT, com.gymcoach.core.model.MovementPattern.HINGE, com.gymcoach.core.model.MovementPattern.LUNGE_SPLIT)
        val NO_STRENGTH_VARIANT: Set<SplitKind> = setOf(SplitKind.FULL_BODY_1, SplitKind.FULL_BODY_2, SplitKind.FULL_BODY_3, SplitKind.TRAVEL_2)

        /** SHA-256 of CoreJson.stored.encodeToString(PlanInputs) (§4 intro). */
        fun inputsHash(inputs: PlanInputs): String = Hashing.sha256Hex(CoreJson.stored.encodeToString(PlanInputs.serializer(), inputs))

        /** Muscles of a soreness region's group (back = lats ∪ upper_back; delts; else the muscle itself). */
        fun groupOf(m: Muscle): List<Muscle> = when (m) {
            Muscle.LATS, Muscle.UPPER_BACK -> listOf(Muscle.LATS, Muscle.UPPER_BACK)
            Muscle.FRONT_DELTS, Muscle.SIDE_DELTS, Muscle.REAR_DELTS -> listOf(Muscle.FRONT_DELTS, Muscle.SIDE_DELTS, Muscle.REAR_DELTS)
            Muscle.GLUTES, Muscle.GLUTE_MED -> listOf(Muscle.GLUTES, Muscle.GLUTE_MED)
            Muscle.ABS, Muscle.OBLIQUES -> listOf(Muscle.ABS, Muscle.OBLIQUES)
            else -> listOf(m)
        }

        fun fmt(x: Double): String = java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    }
}

// Normalized view of the inputs (stage 0 output), reused by every later stage (§4.2).
data class Normalized(
    val age: Int,
    val trainingAge: TrainingAge,
    val goal: Goal,
    val tiers: Map<Muscle, MuscleTier>,
    val excludedExerciseIds: Set<String>,          // hard exclusions (limitations pain > 3, dislikes, ExcludeExercise overrides, difficulty gate)
    val penalizedExerciseIds: Set<String>,         // mild limitation (pain ≤ 3): SEL penalty + form note
    val elbowWristHalved: Boolean,                  // INJ-04
    val medicalGate: Boolean,                       // RED-03 without clearance
    val beginnerOnly: Boolean,                      // R4 §5.3
    val maxStrengthAllowed: Boolean,                // RED-03 and AGE-03 (§4.3)
    val over60: Boolean,                            // AGE-02 (AgePolicy, §4.4)
    val firstMesocycle: Boolean,                    // inputs.mesocyclesCompleted == 0 (DLD-01 / PROG-05 novice rules)
    val activeOverrides: List<com.gymcoach.core.model.PlanOverride>,
    val pinned: Map<String, String>,                // templateId ("*" = all) -> exerciseId
    val permanentSwaps: Map<String, String>,        // fromExerciseId -> toExerciseId (scope PERMANENT / REST_OF_MESOCYCLE)
    val sessionMinutes: Int,
    val daysPerWeek: Int,
    val preferredWeekdays: List<Int>,
    val priorityMuscles: List<Muscle>,
    val explanations: List<Explanation>,
)
