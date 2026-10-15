package com.gymcoach.core.planner

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.Difficulty
import com.gymcoach.core.model.Exercise
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.Limitation
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.MovementPattern
import com.gymcoach.core.model.MutationScope
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.mutation.OverrideResolver
import com.gymcoach.core.time.Weeks
import com.gymcoach.core.volume.MuscleTiers
import java.time.LocalDate
import java.time.ZoneOffset

/** Stage 0 (§4.3): normalized view of the inputs, reused by every later stage. Pure. */
class Normalizer(private val kb: KnowledgeBase, private val c: PlannerConstants) {
    private val agePolicy = AgePolicy(c)

    /** DEF-01 (H11): NOVICE below 6 consistent months or after a ≥ 12-month layoff retrained < 6 months; INTERMEDIATE ≤ 24 months; else ADVANCED. */
    fun trainingAgeOf(profile: Profile, @Suppress("UNUSED_PARAMETER") today: LocalDate): TrainingAge {
        val d = c.def
        return when {
            profile.trainingMonthsConsistent < d.def01NoviceMaxMonths -> TrainingAge.NOVICE
            profile.layoffMonths >= d.def01LayoffResetMonths && profile.monthsRetrainedSinceLayoff < d.def01RetrainMonthsToLeaveNovice -> TrainingAge.NOVICE
            profile.trainingMonthsConsistent <= d.def01IntermediateMaxMonths -> TrainingAge.INTERMEDIATE
            else -> TrainingAge.ADVANCED
        }
    }

    /** Profile limitations merged with active SetLimitation overrides (same id → the override wins); active ones only. */
    fun limitations(inputs: PlanInputs, today: LocalDate): List<Limitation> {
        val byId = LinkedHashMap<String, Limitation>()
        for (l in inputs.profile.limitations) byId[l.id] = l
        for (o in OverrideResolver.active(inputs.overrides, today)) {
            val m = o.mutation
            if (m is PlanMutation.SetLimitation) byId[m.limitation.id] = m.limitation
        }
        return byId.values.filter { it.active }
    }

