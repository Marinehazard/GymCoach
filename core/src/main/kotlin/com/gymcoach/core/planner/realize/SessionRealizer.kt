package com.gymcoach.core.planner.realize

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.GymParams
import com.gymcoach.core.equipment.LoadRounder
import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.mobility.MobilityPlanner
import com.gymcoach.core.model.Band
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.CardioPlacement
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.DrillPrescription
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.ExerciseBlock
import com.gymcoach.core.model.ExerciseClass
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LoadSource
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.LoadZone
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.RepUnit
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionMode
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetPrescription
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.VolumeSnapshot
import com.gymcoach.core.model.Warmup
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.planner.AgePolicy
import com.gymcoach.core.planner.Normalized
import com.gymcoach.core.planner.Normalizer
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.TimeModel
import com.gymcoach.core.substitution.LoadTransfer
import com.gymcoach.core.substitution.SubstitutionEngine
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.volume.VolumeAccountant
import kotlin.math.ceil

/**
 * Stage 6 (§4.7): today's SessionPlan from a PlannedSession. Steps in order — today-scope overrides and equipment
 * (substitution, keep-at-max, light-load fallback), layoff, readiness, deload / re-entry, time budget, per-set prescription
 * (loads, reps, RirStack, rest), safety, warm-up, cardio finisher, cool-down, volume snapshot and carryover. Every step that
 * changes something appends an Explanation to `modifiers` (or the block's warnings). Logged numbers are never touched: the
 * realizer reads states and logs, it writes only the prescription.
 */
