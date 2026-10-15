package com.gymcoach.core.mutation

import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.CardioKind
import com.gymcoach.core.model.CheckinSchedule
import com.gymcoach.core.model.Difficulty
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.LoadType
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.ProgressionStatus
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.planner.SplitSelector
import com.gymcoach.core.substitution.SubstitutionEngine
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.volume.TargetPolicy
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

sealed class ValidationResult {
    data class Valid(val warnings: List<String>) : ValidationResult()
    data class Invalid(val errors: List<String>) : ValidationResult()
}

/**
 * §4.15 — every coach / user proposal passes this table before a card is shown (reject = `Invalid(errors)` → an is_error
 * tool result; warn = shown on the card). Pure: the reference day is `today.date`, else the mesocycle's current week start.
 */
class MutationValidator(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val resolver = EquipmentResolver(kb)
    private val targetPolicy = TargetPolicy(c)

    /**
     * §4.15 table. `mesocycle` is null before the first plan exists (then only ChangeGoal, ChangeSchedule,
     * SetLimitation, SetTravelDays, SetPriorityMuscles and UpdateNutritionTargets can be Valid). `today` is
     * null when validating outside a day context (Plan screen); `nutrition` is the current targets for
     * UpdateNutritionTargets bounds (§5.5) or null.
     */
    fun validate(mutation: PlanMutation, inputs: PlanInputs, mesocycle: Mesocycle?, today: TodayContext?, nutrition: NutritionTargets? = null): ValidationResult {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val needsPlan = when (mutation) {
            is PlanMutation.ChangeGoal, is PlanMutation.ChangeSchedule, is PlanMutation.SetLimitation, is PlanMutation.SetTravelDays,
            is PlanMutation.SetPriorityMuscles, is PlanMutation.UpdateNutritionTargets -> false
            is PlanMutation.SwapExercise, is PlanMutation.ChangeVolume, is PlanMutation.ChangeRepRange, is PlanMutation.AddCardio,
            is PlanMutation.RemoveCardio, is PlanMutation.SetCheckinQuestion, is PlanMutation.ExcludeExercise, is PlanMutation.PinExercise,
            is PlanMutation.AdjustWorkingLoad, is PlanMutation.ScheduleDeload -> true
        }
        if (needsPlan && mesocycle == null) return ValidationResult.Invalid(listOf("No plan exists yet: create the plan first."))
        val refDate = today?.date ?: mesocycle?.let { currentWeek(it, null)?.startDate }
        val week = mesocycle?.let { currentWeek(it, refDate) }
        when (mutation) {
            is PlanMutation.SwapExercise -> swap(mutation, inputs, mesocycle, today, errors, warnings)
            is PlanMutation.ChangeVolume -> {
                if (mutation.deltaSetsPerWeek !in -MAX_VOLUME_DELTA..MAX_VOLUME_DELTA) errors.add("Volume change ${mutation.deltaSetsPerWeek} is outside −$MAX_VOLUME_DELTA..+$MAX_VOLUME_DELTA sets per week.")
                val current = week?.targets?.get(mutation.muscle)
                val result = (current ?: 0.0) + mutation.deltaSetsPerWeek
                if (result < c.vol.vol01Minimum) errors.add("${name(mutation.muscle.name)} would end at ${fmt(result)} sets/week, below the VOL-01 minimum of ${c.vol.vol01Minimum}.")
                val hi = targetPolicy.volBand(mesocycle?.trainingAge ?: com.gymcoach.core.model.TrainingAge.INTERMEDIATE).hi
                if (result > hi) warnings.add("${fmt(result)} sets/week is beyond the evidence band (VOL-04 upper bound $hi); sets beyond it are user-owned.")
                val sessions = week?.sessions?.count { (it.volumeFractional[mutation.muscle] ?: 0.0) > 0.0 }?.coerceAtLeast(1) ?: 1
                if (result / sessions > c.vol.vol06PerSessionCap) warnings.add("More than ${c.vol.vol06PerSessionCap} sets per session for ${name(mutation.muscle.name)} (VOL-06): this will need a session added.")
            }
            is PlanMutation.ChangeRepRange -> {
                if (mutation.repLo >= mutation.repHi) errors.add("Rep range ${mutation.repLo}–${mutation.repHi}: the low end must be below the high end.")
                if (mutation.repLo < MIN_REPS || mutation.repHi > MAX_REPS) errors.add("Rep range must lie within $MIN_REPS–$MAX_REPS.")
                if (!inPlan(mesocycle, mutation.exerciseId)) errors.add("${exName(mutation.exerciseId)} is not in the plan.")
                val e = kb.exercise(mutation.exerciseId)
                if (e != null && mesocycle != null) {
                    val p = kb.prescription(e, mesocycle.goal).range
                    if (mutation.repLo < p.lo || mutation.repHi > p.hi) warnings.add("Outside the ${mesocycle.goal.name.lowercase()} range ${p.lo}–${p.hi} for this exercise (LOAD-03).")
                    if (mutation.repHi > BARBELL_HIGH_REPS && e.loadType == LoadType.BARBELL && e.flags.compound) warnings.add("More than $BARBELL_HIGH_REPS reps on a barbell compound: RIR estimates get less accurate (RIR-05, Halperin 2022).")
                }
            }
            is PlanMutation.AddCardio -> {
                if (mutation.weekday !in 1..7) errors.add("Weekday ${mutation.weekday} must be 1 (Mon) … 7 (Sun).")
                if (mutation.minutes !in MIN_CARDIO..MAX_CARDIO) errors.add("Cardio minutes must be $MIN_CARDIO–$MAX_CARDIO.")
                val hiit = mutation.kind == CardioKind.HIIT_4X4 || mutation.kind == CardioKind.HIIT_10X1 || mutation.kind == CardioKind.SIT
                val medicalGate = inputs.profile.medicalFlags.isNotEmpty() && inputs.profile.clearanceConfirmedOn == null
                if (hiit && medicalGate) errors.add("High-intensity intervals are paused until your medical clearance is confirmed (RED-03).")
                if (hiit && zone2BaseWeeks(inputs, refDate) < c.car.car05HiitBaseWeeks) errors.add("Intervals need ≥ ${c.car.car05HiitBaseWeeks} weeks of zone-2 base in your logs first (CAR-05).")
                if (week != null && mutation.weekday in 1..7) {
                    val nextDay = if (mutation.weekday == 7) 1 else mutation.weekday + 1
                    val legsNext = week.sessions.any { s -> s.weekday == nextDay && s.slots.any { it.heavy && SplitSelector.groupOf(it.pattern)?.startsWith(LEGS) == true } }
                    if (legsNext && mutation.kind != CardioKind.ZONE2 && mutation.kind != CardioKind.WALK) warnings.add("Hard cardio less than 24 h before a heavy leg session (CAR-06): the planner will use bike, rower or elliptical.")
                    val goal = mesocycle?.goal
                    if (goal == Goal.HYPERTROPHY || goal == Goal.STRENGTH) {
                        val count = week.cardio.size + 1
                        val minutes = week.cardio.sumOf { it.minutes } + mutation.minutes
                        if (count > HYPERTROPHY_CARDIO_SESSIONS || minutes > c.car.car06HypertrophyCapMinutes) warnings.add("More than ${c.car.car06HypertrophyCapSessions} sessions or ${c.car.car06HypertrophyCapMinutes} min of cardio a week can blunt strength/hypertrophy gains (CAR-06).")
                    }
                }
            }
            is PlanMutation.RemoveCardio -> if (week == null || week.cardio.none { it.weekday == mutation.weekday }) errors.add("There is no cardio on weekday ${mutation.weekday}.")
            is PlanMutation.ChangeGoal -> warnings.add(if (mutation.startNextWeek) "Applies from next week; progression states keep their loads but rep bands change." else "Applies now; progression states keep their loads but rep bands change.")
            is PlanMutation.ChangeSchedule -> {
                val days = mutation.daysPerWeek
                if (days != null && days !in 1..MAX_DAYS) errors.add("Days per week must be 1–$MAX_DAYS.")
                val minutes = mutation.sessionMinutes
                if (minutes != null && minutes !in MIN_SESSION..MAX_SESSION) errors.add("Session minutes must be $MIN_SESSION–$MAX_SESSION.")
                val wd = mutation.preferredWeekdays
                val effectiveDays = days ?: inputs.profile.daysPerWeek
                if (wd != null && wd.isNotEmpty() && wd.distinct().size != effectiveDays) errors.add("${wd.distinct().size} weekdays given for $effectiveDays training days.")
                if (wd != null && wd.any { it !in 1..7 }) errors.add("Weekdays must be 1 (Mon) … 7 (Sun).")
                if (errors.isEmpty() && days != null) {
                    val row = SplitSelector(kb).row(days, mesocycle?.goal ?: inputs.profile.goal, mesocycle?.trainingAge ?: com.gymcoach.core.model.TrainingAge.INTERMEDIATE)
                    if (row != null) {
                        val choice = SplitSelector(kb).choose(row, wd ?: emptyList())
                        if (choice.explanations.any { RuleIds.SPLIT_02 in it.ruleIds && it.inputs.containsKey("conflicts") }) warnings.add("Heavy days for the same muscles fall on consecutive days with these weekdays (SPLIT-02).")
                    }
                }
                if (mesocycle != null) warnings.add("Rebuilds the plan mid-mesocycle; progression states are kept.")
            }
            is PlanMutation.SetCheckinQuestion -> {
                if (mutation.question.isBlank() || mutation.question.length > MAX_QUESTION) errors.add("The question must be 1–$MAX_QUESTION characters.")
                if (mutation.schedule == CheckinSchedule.ONCE) {
                    val d = mutation.onDate
                    if (d == null) errors.add("A one-off question needs a date.")
                    else if (refDate != null && d.isBefore(refDate)) errors.add("The date ${d} is in the past.")
                }
                val active = inputs.overrides.count { it.revokedAt == null && it.mutation is PlanMutation.SetCheckinQuestion && (it.expiresOn == null || refDate == null || !it.expiresOn.isBefore(refDate)) }
                if (active >= MAX_CUSTOM_QUESTIONS) errors.add("$MAX_CUSTOM_QUESTIONS custom questions are already active.")
            }
            is PlanMutation.ExcludeExercise -> {
                val e = kb.exercise(mutation.exerciseId)
                if (e == null) errors.add("Unknown exercise id ${mutation.exerciseId}.")
                else if (mesocycle != null) {
                    val primaryHere = mesocycle.weeks.any { w -> w.sessions.any { s -> s.slots.any { it.exerciseId == e.id && it.role == SlotRole.PRIMARY } } }
                    if (primaryHere) {
                        val gym = gymOf(inputs, mesocycle.gymId)
                        val eq = SessionEquipment(gym)
                        val alt = kb.exerciseOrder.mapNotNull { kb.exercises[it] }.any { o -> o.id != e.id && o.pattern == e.pattern && resolver.resolve(o, eq).available && o.id !in inputs.profile.dislikedExerciseIds }
                        if (!alt) errors.add("${e.name} is a primary lift with no available substitute in ${gym.name}.")
                    }
                }
            }
            is PlanMutation.PinExercise -> {
                val e = kb.exercise(mutation.exerciseId)
                if (e == null) errors.add("Unknown exercise id ${mutation.exerciseId}.")
                else if (mesocycle != null) {
                    val gym = gymOf(inputs, mesocycle.gymId)
                    if (!resolver.resolve(e, SessionEquipment(gym)).available) errors.add("${e.name} is not available in ${gym.name}.")
                    val templates = mesocycle.weeks.flatMap { w -> w.sessions.map { it.templateId } }.distinct().filter { mutation.templateId == null || it == mutation.templateId }
                    val fits = templates.any { t -> kb.templates[t]?.slots?.any { e.pattern in it.patterns } == true }
                    if (!fits) errors.add("No slot of ${mutation.templateId ?: "the plan's templates"} takes the ${e.pattern.name.lowercase()} pattern.")
                    if (e.difficulty == Difficulty.ADVANCED && !inputs.profile.allowAdvancedExercises) warnings.add("${e.name} is an advanced exercise; advanced exercises are not enabled in your profile.")
                }
            }
            is PlanMutation.AdjustWorkingLoad -> {
                val state = inputs.progressionStates.firstOrNull { it.key.exerciseId == mutation.exerciseId && (mutation.gymScope == null || it.key.gymScope == mutation.gymScope) }
                val cur = state?.workingLoadKg
                if (state == null || state.status == ProgressionStatus.UNKNOWN || cur == null) errors.add("No working load is known for ${exName(mutation.exerciseId)} yet: log a set instead.")
                else if (mutation.newWorkingLoadKg > cur * (1.0 + MAX_LOAD_UP) + 1e-9 || mutation.newWorkingLoadKg < cur * (1.0 - MAX_LOAD_DOWN) - 1e-9) {
                    errors.add("${fmt(mutation.newWorkingLoadKg)} kg is more than +10 % / −20 % away from the current ${fmt(cur)} kg: log a set instead.")
                }
            }
            is PlanMutation.SetPriorityMuscles -> if (mutation.muscles.distinct().size > MAX_PRIORITY) errors.add("At most $MAX_PRIORITY priority muscles.")
            is PlanMutation.ScheduleDeload -> {
                if (week?.mode == WeekMode.TRAVEL_MAINTENANCE) errors.add("No deload during a travel week.")
                val ref = refDate
                val last = inputs.lastDeloadWeekStart
                if (ref != null && last != null) {
                    val start = Weeks.weekStart(ref, inputs.weekStartsOnMonday).let { if (mutation.startThisWeek) it else it.plusDays(7) }
                    val days = Weeks.daysBetween(last, start)
                    if (days < 7L * c.plat.dld03MinWeeksBetweenDeloads) {
                        val ended = Weeks.daysBetween(last.plusDays(7), ref).coerceAtLeast(0)
                        errors.add("A deload ended $ended days ago; log a week first (DLD-03, at least ${c.plat.dld03MinWeeksBetweenDeloads} weeks between deloads).")
                    }
                }
            }
            is PlanMutation.SetLimitation -> {
                val p = mutation.limitation.pain0to10
                if (p !in 0..10) errors.add("Pain must be 0–10.")
                else if (p >= RED_FLAG_PAIN || today?.readiness?.redFlagReported == true) errors.add(RED_HANDOFF)
            }
            is PlanMutation.SetTravelDays -> {
                if (mutation.from.isAfter(mutation.to)) errors.add("Travel start ${mutation.from} is after the end ${mutation.to}.")
                if (Weeks.daysBetween(mutation.from, mutation.to) + 1 > MAX_TRAVEL_DAYS) errors.add("Travel spans more than $MAX_TRAVEL_DAYS days.")
                if (refDate != null && mutation.from.isBefore(refDate)) errors.add("Travel start ${mutation.from} is in the past.")
            }
            is PlanMutation.UpdateNutritionTargets -> nutritionRow(mutation, inputs, nutrition, refDate, errors, warnings)
        }
        return if (errors.isEmpty()) ValidationResult.Valid(warnings) else ValidationResult.Invalid(errors)
    }

    private fun swap(m: PlanMutation.SwapExercise, inputs: PlanInputs, mesocycle: Mesocycle?, today: TodayContext?, errors: MutableList<String>, warnings: MutableList<String>) {
        val from = kb.exercise(m.fromExerciseId)
        val to = kb.exercise(m.toExerciseId)
        if (from == null) errors.add("Unknown exercise id ${m.fromExerciseId}.")
        if (to == null) errors.add("Unknown exercise id ${m.toExerciseId}.")
        if (from == null || to == null || mesocycle == null) return
        val gym: GymSet
        val equipment: SessionEquipment
        if (m.scope == MutationScope.TODAY && today != null) {
            gym = gymOf(inputs, today.gymId)
            equipment = SessionEquipment(gym, today.extraItems, today.removedEquipmentIds.toSet(), today.spotter)
        } else {
            gym = gymOf(inputs, mesocycle.gymId)
            equipment = SessionEquipment(gym)
        }
        val a = resolver.resolve(to, equipment)
        if (!a.available) {
            if (EquipmentResolver.RACK_OR_SPOTTER in a.missing) errors.add("${to.name} needs safeties or a spotter; toggle Spotter for today.")
            else errors.add("${to.name} is not available in ${gym.name} (missing ${a.missing.joinToString(", ")}).")
        }
        val conflict = limitationConflict(to, inputs)
        if (conflict != null) errors.add("${to.name} conflicts with your ${conflict} limitation (pain > 3/10).")
        if (!inPlan(mesocycle, from.id)) errors.add("${from.name} is not in the plan.")
        if (!SubstitutionEngine.sameFamily(from.pattern, to.pattern)) {
            val lost = from.muscles.filter { it.weight >= 1.0 && to.weightOf(it.muscle) < it.weight }
            val detail = lost.joinToString(", ") { name(it.muscle.name) + " −" + fmt(it.weight - to.weightOf(it.muscle)) + "/set" }
            warnings.add("Pattern change: ${from.pattern.name.lowercase()} → ${to.pattern.name.lowercase()}" + (if (detail.isNotEmpty()) "; weekly coverage drops for $detail." else "."))
        }
        if (to.difficulty == Difficulty.ADVANCED && !inputs.profile.allowAdvancedExercises) warnings.add("${to.name} is an advanced exercise; advanced exercises are not enabled in your profile.")
        if (a.available && to.flags.requiresSpotterOrSafeties && !a.safetiesOrSpotter) warnings.add("RIR floor 2 without safeties for ${to.name}.")
    }

    private fun nutritionRow(m: PlanMutation.UpdateNutritionTargets, inputs: PlanInputs, nutrition: NutritionTargets?, refDate: LocalDate?, errors: MutableList<String>, warnings: MutableList<String>) {
        val protein = m.proteinGPerKg
        if (protein != null && (protein < MIN_PROTEIN - 1e-9 || protein > MAX_PROTEIN + 1e-9)) errors.add("Protein ${fmt(protein)} g/kg is outside $MIN_PROTEIN–$MAX_PROTEIN g/kg.")
        val kcal = m.kcalTarget ?: return
        val weight = inputs.bodyMetrics.filter { it.weight != null }.maxByOrNull { it.date }?.weight?.kg
        if (nutrition == null || weight == null) {
            errors.add("No energy estimate yet (needs a weigh-in) — log a set of weigh-ins instead.")
            return
        }
        val p = inputs.profile
        val age = refDate?.let { p.ageOn(it) } ?: (nutrition.nextReviewDate?.let { p.ageOn(it) } ?: 0)
        val ree = 10.0 * weight + 6.25 * p.height.cm - 5.0 * age + if (p.sex == Sex.MALE) MSJ_MALE else MSJ_FEMALE
        val tdee = (nutrition.kcal - nutrition.energyDeltaKcal).toDouble()
        val floor = maxOf(ree, c.nut.nut05KcalFloorTdeeFactor * tdee)
        val ceiling = c.nut.nut06KcalCeilingTdeeFactor * tdee
        if (kcal < floor - 1e-9 || kcal > ceiling + 1e-9) errors.add("$kcal kcal is outside the NUT-05/06 bands (${Math.round(floor)}–${Math.round(ceiling)} kcal) — log a set of weigh-ins instead.")
        if (abs(kcal - nutrition.kcal) > c.nut.nut16StepKcal.hi) warnings.add("A step of ${abs(kcal - nutrition.kcal)} kcal is larger than the usual ${c.nut.nut16StepKcal.lo}–${c.nut.nut16StepKcal.hi} kcal (NUT-16).")
    }

    /** INJ-00..03: an active limitation with pain > 3 whose flag the exercise carries (the exclusion rule of §4.3). */
    fun limitationConflict(e: Exercise, inputs: PlanInputs): String? {
        for (l in inputs.profile.limitations + inputs.overrides.filter { it.revokedAt == null }.mapNotNull { (it.mutation as? PlanMutation.SetLimitation)?.limitation }) {
            if (!l.active || l.pain0to10 <= PAIN_THRESHOLD) continue
            val hit = when (l.region) {
                LimitationRegion.KNEE -> e.flags.kneeSensitive
                LimitationRegion.SHOULDER -> e.flags.shoulderSensitive
                LimitationRegion.LOW_BACK -> e.flags.lowBackSensitive || e.flags.spinalLoading
                LimitationRegion.ANKLE -> e.flags.kneeSensitive && (e.tags.contains("impact") || e.pattern == MovementPattern.POWER)
                LimitationRegion.HIP, LimitationRegion.NECK, LimitationRegion.ELBOW_WRIST, LimitationRegion.OTHER -> false
            }
            if (hit) return l.region.name.lowercase().replace('_', ' ')
        }
        return null
    }

    /** Weeks (in the recent window before `ref`) with ≥ 2 logged cardio sessions — the CAR-05 base proxy available to the planner. */
    private fun zone2BaseWeeks(inputs: PlanInputs, ref: LocalDate?): Int {
        val dates = inputs.recentSessions.associate { it.id to it.date }
        val perWeek = HashMap<LocalDate, MutableSet<String>>()
        for (s in inputs.recentSets) {
            val e = kb.exercise(s.exerciseId) ?: continue
            if (e.pattern != MovementPattern.CARDIO) continue
            val d = dates[s.sessionId] ?: s.loggedAt.atZone(ZoneOffset.UTC).toLocalDate()
            if (ref != null && d.isAfter(ref)) continue
            perWeek.getOrPut(Weeks.weekStart(d, inputs.weekStartsOnMonday)) { HashSet() }.add(s.sessionId)
        }
        return perWeek.values.count { it.size >= 2 }
    }

    private fun inPlan(m: Mesocycle?, id: String): Boolean = m?.weeks?.any { w -> w.sessions.any { s -> s.slots.any { it.exerciseId == id } } } == true

    private fun currentWeek(m: Mesocycle, date: LocalDate?): MesoWeek? {
        if (m.weeks.isEmpty()) return null
        if (date != null) m.weeks.firstOrNull { !date.isBefore(it.startDate) && date.isBefore(it.startDate.plusDays(7)) }?.let { return it }
        return m.weeks.first()
    }

    private fun gymOf(inputs: PlanInputs, id: String): GymSet =
        inputs.gyms.firstOrNull { it.id == id } ?: inputs.gyms.firstOrNull { it.id == inputs.defaultGymId } ?: checkNotNull(inputs.gyms.firstOrNull()) { "PlanInputs.gyms is empty" }

    private fun exName(id: String): String = kb.exercise(id)?.name ?: id
    private fun name(s: String): String = s.lowercase().replace('_', ' ')

    companion object {
        const val MAX_VOLUME_DELTA: Int = 6
        const val MIN_REPS: Int = 1
        const val MAX_REPS: Int = 30
        const val BARBELL_HIGH_REPS: Int = 12
        const val MIN_CARDIO: Int = 10
        const val MAX_CARDIO: Int = 90
        const val HYPERTROPHY_CARDIO_SESSIONS: Int = 3
        const val MAX_DAYS: Int = 6
        const val MIN_SESSION: Int = 20
        const val MAX_SESSION: Int = 120
        const val MAX_QUESTION: Int = 200
        const val MAX_CUSTOM_QUESTIONS: Int = 5
        const val MAX_LOAD_UP: Double = 0.10
        const val MAX_LOAD_DOWN: Double = 0.20
        const val MAX_PRIORITY: Int = 3
        const val RED_FLAG_PAIN: Int = 7
        const val PAIN_THRESHOLD: Int = 3
        const val MAX_TRAVEL_DAYS: Long = 21
        const val MIN_PROTEIN: Double = 1.0
        const val MAX_PROTEIN: Double = 3.5
        const val MSJ_MALE: Double = 5.0
        const val MSJ_FEMALE: Double = -161.0
        const val LEGS: String = "legs"
        const val RED_HANDOFF: String = "Pain of 7/10 or more, or a red-flag symptom, is not something to train around: please stop and see a physiotherapist or physician now (RED-01/RED-02). If you have chest pain, fainting, sudden shortness of breath or palpitations with symptoms, call emergency services."

        fun fmt(x: Double): String = java.math.BigDecimal(x).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