    fun normalize(inputs: PlanInputs, today: LocalDate): Normalized {
        val profile = inputs.profile
        val notes = ArrayList<Explanation>()
        val age = profile.ageOn(today)
        val trainingAge = trainingAgeOf(profile, today)
        notes.add(
            Explanations.of(
                RuleIds.DEF_01, "Training age ${trainingAge.name.lowercase()} (${profile.trainingMonthsConsistent} months of consistent training).",
                "training_months" to profile.trainingMonthsConsistent.toString(), "layoff_months" to profile.layoffMonths.toString(),
                confidence = Confidence.WEAK,
            ),
        )
        val active = OverrideResolver.active(inputs.overrides, today)

        var goal = profile.goal
        for (o in active) {
            val m = o.mutation
            if (m is PlanMutation.ChangeGoal && goalStart(o, m) <= today) goal = m.goal
        }
        if (goal != profile.goal) notes.add(Explanations.of(listOf(RuleIds.DEF_06, RuleIds.OVERRIDE), "Goal ${goal.name.lowercase()} (changed from ${profile.goal.name.lowercase()}).", mapOf("goal" to goal.name.lowercase())))

        var days = profile.daysPerWeek
        var minutes = profile.sessionMinutes
        var weekdays = profile.preferredWeekdays
        var priority = profile.priorityMuscles
        val pinned = LinkedHashMap<String, String>()
        val swaps = LinkedHashMap<String, String>()
        val excluded = LinkedHashSet<String>()
        val penalized = LinkedHashSet<String>()
        for (id in profile.dislikedExerciseIds) if (kb.exercise(id) != null) excluded.add(kb.requireExercise(id).id)
        for (o in active) {
            when (val m = o.mutation) {
                is PlanMutation.ChangeSchedule -> {
                    m.daysPerWeek?.let { days = it }
                    m.sessionMinutes?.let { minutes = it }
                    m.preferredWeekdays?.let { weekdays = it }
                }
                is PlanMutation.SetPriorityMuscles -> priority = m.muscles
                is PlanMutation.PinExercise -> pinned[m.templateId ?: "*"] = m.exerciseId
                is PlanMutation.SwapExercise -> if (m.scope == MutationScope.PERMANENT || m.scope == MutationScope.REST_OF_MESOCYCLE) swaps[m.fromExerciseId] = m.toExerciseId
                is PlanMutation.ExcludeExercise -> excluded.add(m.exerciseId)
                is PlanMutation.ChangeVolume, is PlanMutation.ChangeRepRange, is PlanMutation.AddCardio,
                is PlanMutation.RemoveCardio, is PlanMutation.ChangeGoal, is PlanMutation.SetCheckinQuestion, is PlanMutation.AdjustWorkingLoad,
                is PlanMutation.ScheduleDeload, is PlanMutation.SetLimitation, is PlanMutation.SetTravelDays, is PlanMutation.UpdateNutritionTargets -> Unit
            }
        }
        days = days.coerceIn(1, 6)

        // Limitations (INJ-00..04, R4 §5.5).
        var elbowWrist = false
        for (lim in limitations(inputs, today)) {
            val severe = lim.pain0to10 > INJ_PAIN_THRESHOLD
            if (lim.region == LimitationRegion.ELBOW_WRIST) {
                elbowWrist = true
                notes.add(Explanations.of(RuleIds.INJ_04, "Elbow/wrist limitation: curl and pressdown weekly sets halved.", "pain" to lim.pain0to10.toString(), confidence = Confidence.WEAK))
                continue
            }
            var hits = 0
            for (id in kb.exerciseOrder) {
                val e = kb.exercises[id] ?: continue
                when (limitationEffect(lim.region, e, severe)) {
                    Effect.EXCLUDE -> { excluded.add(e.id); hits++ }
                    Effect.PENALIZE -> { penalized.add(e.id); hits++ }
                    Effect.NONE -> Unit
                }
            }
            if (hits > 0) {
                notes.add(
                    Explanations.of(
                        listOf(RuleIds.INJ_00, regionRule(lim.region)),
                        if (severe) "${regionName(lim.region)} pain ${lim.pain0to10}/10: sensitive exercises excluded or penalized." else "${regionName(lim.region)} pain ${lim.pain0to10}/10 (≤ 3): sensitive exercises penalized, form note added.",
                        mapOf("region" to lim.region.name.lowercase(), "pain" to lim.pain0to10.toString()),
                        Confidence.WEAK,
                    ),
                )
            }
        }

        // Difficulty gate (R4 §5.3).
        val weeksLogged = inputs.recentSessions.filter { it.status == SessionStatus.COMPLETED }
            .map { Weeks.weekStart(it.date, inputs.weekStartsOnMonday) }.distinct().size
        val beginnerOnly = trainingAge == TrainingAge.NOVICE && weeksLogged < BEGINNER_WEEKS
        for (id in kb.exerciseOrder) {
            val e = kb.exercises[id] ?: continue
            if (e.isCardioOrMobility) continue
            val gated = when (e.difficulty) {
                Difficulty.BEGINNER -> false
                Difficulty.INTERMEDIATE -> beginnerOnly
                Difficulty.ADVANCED -> beginnerOnly || !profile.allowAdvancedExercises
            }
            if (gated) excluded.add(e.id)
        }
        if (beginnerOnly) notes.add(Explanations.of(RuleIds.R4_5_5, "Beginner exercises only for the first $BEGINNER_WEEKS weeks of logged training.", "weeks_logged" to weeksLogged.toString(), confidence = Confidence.WEAK))

        val medicalGate = profile.medicalFlags.isNotEmpty() && profile.clearanceConfirmedOn == null
        if (medicalGate) notes.add(Explanations.of(RuleIds.RED_03, "Medical flags without clearance: no HIIT and no max-strength work until cleared.", "flags" to profile.medicalFlags.map { it.name.lowercase() }.sorted().joinToString(",")))
        val ageAllows = agePolicy.maxStrengthAllowed(age, trainingAge, profile.allowAdvancedExercises)
        if (!ageAllows) notes.add(Explanations.of(RuleIds.AGE_03, "Age $age: no max-strength zone; heavy work stays at 4–6 reps.", "age" to age.toString(), confidence = Confidence.WEAK))
        val over60 = agePolicy.over60(age)
        if (over60) notes.add(Explanations.of(RuleIds.AGE_02, "Age $age: 2–3 sets per exercise, compound reps 7–9, rest ≤ 120 s, a power slot and balance work.", "age" to age.toString(), confidence = Confidence.MODERATE))

        val tiers = MuscleTiers.assign(priority)
        notes.add(Explanations.of(listOf(RuleIds.TIER), "Tier 1 (full VOL-04 band): " + tiers.filter { it.value == com.gymcoach.core.model.MuscleTier.TIER_1 }.keys.joinToString(", ") { it.name.lowercase() } +
            "; tier 2 (0.6 × band): " + tiers.filter { it.value == com.gymcoach.core.model.MuscleTier.TIER_2 }.keys.joinToString(", ") { it.name.lowercase() } + "; tier 3 has no target.",
            mapOf("priority" to priority.joinToString(",") { it.name.lowercase() }), Confidence.WEAK))
        return Normalized(
            age = age,
            trainingAge = trainingAge,
            goal = goal,
            tiers = tiers,
            excludedExerciseIds = excluded,
            penalizedExerciseIds = penalized.filter { it !in excluded }.toCollection(LinkedHashSet()),
            elbowWristHalved = elbowWrist,
            medicalGate = medicalGate,
            beginnerOnly = beginnerOnly,
            maxStrengthAllowed = !medicalGate && ageAllows,
            over60 = over60,
            firstMesocycle = inputs.mesocyclesCompleted == 0,
            activeOverrides = active,
            pinned = pinned,
            permanentSwaps = swaps,
            sessionMinutes = minutes,
            daysPerWeek = days,
            preferredWeekdays = weekdays,
            priorityMuscles = priority,
            explanations = notes,
        )
    }