class SessionRealizer(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val resolver = EquipmentResolver(kb)
    private val rounder = LoadRounder(kb)
    private val subs = SubstitutionEngine(kb, c)
    private val transfer = LoadTransfer(kb)
    private val layoff = LayoffPolicy(kb, c)
    private val readiness = ReadinessModifiers(c)
    private val concessions = TimeConcessions(c)
    private val warmups = WarmupGenerator(kb, c)
    private val mobility = MobilityPlanner(kb, c)
    private val time = TimeModel(c)
    private val agePolicy = AgePolicy(c)

    fun realize(planned: PlannedSession?, week: MesoWeek, inputs: PlanInputs, today: TodayContext, states: List<ProgressionState>, n: Normalized): SessionPlan {
        val gym = gymFor(inputs, today)
        val equipment = SessionEquipment(gym, today.extraItems, today.removedEquipmentIds.toSet(), today.spotter)
        val params = GymParams(kb, equipment)
        val limitations = Normalizer(kb, c).limitations(inputs, today.date)
        val effect = readiness.evaluate(today, inputs.readinessHistory)
        val weekday = Weeks.isoWeekday(today.date)
        val id = (planned?.id ?: FREE) + "@" + today.date

        if (planned == null) {
            val cardio = week.cardio.firstOrNull { it.weekday == weekday }
            return SessionPlan(
                id = id, plannedSessionId = null, templateId = REST_TEMPLATE, name = "Rest day", date = today.date, gymId = gym.id, mode = SessionMode.AS_PLANNED,
                weekIndex = week.index, readinessScore = today.readiness?.score,
                warmup = Warmup(0, null, emptyList(), 0, Explanations.of(listOf(RuleIds.MOB_02), "Rest day: no lifting warm-up.", mapOf("rest_day" to "true"), Confidence.WEAK)),
                blocks = emptyList(), cardio = cardio, cooldown = mobility.restDayCard(limitations), modifiers = emptyList(),
                estimatedMinutes = (cardio?.minutes ?: 0).toDouble(), volume = VolumeSnapshot(emptyMap(), emptyMap()),
            )
        }
        if (today.illnessSymptoms) {
            return SessionPlan(
                id = id, plannedSessionId = planned.id, templateId = planned.templateId, name = planned.name, date = today.date, gymId = gym.id,
                mode = SessionMode.ALTERNATIVE_Z2_MOBILITY, weekIndex = week.index, readinessScore = today.readiness?.score,
                warmup = Warmup(0, null, emptyList(), 0, Explanations.of(listOf(RuleIds.LAY_02), "No lifting while ill.", mapOf("illness" to "true"), Confidence.MODERATE)),
                blocks = emptyList(), cardio = null, cooldown = mobility.restDayCard(limitations),
                modifiers = listOf(Explanations.of(listOf(RuleIds.LAY_02, RuleIds.RDY_03), "Illness symptoms: today's session is paused. Rest; easy zone-2 walks only for ${c.lay.lay02Zone2OnlyDays} days, then a gentle restart.", mapOf("zone2_days" to c.lay.lay02Zone2OnlyDays.toString()), Confidence.MODERATE)),
                estimatedMinutes = 0.0, volume = VolumeSnapshot(emptyMap(), emptyMap()), alternativeOffered = true,
            )
        }

        val modifiers = ArrayList<Explanation>()
        val sessionMinutes = sessionMinutes(week, inputs, today, gym, n)
        val template = kb.templates[planned.templateId]
        val bodyWeight = inputs.bodyMetrics.filter { it.weight != null }.maxByOrNull { it.date }?.weight?.kg ?: 0.0
        val works = ArrayList<WorkBlock>()
        for (s in planned.slots) {
            if (s.sets <= 0) continue
            val ts = template?.slots?.firstOrNull { it.slotId == s.slotId }
            val ex = kb.exercise(s.exerciseId) ?: continue
            val opt = ex.equipmentOptions.getOrNull(s.optionIndex)
            works.add(WorkBlock(s.slotId, s.role, s.heavy, ts?.required ?: false, ex, s.optionIndex, opt?.loadType ?: ex.loadType, s.sets, s.sets, s.supersetGroup,
                ts?.backoffOf, power = ex.repProfile == POWER_PROFILE))
        }

        // 1. Today-scope overrides, then equipment.
        for (o in n.activeOverrides) {
            val m = o.mutation as? PlanMutation.SwapExercise ?: continue
            if (m.scope != MutationScope.TODAY || o.expiresOn != today.date) continue
            if (m.templateId != null && m.templateId != planned.templateId) continue
            val to = kb.exercise(m.toExerciseId) ?: continue
            for (w in works.filter { it.exercise.id == m.fromExerciseId }) {
                val a = resolver.resolve(to, equipment)
                if (!a.available) {
                    w.warnings.add(Explanations.of(listOf(RuleIds.OVERRIDE, RuleIds.TRV_04), "Today's swap to ${to.name} is not possible here (missing ${a.missing.joinToString(", ")}); substituting instead.", mapOf("to" to to.id), Confidence.WEAK))
                    continue
                }
                w.substitutedFor = w.exercise.id
                w.exercise = to
                w.optionIndex = a.optionIndex ?: 0
                w.loadType = a.effectiveLoadType ?: to.loadType
                modifiers.add(Explanations.of(listOf(RuleIds.OVERRIDE), "${kb.exercise(m.fromExerciseId)?.name ?: m.fromExerciseId} → ${to.name} for today (${o.source.name.lowercase()}).", mapOf("from" to m.fromExerciseId, "to" to to.id, "source" to o.source.name.lowercase()), Confidence.WEAK))
            }
        }
        for (w in works) {
            val a = resolver.resolve(w.exercise, equipment)
            if (a.available) {
                w.optionIndex = a.optionIndex ?: w.optionIndex
                w.loadType = a.effectiveLoadType ?: w.exercise.loadType
                continue
            }
            substitute(w, works, equipment, params, states, inputs, n, gym, bodyWeight, modifiers, "unavailable today (missing ${a.missing.joinToString(", ")})")
        }
        // Load feasibility (R4-2.6) and the light-load endurance fallback (TRV-01/LOAD-01).
        for (w in works.filter { it.active }) {
            if (!LoadTransfer.loadBearing(w.loadType)) continue
            val desired = workingLoad(w, states, inputs, gym) ?: seedLoad(w, states, inputs, n, gym, bodyWeight) ?: continue
            val achievable = rounder.achievableMaxKg(w.exercise, w.loadType, params) ?: continue
            if (achievable >= desired - EPS) continue
            val ratio = achievable / desired
            if (ratio >= c.sel.r426KeepAtMaxThreshold) {
                w.keepAtMax = true
                w.warnings.add(Explanations.of(listOf(RuleIds.R4_2_6), "${w.exercise.name}: ${fmt(desired)} kg wanted, ${fmt(achievable)} kg is the most this gym loads — kept at the maximum with +${c.sel.r426KeepAtMaxRepExtension} reps at the top and RIR ${c.sel.r426KeepAtMaxRirOffset}.",
                    mapOf("desired_kg" to fmt(desired), "achievable_kg" to fmt(achievable), "ratio" to fmt(ratio)), Confidence.WEAK))
            } else {
                w.endurance = true
                w.warnings.add(Explanations.of(listOf(RuleIds.LOAD_01, RuleIds.TRV_01, RuleIds.R4_2_6), "${w.exercise.name}: only ${fmt(achievable)} of ${fmt(desired)} kg available — light-load sets of ${c.trv.trv01EnduranceReps.lo}–${c.trv.trv01EnduranceReps.hi} reps close to failure (RIR ${c.trv.trv01EnduranceRir}).",
                    mapOf("desired_kg" to fmt(desired), "achievable_kg" to fmt(achievable), "ratio" to fmt(ratio)), Confidence.MODERATE))
            }
        }

        // 2. Layoff (LAY-01) — per block, from excess days over the plan's cadence.
        val layoffs = HashMap<String, LayoffModifier>()
        for (w in works.filter { it.active }) {
            val gap = layoff.scheduledGapDays(w.exercise.pattern, week)
            val mod = layoff.forBlockWithGap(w.exercise.pattern, w.exercise.id, today.date, gap, inputs, states, n.age, n.trainingAge) ?: continue
            layoffs[w.slotId] = mod
            val before = w.sets
            w.sets = when {
                mod.setFactor != null -> maxOf(1, ceil(w.sets * mod.setFactor - EPS).toInt())
                mod.row == 4 -> minOf(w.sets, setsBand(w.exercise, n).lo)
                else -> maxOf(1, w.sets + mod.setDelta)
            }
            modifiers.add(mod.explanation.copy(inputs = mod.explanation.inputs + mapOf("exercise_id" to w.exercise.id, "sets" to "$before→${w.sets}")))
        }

        // 3. Readiness.
        modifiers.addAll(effect.explanations)
        val activeIdx = works.filter { it.active }
        if (effect.setDropAfter != null) activeIdx.drop(effect.setDropAfter).forEach { it.sets = maxOf(1, it.sets - 1) }
        if (effect.setFactor != null) works.filter { it.active }.forEach { it.sets = maxOf(1, ceil(it.sets * effect.setFactor - EPS).toInt()) }
        if (effect.replaceHeavyBarbell) {
            for (w in works.filter { it.active }) {
                if (w.loadType != LoadType.BARBELL && w.loadType != LoadType.SMITH) continue
                if (!LoadZones.isHeavy(zoneOf(w, n, states, gym))) continue
                substitute(w, works, equipment, params, states, inputs, n, gym, bodyWeight, modifiers, "Red day: heavy barbell work replaced", preferModerate = true)
            }
        }
        if (effect.soreMuscles.isNotEmpty()) {
            for (w in works.filter { it.active && it.exercise.muscles.any { m -> m.weight >= 1.0 && m.muscle in effect.soreMuscles } }) w.sets = maxOf(1, w.sets - 1)
        }
        if (effect.sleepHeavySetFactor != null) {
            for (w in works.filter { it.active && it.exercise.flags.compound && LoadZones.isHeavy(zoneOf(it, n, states, gym)) }) w.sets = maxOf(1, ceil(w.sets * effect.sleepHeavySetFactor - EPS).toInt())
        }
        if (effect.stressSetFactor != null) works.filter { it.active }.forEach { it.sets = maxOf(1, ceil(it.sets * effect.stressSetFactor - EPS).toInt()) }

        // 4. Deload / re-entry (factors straight from PlannerConstants; DeloadPolicy is WP-04's explanation helper).
        when (week.mode) {
            WeekMode.DELOAD -> {
                works.filter { it.active }.forEach { it.sets = maxOf(1, ceil(it.sets * c.plat.dld02SetsFactor - EPS).toInt()) }
                modifiers.add(Explanations.of(listOf(RuleIds.DLD_02), "Deload week: sets × ${fmt(c.plat.dld02SetsFactor)} (rounded up), compound loads × ${fmt(c.plat.dld02CompoundLoadFactor)}, machine/isolation loads unchanged, RIR ${c.rir.rir04DeloadRir}.", mapOf("week" to week.index.toString()), Confidence.MODERATE))
            }
            WeekMode.REENTRY -> {
                works.filter { it.active }.forEach { it.sets = minOf(it.sets, setsBand(it.exercise, n).lo) }
                modifiers.add(Explanations.of(listOf(RuleIds.DLD_04), "Re-entry week: loads × ${fmt(c.plat.dld04LoadFactor)} and lower-bound sets (the week-1 RIR +1 is the only extra RIR).", mapOf("week" to week.index.toString()), Confidence.WEAK))
            }
            WeekMode.NORMAL, WeekMode.TRAVEL_MAINTENANCE -> Unit
        }
        if (agePolicy.singleSetWeek(n.age, n.trainingAge, week.index)) {
            works.filter { it.active }.forEach { it.sets = 1 }
            modifiers.add(Explanations.of(listOf(RuleIds.AGE_02, RuleIds.VOL_02), "First ${c.age.age02SingleSetStartWeeks} weeks: one working set per exercise (one hard set is already an effective dose).", mapOf("week" to week.index.toString()), Confidence.MODERATE))
        }

        // 5. Time budget (TIME-01, L1..L7).
        val wu06 = warmups.wu06(today, n.age)
        val warm = time.warmupMinutes(sessionMinutes, wu06)
        val finisher = week.cardio.firstOrNull { it.weekday == weekday && it.placement == CardioPlacement.FINISHER }
        val finisherMinutes = if (finisher != null && inputs.profile.cardioInsideSessionMinutes) finisher.minutes + FINISHER_TRANSITION_MIN else 0
        val priority = works.filter { it.active && it.role == SlotRole.PRIMARY }.take(2).map { it.slotId }.toSet()
        val credit = weekCompoundCredit(week, planned)
        val tc = concessions.apply(works, sessionMinutes, warm, finisherMinutes, sessionMinutes <= c.wu.wu05TrimAtOrBelowMinutes, credit, priority)
        modifiers.addAll(tc.applied)
        // §4.13 step 2: deficits left by this week's substituted or trimmed sessions are absorbed where the budget allows (Green, NORMAL days only).
        if (effect.band == ReadinessBand.GREEN && week.mode == WeekMode.NORMAL) {
            carryIn(works, week, planned, inputs, today, sessionMinutes - tc.warmupMinutes - finisherMinutes, tc.level, n, modifiers)
        }

        // 6./7. Prescription per set, safety.
        val blocks = ArrayList<ExerciseBlock>()
        val zones = LinkedHashMap<String, LoadZone>()
        val warmMuscles = HashSet<Muscle>()
        var calibrationDone = false
        val calibrationWeek = week.mode == WeekMode.NORMAL && week.index % c.rir.rir05CalibrationEveryWeeks == 0
        val activeWorks = works.filter { it.active }
        for ((order, w) in activeWorks.withIndex()) {
            val built = prescribe(w, order, week, inputs, today, states, n, gym, params, effect, layoffs[w.slotId], tc.level, bodyWeight, modifiers, activeWorks)
            var sets = built.sets
            if (calibrationWeek && !calibrationDone && (w.loadType == LoadType.MACHINE || w.loadType == LoadType.CABLE || w.exercise.isIsolation) && !w.exercise.flags.requiresSpotterOrSafeties && !w.endurance) {
                val lastWorking = sets.indexOfLast { it.kind == SetKind.WORKING }
                if (lastWorking >= 0) {
                    sets = sets.toMutableList().also { it[lastWorking] = it[lastWorking].copy(kind = SetKind.CALIBRATION) }
                    calibrationDone = true
                    modifiers.add(Explanations.of(listOf(RuleIds.RIR_05), "${w.exercise.name}: last set to failure (safe exercise) to recalibrate your RIR reports.", mapOf("exercise_id" to w.exercise.id), Confidence.MODERATE))
                }
            }
            // WU-02 ramp sets (find-load blocks carry their own RAMP sets).
            val firstWorking = sets.firstOrNull { it.kind != SetKind.RAMP }?.loadKg
            val ramps = if (sets.any { it.kind == SetKind.RAMP }) emptyList()
            else warmups.rampSets(w.exercise, w.loadType, firstWorking, built.zone, order == 0, w.exercise.primaryMuscles.any { it in warmMuscles }, wu06, params)
            val all = (ramps + sets).mapIndexed { i, s -> s.copy(index = i + 1) }
            for (m in w.exercise.muscles) if (m.weight >= 0.5) warmMuscles.add(m.muscle)
            zones[built.blockId] = built.zone
            blocks.add(ExerciseBlock(built.blockId, w.slotId, w.exercise.id, w.optionIndex, w.loadType, w.substitutedFor, w.role, order, all, w.group?.takeIf { g -> activeWorks.count { it.group == g } == 2 },
                ProgressionKey.forExercise(w.exercise.id, w.loadType, gym.id), w.warnings + built.warnings, built.explanation))
        }

        // 8. Warm-up.
        var warmup = warmups.generate(blocks, if (tc.warmupMinutes < warm) minOf(sessionMinutes, c.wu.wu05TrimAtOrBelowMinutes) else sessionMinutes, today, n.age, limitations, equipment, params, zones)
        if (warmup.totalMinutes != tc.warmupMinutes) warmup = warmup.copy(totalMinutes = tc.warmupMinutes)

        // 9./10. Cardio finisher and cool-down (+ AGE-02 balance block on the first two sessions of the week).
        val cooldown = ArrayList<DrillPrescription>()
        cooldown.addAll(mobility.cooldown(limitations, blocks.mapNotNull { kb.exercise(it.exerciseId)?.pattern }.distinct(), finisher?.kind?.let { it != CardioKind.ZONE2 && it != CardioKind.WALK } ?: false))
        if (n.over60) {
            val firstTwo = week.sessions.map { it.weekday }.distinct().sorted().take(c.age.age02BalanceDaysPerWeek)
            if (planned.weekday in firstTwo) cooldown.add(DrillPrescription(BALANCE_DRILL, 1, null, Band(c.age.age02BalanceMinutes * 60, c.age.age02BalanceMinutes * 60),
                Explanations.of(listOf(RuleIds.AGE_02), "${c.age.age02BalanceMinutes}-min balance block: single-leg stance and tandem walk.", mapOf("minutes" to c.age.age02BalanceMinutes.toString()), Confidence.MODERATE)))
        }

        // 11. Volume snapshot and the week's carryover projection (never across a week boundary).
        val volume = VolumeAccountant.ofBlocks(blocks, kb)
        val carry = carryover(week, planned, volume)
        val estimated = TimeModel.round2(time.sessionMinutes(blocks, kb, tc.warmupMinutes, finisherMinutes, tc.level))
        val mode = when (effect.band) {
            ReadinessBand.RED -> SessionMode.RED
            ReadinessBand.AMBER -> SessionMode.AMBER
            ReadinessBand.GREEN -> when (week.mode) {
                WeekMode.DELOAD -> SessionMode.DELOAD
                WeekMode.TRAVEL_MAINTENANCE -> SessionMode.TRAVEL
                WeekMode.NORMAL, WeekMode.REENTRY -> SessionMode.AS_PLANNED
            }
        }
        return SessionPlan(
            id = id, plannedSessionId = planned.id, templateId = planned.templateId, name = planned.name, date = today.date, gymId = gym.id, mode = mode,
            weekIndex = week.index, readinessScore = today.readiness?.score, warmup = warmup, blocks = blocks, cardio = finisher, cooldown = cooldown,
            modifiers = modifiers, estimatedMinutes = estimated, volume = volume, ledgerCarryover = carry, alternativeOffered = effect.alternativeOffered,
        )
    }

    private data class Built(val blockId: String, val sets: List<SetPrescription>, val zone: LoadZone, val warnings: List<Explanation>, val explanation: Explanation)

    private fun prescribe(
        w: WorkBlock, order: Int, week: MesoWeek, inputs: PlanInputs, today: TodayContext, states: List<ProgressionState>, n: Normalized, gym: GymSet,
        params: GymParams, effect: ReadinessEffect, lay: LayoffModifier?, level: Int, bodyWeight: Double, modifiers: MutableList<Explanation>, all: List<WorkBlock>,
    ): Built {
        val ex = w.exercise
        val key = ProgressionKey.forExercise(ex.id, w.loadType, gym.id)
        val state = states.firstOrNull { it.key == key }
        val prescription = kb.prescription(ex, n.goal)
        val warnings = ArrayList<Explanation>()
        val rules = LinkedHashSet<String>()
        rules.add(RuleIds.LOAD_03)

        // Rep band.
        var band: Band = prescription.range
        val repOverride = n.activeOverrides.mapNotNull { it.mutation as? PlanMutation.ChangeRepRange }.lastOrNull { it.exerciseId == ex.id && it.scope != MutationScope.TODAY }
        if (repOverride != null && repOverride.repLo < repOverride.repHi) { band = Band(repOverride.repLo, repOverride.repHi); rules.add(RuleIds.OVERRIDE) }
        val aged = agePolicy.repBand(n.age, ex, band)
        if (aged != band) { band = aged; rules.add(RuleIds.AGE_02) }
        if (state != null && state.repZoneShift > 0) { band = shiftedBand(kb, ex, n.goal, band, state.repZoneShift); rules.add(RuleIds.PLAT_02) }
        if (state != null && state.repHiExtended && state.workingReps.hi > state.repHiBase) { band = Band(band.lo, band.hi + (state.workingReps.hi - state.repHiBase)); rules.add(RuleIds.PROG_02) }
        if (w.backoffOf != null) { band = c.prog.load03BackoffReps; rules.add(RuleIds.PROG_03) }
        if (w.keepAtMax) { band = Band(band.lo, band.hi + c.sel.r426KeepAtMaxRepExtension); rules.add(RuleIds.R4_2_6) }
        if (w.endurance) { band = c.trv.trv01EnduranceReps; rules.add(RuleIds.LOAD_01) }

        // RIR (RirStack).
        val lastWeek = week.mode == WeekMode.NORMAL && week.rirOffset != 0 && week.rirOffset == c.rir.rir04LastWeekOffset
        val noviceGate = n.firstMesocycle && n.trainingAge == TrainingAge.NOVICE && week.index <= c.prog.prog05NoviceWeeks
        val base: Int
        val weekOffset: Int
        when {
            noviceGate -> { base = ceil((c.prog.prog05NoviceRir.lo + c.prog.prog05NoviceRir.hi) / 2.0).toInt(); weekOffset = 0; rules.add(RuleIds.PROG_05) }
            lastWeek -> { base = RirStack.lastWeekTargetRir(prescription); weekOffset = 0 }
            else -> { base = RirStack.profileTargetRir(prescription); weekOffset = week.rirOffset }
        }
        rules.add(RuleIds.RIR_04)
        val cls = ex.exerciseClass(w.loadType)
        var floor = if (ex.flags.compound) c.rir.rir04LastWeekCompoundFloor else c.rir.rir04LastWeekIsolationFloor
        if (cls == ExerciseClass.LOWER_BARBELL) floor = maxOf(floor, c.rir.rir03LowerBarbellFloor)
        if (cls == ExerciseClass.UPPER_BARBELL) floor = maxOf(floor, c.rir.rir03UpperBarbellFloor)
        val noRack = ex.flags.requiresSpotterOrSafeties && !resolver.resolve(ex, SessionEquipment(gym, today.extraItems, today.removedEquipmentIds.toSet(), today.spotter)).safetiesOrSpotter
        if (noRack) {
            floor = maxOf(floor, c.rir.rir03SpWithoutRackFloor)
            rules.add(RuleIds.RIR_03)
            val note = ex.content?.safety?.firstOrNull() ?: "Set the safeties or use a spotter for sets close to failure."
            warnings.add(Explanations.of(listOf(RuleIds.R4_5_5, RuleIds.RIR_03), "${ex.name} without safety bars: RIR ≥ ${c.rir.rir03SpWithoutRackFloor}. $note", mapOf("exercise_id" to ex.id), Confidence.MODERATE))
        }
        if (ex.flags.requiresSpotterOrSafeties) floor = maxOf(floor, 1)
        if (w.power) floor = maxOf(floor, c.age.age02PowerMinRir)
        var layoffOffset = lay?.rirOffset ?: 0
        if (lay != null && lay.row == 4) { floor = maxOf(floor, c.lay.lay01Row4Rir.lo); layoffOffset = 0 }
        val readinessOffset = effect.rirOffset + if (effect.soreMuscles.isNotEmpty() && ex.muscles.any { it.weight >= 1.0 && it.muscle in effect.soreMuscles }) RDY04_OFFSET else 0
        val zone0 = zoneOf(w, n, states, gym, band, base + weekOffset)
        val sleepOffset = if (effect.sleepHeavyRirOffset > 0 && ex.flags.compound && LoadZones.isHeavy(zone0)) effect.sleepHeavyRirOffset else 0
        val stressOffset = if (effect.stressMainLiftRirOffset > 0 && w.role == SlotRole.PRIMARY) effect.stressMainLiftRirOffset else 0
        val keepOffset = if (w.keepAtMax) c.sel.r426KeepAtMaxRirOffset else 0
        val (rir, capNote) = RirStack.finalRir(
            RirInput(base, weekOffset, readinessOffset, layoffOffset, sleepOffset, stressOffset, keepOffset, floor,
                if (week.mode == WeekMode.DELOAD) c.rir.rir04DeloadRir else null, if (w.endurance) c.trv.trv01EnduranceRir else null), c)
        if (capNote != null) modifiers.add(capNote.copy(inputs = capNote.inputs + ("exercise_id" to ex.id)))
        var zone = LoadZones.of(band, rir, w.heavy && !w.endurance, w.power, state?.history?.firstOrNull { it.e1rm != null }?.e1rm, c)
        if (zone == LoadZone.MAX_STRENGTH && (!n.maxStrengthAllowed || effect.noMaxStrength)) { zone = LoadZone.HEAVY; rules.add(RuleIds.AGE_03) }
        rules.add(RuleIds.LOAD_04)
        rules.add(RuleIds.DEF_03)
        if (zone == LoadZone.MAX_STRENGTH) rules.add(RuleIds.LOAD_02)
        if (state?.history?.firstOrNull { it.e1rm != null }?.e1rm?.confidence == com.gymcoach.core.model.E1rmConfidence.HIGH) rules.add(RuleIds.E1RM_02)
        rules.add(if (n.goal == Goal.STRENGTH) RuleIds.RIR_02 else RuleIds.RIR_01)
        rules.add(RuleIds.DEF_02)
        if (cls == ExerciseClass.LOWER_BARBELL || cls == ExerciseClass.UPPER_BARBELL) rules.add(RuleIds.DEF_07)
        if (ex.unilateral) rules.add(RuleIds.UNI_01)
        rules.add(if (order == 0) RuleIds.ORD_01 else RuleIds.ORD_02)
        rules.add(RuleIds.TEMPO_01)
        rules.add(RuleIds.TEMPO_02)
        rules.add(RuleIds.ROM_01)
        if (key.gymScope != null) rules.add(RuleIds.E1RM_04)
        if (ex.id in n.penalizedExerciseIds) warnings.add(Explanations.of(listOf(RuleIds.INJ_00, RuleIds.ROM_02, RuleIds.ROM_03), "${ex.name}: keep pain ≤ 3/10 — if a part of the range hurts, train the pain-free (lengthened) part and tell the coach.", mapOf("exercise_id" to ex.id), Confidence.WEAK))

        // Rest (REST-01..04, AGE-02, TRV-01).
        var rest = when {
            !ex.flags.compound || w.loadType == LoadType.MACHINE || w.loadType == LoadType.CABLE -> c.rest.rest03IsolationSec.also { rules.add(RuleIds.REST_03) }
            LoadZones.isHeavy(zone) -> (if (band.hi <= TOP_SET_REPS) c.rest.rest02TopSetSec else c.rest.rest02HeavySec).also { rules.add(RuleIds.REST_02) }
            else -> c.rest.rest01HypertrophySec.also { rules.add(RuleIds.REST_01) }
        }
        val mainLift = w.role == SlotRole.PRIMARY && ex.flags.compound
        if (level >= 4 && (!ex.flags.compound || w.loadType == LoadType.MACHINE || w.loadType == LoadType.CABLE)) { rest = c.rest.rest04CutSec; rules.add(RuleIds.REST_04) }
        if (n.over60) rest = agePolicy.restSec(n.age, rest)
        if (week.mode == WeekMode.TRAVEL_MAINTENANCE) rest = rest.coerceIn(TRAVEL_REST_LO, TRAVEL_REST_HI)
        if (mainLift && level >= 4) rest = maxOf(rest, c.rest.rest04MainLiftFloorSec)
        val firstOfPair = w.group != null && all.count { it.group == w.group } == 2 && all.first { it.group == w.group } === w
        if (firstOfPair) { rest = c.rest.rest03SupersetTransitionSec; rules.add(RuleIds.REST_03) }

        // Load.
        val unit = kb.repProfile(ex).unit
        val sets = ArrayList<SetPrescription>()
        fun working(n: Int, loadKg: Double?, label: String?, perHand: Boolean, source: LoadSource) {
            for (i in 0 until n) sets.add(setOf(unit, band, loadKg, label, perHand, rir, rest, source))
        }
        if (!LoadTransfer.loadBearing(w.loadType)) {
            working(w.sets, null, if (w.loadType == LoadType.BODYWEIGHT) BODYWEIGHT_LABEL else null, false, if (state != null) LoadSource.PROGRESSION_STATE else LoadSource.NONE)
        } else {
            val override = n.activeOverrides.mapNotNull { it.mutation as? PlanMutation.AdjustWorkingLoad }.lastOrNull { it.exerciseId == ex.id && (it.gymScope == null || it.gymScope == key.gymScope) }
            var source = LoadSource.PROGRESSION_STATE
            var load: Double? = workingLoad(w, states, inputs, gym)
            if (override != null) { load = override.newWorkingLoadKg; source = LoadSource.USER_OVERRIDE; rules.add(RuleIds.OVERRIDE) }
            val achievable = rounder.achievableMaxKg(ex, w.loadType, params)
            if (load != null) {
                if (w.backoffOf != null) load *= c.prog.load03BackoffFactor
                if (week.mode == WeekMode.DELOAD && ex.flags.compound && w.loadType != LoadType.MACHINE && w.loadType != LoadType.CABLE) load *= c.plat.dld02CompoundLoadFactor
                if (week.mode == WeekMode.REENTRY) load *= c.plat.dld04LoadFactor
                if (lay != null) load *= lay.loadFactor
                if (achievable != null && load > achievable) load = achievable
                val r = rounder.round(load, ex, w.loadType, params)
                if (r.roundingErrorPct > LoadRounder.ROUNDING_WARN_PCT) warnings.add(Explanations.of(listOf(RuleIds.ROUNDING), "${ex.name}: ${fmt(load)} kg rounded to ${r.label} (${fmt(r.roundingErrorPct)} %).", mapOf("desired" to fmt(load), "loaded" to fmt(r.kg)), Confidence.WEAK))
                working(w.sets, r.kg, r.label, r.perHand, source)
            } else {
                // Find-load (§4.9.2): L0 = known load / transfer seed / body-weight seed / floor, capped at the achievable maximum.
                rules.add(RuleIds.TRV_04)
                val seed = seedLoad(w, states, inputs, n, gym, bodyWeight)
                val l0 = seed ?: floorLoad(ex, w.loadType, params)
                if (achievable != null && l0 >= achievable - EPS) {
                    val r = rounder.round(achievable, ex, w.loadType, params)
                    if (!w.keepAtMax && !w.endurance) warnings.add(Explanations.of(listOf(RuleIds.R4_2_6), "${ex.name}: the starting guess is at or above the heaviest load here — straight to working sets at ${r.label}.", mapOf("seed_kg" to fmt(l0), "achievable_kg" to fmt(achievable)), Confidence.WEAK))
                    working(w.sets, r.kg, r.label, r.perHand, LoadSource.LOAD_FIND)
                } else {
                    var last = -1.0
                    val rampReps = Band(band.lo + FIND_LOAD_EXTRA_REPS, band.lo + FIND_LOAD_EXTRA_REPS)
                    for (f in c.trv.trv04RampFactors) {
                        var kg = l0 * f
                        if (achievable != null) kg = minOf(kg, achievable)
                        val r = rounder.round(kg, ex, w.loadType, params)
                        if (r.kg <= last + EPS) continue
                        last = r.kg
                        sets.add(SetPrescription(0, SetKind.RAMP, if (unit == RepUnit.REPS) rampReps else null, null, null, r.kg, r.label, r.perHand, null, FIND_LOAD_REST_SEC, LoadSource.LOAD_FIND))
                    }
                    working(w.sets, null, null, false, LoadSource.LOAD_FIND)
                    warnings.add(Explanations.of(listOf(RuleIds.TRV_04), "${ex.name}: find your working load — ramp from ${fmt(l0)} kg until a set feels like RIR ≤ ${minOf(rir + c.trv.trv04StopRirOffset, c.rir.rirCapMax)}.",
                        mapOf("start_kg" to fmt(l0), "stop_rir" to minOf(rir + c.trv.trv04StopRirOffset, c.rir.rirCapMax).toString()), Confidence.WEAK))
                }
            }
        }
        val inputsMap = LinkedHashMap<String, String>()
        inputsMap[TimeModel.HEAVY_KEY] = w.heavy.toString()
        inputsMap["zone"] = zone.name.lowercase()
        inputsMap["reps"] = "${band.lo}-${band.hi}"
        inputsMap["rir"] = rir.toString()
        inputsMap["base_rir"] = base.toString()
        inputsMap["rest_sec"] = rest.toString()
        inputsMap["sets"] = w.sets.toString()
        if (w.substitutedFor != null) inputsMap["substituted_for"] = w.substitutedFor ?: ""
        val first = sets.firstOrNull { it.kind != SetKind.RAMP }
        val loadText = first?.loadLabel ?: "find load"
        val explanation = Explanations.of(rules.toList(), "${w.sets} × ${if (unit == RepUnit.SECONDS) "${band.lo}–${band.hi} s" else "${band.lo}–${band.hi}"} @ $loadText, RIR $rir (≈ RPE ${RPE_AT_ZERO_RIR - rir}), rest $rest s (${zone.name.lowercase().replace('_', ' ')}); full range, controlled 1–3 s lowering.", inputsMap, Confidence.MODERATE)
        return Built(w.slotId + ":" + ex.id, sets, zone, warnings, explanation)
    }

    private fun setOf(unit: RepUnit, band: Band, loadKg: Double?, label: String?, perHand: Boolean, rir: Int, rest: Int, source: LoadSource): SetPrescription = when (unit) {
        RepUnit.REPS -> SetPrescription(0, SetKind.WORKING, band, null, null, loadKg, label, perHand, rir, rest, source)
        RepUnit.SECONDS -> SetPrescription(0, SetKind.WORKING, null, band, null, loadKg, label, perHand, rir, rest, source)
        RepUnit.METRES -> SetPrescription(0, SetKind.WORKING, null, null, band, loadKg, label, perHand, rir, rest, source)
        RepUnit.MINUTES -> SetPrescription(0, SetKind.WORKING, null, Band(band.lo * 60, band.hi * 60), null, loadKg, label, perHand, rir, rest, source)
    }

    private fun zoneOf(w: WorkBlock, n: Normalized, states: List<ProgressionState>, gym: GymSet, band: Band? = null, rir: Int? = null): LoadZone {
        val p = kb.prescription(w.exercise, n.goal)
        val b = band ?: p.range
        val state = states.firstOrNull { it.key == ProgressionKey.forExercise(w.exercise.id, w.loadType, gym.id) }
        return LoadZones.of(b, rir ?: RirStack.profileTargetRir(p), w.heavy, w.power, state?.history?.firstOrNull { it.e1rm != null }?.e1rm, c)
    }

    /** State working load, else the onboarding known load (not for gym-scoped keys at another gym). */
    private fun workingLoad(w: WorkBlock, states: List<ProgressionState>, inputs: PlanInputs, gym: GymSet): Double? {
        val key = ProgressionKey.forExercise(w.exercise.id, w.loadType, gym.id)
        states.firstOrNull { it.key == key }?.workingLoadKg?.let { return it }
        if (key.gymScope == null) inputs.profile.knownLoadsKg[w.exercise.id]?.let { return it }
        return null
    }

    /** Find-load / substitution starting load: from the replaced block's load, else the Appendix C seed. */
    private fun seedLoad(w: WorkBlock, states: List<ProgressionState>, inputs: PlanInputs, n: Normalized, gym: GymSet, bodyWeight: Double): Double? {
        val replaced = w.substitutedFor?.let { kb.exercise(it) }
        val replacedLoad = w.replacedLoadKg
        if (replaced != null && replacedLoad != null) transfer.fromReplaced(replaced, replacedLoad, w.exercise, w.loadType)?.let { return it.first }
        return transfer.seed(w.exercise, w.loadType, states, inputs.profile, n.trainingAge, bodyWeight)?.first
    }

    /** §4.9.2 floor: empty bar, lightest dumbbell(s), one stack step, lightest bell. */
    private fun floorLoad(ex: Exercise, lt: LoadType, params: GymParams): Double {
        val implements = com.gymcoach.core.equipment.IncrementResolver(kb).implements(ex, lt)
        return when (lt) {
            LoadType.BARBELL -> params.barKg
            LoadType.SMITH -> LoadRounder.DEFAULT_SMITH_BAR_KG
            LoadType.DUMBBELL -> (params.dumbbellMinKg ?: LoadRounder.DEFAULT_DB_MIN_KG) * implements
            LoadType.KETTLEBELL -> (params.kettlebellsKg?.minOrNull() ?: LoadRounder.DEFAULT_KETTLEBELLS.first()) * implements
            LoadType.MACHINE, LoadType.CABLE, LoadType.SLED, LoadType.MEDICINE_BALL -> com.gymcoach.core.equipment.IncrementResolver.DEFAULT_STACK_STEP_KG
            LoadType.BODYWEIGHT, LoadType.BAND, LoadType.CARDIO, LoadType.NONE -> 0.0
        }
    }

    /** §4.12 steps 4–6: the top available candidate replaces the block (or MERGES into a block of the same exercise). */
    private fun substitute(
        w: WorkBlock, works: List<WorkBlock>, equipment: SessionEquipment, params: GymParams, states: List<ProgressionState>, inputs: PlanInputs,
        n: Normalized, gym: GymSet, bodyWeight: Double, modifiers: MutableList<Explanation>, why: String, preferModerate: Boolean = false,
    ) {
        val replacedLoad = workingLoad(w, states, inputs, gym)
        val ranked = subs.rankFor(w.exercise, replacedLoad, w.sets, equipment, params, states, n.trainingAge, n.excludedExerciseIds, n.penalizedExerciseIds,
            inputs.profile.allowAdvancedExercises, inputs.profile, gym.id, bodyWeight).filter { it.available }
        val pick = if (preferModerate) ranked.firstOrNull { it.effectiveLoadType == LoadType.MACHINE || it.effectiveLoadType == LoadType.DUMBBELL || it.effectiveLoadType == LoadType.CABLE } else ranked.firstOrNull()
        if (pick == null) {
            w.sets = 0
            w.droppedBy = RuleIds.TRV_04
            modifiers.add(Explanations.of(listOf(RuleIds.TRV_04, RuleIds.SUB), "${w.exercise.name}: $why and no substitute is available — skipped today.", mapOf("exercise_id" to w.exercise.id), Confidence.WEAK))
            return
        }
        val to = kb.requireExercise(pick.exerciseId)
        val table = ranked.take(SCORE_TABLE).joinToString("; ") { "${it.exerciseId} ${String.format(java.util.Locale.ROOT, "%.3f", it.score)}" }
        val inputsMap = LinkedHashMap<String, String>()
        inputsMap["from"] = w.exercise.id
        inputsMap["to"] = to.id
        inputsMap["score"] = String.format(java.util.Locale.ROOT, "%.3f", pick.score)
        inputsMap["ranking"] = table
        val existing = works.firstOrNull { it !== w && it.active && it.exercise.id == to.id }
        if (existing != null) {
            val hi = setsBand(to, n).hi
            val merged = minOf(hi, existing.sets + w.sets)
            modifiers.add(Explanations.of(listOf(RuleIds.TRV_04, RuleIds.SUB), "${w.exercise.name}: $why → merged into ${to.name} (${existing.sets} → $merged sets).", inputsMap, Confidence.WEAK))
            existing.sets = merged
            w.sets = 0
            w.droppedBy = RuleIds.SUB
            return
        }
        modifiers.add(Explanations.of(listOf(RuleIds.TRV_04, RuleIds.SUB), "${w.exercise.name}: $why → ${to.name} (score ${inputsMap["score"]}; ranking $table).", inputsMap, Confidence.WEAK))
        w.substitutedFor = w.substitutedFor ?: w.exercise.id
        w.replacedLoadKg = replacedLoad ?: w.replacedLoadKg
        w.exercise = to
        w.optionIndex = pick.optionIndex ?: 0
        w.loadType = pick.effectiveLoadType ?: to.loadType
    }

    /**
     * Deficit per muscle = Σ over this week's completed sessions (before today) of planned − logged fractional credit; for each
     * deficit of at least one set, add sets to today's blocks crediting the muscle at 1.0 (per-exercise band, VOL-06, SESS-02 and
     * the minute budget respected). Carryover never crosses a week boundary.
     */
    private fun carryIn(works: List<WorkBlock>, week: MesoWeek, planned: PlannedSession, inputs: PlanInputs, today: TodayContext, budget: Int, level: Int, n: Normalized, modifiers: MutableList<Explanation>) {
        val byId = week.sessions.associateBy { it.id }
        val done = inputs.recentSessions.filter { r ->
            r.status == com.gymcoach.core.model.SessionStatus.COMPLETED && r.plannedSessionId != null && r.plannedSessionId != planned.id &&
                byId.containsKey(r.plannedSessionId) && !r.date.isBefore(week.startDate) && r.date.isBefore(today.date)
        }
        if (done.isEmpty()) return
        val deficit = DoubleArray(Muscle.entries.size)
        for (r in done) {
            val plannedVol = byId.getValue(r.plannedSessionId ?: continue).volumeFractional
            val logged = VolumeAccountant.ofSets(inputs.recentSets.filter { it.sessionId == r.id }, kb).fractional
            for ((m, v) in plannedVol) deficit[m.ordinal] += v - (logged[m] ?: 0.0)
        }
        val cap = when (n.trainingAge) {
            TrainingAge.NOVICE -> c.sess.sess02CapNovice
            TrainingAge.INTERMEDIATE -> c.sess.sess02CapDefault
            TrainingAge.ADVANCED -> c.sess.sess02CapAdvanced
        }
        val muscles = Muscle.entries.filter { week.targets.containsKey(it) && deficit[it.ordinal] >= 1.0 - EPS }.sortedWith(compareBy<Muscle>({ -deficit[it.ordinal] }, { it.ordinal }))
        for (m in muscles) {
            var added = 0
            while (deficit[m.ordinal] >= 1.0 - EPS) {
                val sessionMuscle = DoubleArray(Muscle.entries.size)
                for (w in works) if (w.active) for (mw in w.exercise.muscles) sessionMuscle[mw.muscle.ordinal] += mw.weight * w.sets
                val target = works.firstOrNull { w ->
                    w.active && w.exercise.weightOf(m) >= 1.0 && w.sets < setsBand(w.exercise, n).hi &&
                        w.exercise.muscles.all { sessionMuscle[it.muscle.ordinal] + it.weight <= c.vol.vol06PerSessionCap + EPS }
                } ?: break
                if (works.sumOf { it.sets } + 1 > cap) break
                target.sets++
                if (concessions.minutes(works, level) > budget + EPS) { target.sets--; break }
                deficit[m.ordinal] -= 1.0
                added++
                if (added == 1) target.notes.add(Explanations.of(listOf(RuleIds.VOL_04, RuleIds.DEF_05), "carryover", emptyMap(), Confidence.WEAK))
            }
            if (added > 0) modifiers.add(Explanations.of(listOf(RuleIds.VOL_04, RuleIds.DEF_05, RuleIds.VOL_06),
                "Carryover from earlier this week: +$added set(s) for ${m.name.lowercase().replace('_', ' ')} (a substituted or shortened session left it behind).",
                mapOf("muscle" to m.name.lowercase(), "added_sets" to added.toString()), Confidence.WEAK))
        }
    }

    private fun setsBand(e: Exercise, n: Normalized): Band = agePolicy.setsBand(n.age, kb.prescription(e, n.goal).sets ?: Band(2, 4))

    /** Fractional credit per muscle from COMPOUND slots of the week's other sessions (TIME-L2 test). */
    private fun weekCompoundCredit(week: MesoWeek, planned: PlannedSession): DoubleArray {
        val out = DoubleArray(Muscle.entries.size)
        for (s in week.sessions) {
            if (s.id == planned.id) continue
            for (slot in s.slots) {
                val e = kb.exercise(slot.exerciseId) ?: continue
                if (!e.flags.compound) continue
                for (mw in e.muscles) out[mw.muscle.ordinal] += mw.weight * slot.sets
            }
        }
        return out
    }

    /** Week projection: other planned sessions + today's realized volume − targets; negative entries only. */
    private fun carryover(week: MesoWeek, planned: PlannedSession, today: VolumeSnapshot): Map<Muscle, Double> {
        val arr = DoubleArray(Muscle.entries.size)
        for (s in week.sessions) if (s.id != planned.id) for ((m, v) in s.volumeFractional) arr[m.ordinal] += v
        for ((m, v) in today.fractional) arr[m.ordinal] += v
        val out = LinkedHashMap<Muscle, Double>()
        for (m in Muscle.entries) {
            val t = week.targets[m] ?: continue
            val d = VolumeAccountant.round2(arr[m.ordinal] - t)
            if (d < 0.0) out[m] = d
        }
        return out
    }

    fun gymFor(inputs: PlanInputs, today: TodayContext): GymSet =
        inputs.gyms.firstOrNull { it.id == today.gymId } ?: inputs.gyms.firstOrNull { it.id == inputs.defaultGymId } ?: checkNotNull(inputs.gyms.firstOrNull()) { "PlanInputs.gyms is empty" }

    /** today.minutesOverride, else the plan's minutes at the plan gym, else the gym's default (hotel 45), clamped by TRV-01 in travel weeks. */
    fun sessionMinutes(week: MesoWeek, inputs: PlanInputs, today: TodayContext, gym: GymSet, n: Normalized): Int {
        today.minutesOverride?.let { return it }
        val base = if (gym.id == inputs.defaultGymId) n.sessionMinutes else gym.defaultSessionMinutes ?: n.sessionMinutes
        return if (week.mode == WeekMode.TRAVEL_MAINTENANCE) minOf(n.sessionMinutes, base).coerceIn(c.trv.trv01Minutes.lo, c.trv.trv01Minutes.hi) else base
    }

    companion object {
        const val FREE: String = "free"
        const val REST_TEMPLATE: String = "REST"
        const val POWER_PROFILE: String = "power"
        const val BALANCE_DRILL: String = "balance_block"
        const val BODYWEIGHT_LABEL: String = "BW"
        const val EPS: Double = 1e-9
        const val FINISHER_TRANSITION_MIN: Int = 3
        const val TOP_SET_REPS: Int = 6
        const val TRAVEL_REST_LO: Int = 60
        const val TRAVEL_REST_HI: Int = 90
        const val FIND_LOAD_EXTRA_REPS: Int = 2
        const val FIND_LOAD_REST_SEC: Int = 75
        const val RDY04_OFFSET: Int = 1
        /** DEF-02: RPE 10 = 0 RIR, so RPE = 10 − RIR. */
        const val RPE_AT_ZERO_RIR: Int = 10
        const val SCORE_TABLE: Int = 4

        /** PLAT-02 step 2 / LOAD-04: one rep zone lighter (barbell compound → compound → isolation → isolation light; else +2/+2 per step). */
        fun shiftedBand(kb: KnowledgeBase, ex: Exercise, goal: Goal, band: Band, shift: Int): Band {
            if (shift <= 0) return band
            var profile = ex.repProfile
            var out = band
            repeat(shift) {
                val next = LIGHTER[profile]
                val p = next?.let { kb.repProfiles[it] }
                if (p != null) {
                    out = p.forGoal(goal).range
                    profile = next
                } else out = Band(out.lo + 2, out.hi + 2)
            }
            return out
        }

        fun fmt(x: Double): String = java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        private val LIGHTER: Map<String, String> = mapOf("barbell_compound" to "compound", "compound" to "isolation", "isolation" to "isolation_light")
    }
}