    private enum class Effect { NONE, EXCLUDE, PENALIZE }

    /** §4.3 exclusions row. */
    private fun limitationEffect(region: LimitationRegion, e: Exercise, severe: Boolean): Effect {
        val f = e.flags
        val matches: Boolean
        var hardWhenSevere = true
        when (region) {
            LimitationRegion.KNEE -> matches = f.kneeSensitive
            LimitationRegion.SHOULDER -> matches = f.shoulderSensitive
            LimitationRegion.LOW_BACK -> matches = f.lowBackSensitive || f.spinalLoading
            LimitationRegion.HIP -> {
                matches = f.kneeSensitive && (e.pattern == MovementPattern.SQUAT || e.pattern == MovementPattern.LUNGE_SPLIT)
                hardWhenSevere = false
            }
            LimitationRegion.ANKLE -> {
                matches = f.kneeSensitive
                hardWhenSevere = e.tags.contains(IMPACT_TAG) || e.id in IMPACT_IDS
            }
            LimitationRegion.NECK -> {
                matches = f.spinalLoading
                hardWhenSevere = false
            }
            LimitationRegion.ELBOW_WRIST, LimitationRegion.OTHER -> matches = false
        }
        if (!matches) return Effect.NONE
        return if (severe && hardWhenSevere) Effect.EXCLUDE else Effect.PENALIZE
    }

    private fun regionRule(region: LimitationRegion): String = when (region) {
        LimitationRegion.KNEE -> RuleIds.INJ_01
        LimitationRegion.SHOULDER -> RuleIds.INJ_02
        LimitationRegion.LOW_BACK -> RuleIds.INJ_03
        LimitationRegion.ELBOW_WRIST -> RuleIds.INJ_04
        LimitationRegion.HIP, LimitationRegion.ANKLE, LimitationRegion.NECK, LimitationRegion.OTHER -> RuleIds.INJ_00
    }

    private fun regionName(region: LimitationRegion): String = region.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    companion object {
        /** INJ-00: pain ≤ 3/10 acceptable (penalty + note); > 3 → exclude. */
        const val INJ_PAIN_THRESHOLD: Int = 3
        /** R4 §5.3: a NOVICE stays on BEGINNER exercises until 8 weeks of training are logged. */
        const val BEGINNER_WEEKS: Int = 8
        const val IMPACT_TAG: String = "impact"
        /** Impact exercises (jumping/running) for the ankle rule when the catalogue carries no `impact` tag. */
        val IMPACT_IDS: Set<String> = setOf("box_jump", "jump_rope", "outdoor_run_walk", "treadmill")

        /** ChangeGoal applies from the next week boundary after it was created when startNextWeek, else from its creation day. */
        fun goalStart(o: PlanOverride, m: PlanMutation.ChangeGoal): LocalDate {
            val created = o.createdAt.atZone(ZoneOffset.UTC).toLocalDate()
            return if (m.startNextWeek) Weeks.weekStart(created).plusDays(7) else created
        }
    }
}
