// core/src/main/kotlin/com/gymcoach/core/nutrition/MacroTargets.kt
package com.gymcoach.core.nutrition

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.EnergyModel
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import java.time.LocalDate

/**
 * §5.5 targets (NUT-01/05/06/08/09/10/11, AGE-01) with the binding floor precedence: when protein + fat floor + carb
 * floor exceed the energy target, the target is raised to their sum (never negative carbs, never a silently broken floor).
 */
class MacroTargets(private val c: PlannerConstants) {

    fun compute(profile: Profile, weightKg: Double, age: Int, goal: Goal, trainingAge: TrainingAge, energy: EnergyModel, state: NutritionState?, cardioSessionsPerWeek: Int, today: LocalDate): NutritionTargets {
        val tdee = energy.tdeeEstimateKcal
        val ree = energy.reeKcal
        val notes = ArrayList<String>()
        val ids = linkedSetOf(RuleIds.NUT_01, RuleIds.NUT_04)
        val inputs = LinkedHashMap<String, String>()
        inputs["tdee_kcal"] = tdee.toString()
        inputs["ree_kcal"] = ree.toString()
        inputs["weight_kg"] = weightKg.toString()

        // Energy target (NUT-05 / NUT-06).
        val lean = isLean(profile)
        var rateLo: Double? = null
        var rateHi: Double? = null
        var kcal: Int = when (goal) {
            Goal.FAT_LOSS -> {
                ids += RuleIds.NUT_05
                val deficit = minOf(c.nut.nut05DeficitKcal.hi, Math.round(c.nut.nut05DeficitPctOfTdee * tdee).toInt())
                inputs["deficit_kcal"] = deficit.toString()
                rateLo = -(if (lean) c.nut.nut05LeanRatePctHi else c.nut.nut05RatePctHi)
                rateHi = -c.nut.nut05RatePctLo
                maxOf(tdee - deficit, ree)
            }
            Goal.HYPERTROPHY, Goal.STRENGTH -> {
                ids += RuleIds.NUT_06
                if (trainingAge == TrainingAge.ADVANCED) {
                    rateLo = ADVANCED_RATE_LO; rateHi = ADVANCED_RATE_HI
                    tdee + ADVANCED_SURPLUS_KCAL
                } else {
                    rateLo = c.nut.nut06RatePctLo; rateHi = c.nut.nut06RatePctHi
                    Math.round(tdee * (1.0 + c.nut.nut06SurplusPct.lo / 100.0)).toInt()
                }
            }
            Goal.GENERAL_FITNESS, Goal.ENDURANCE_SUPPORT -> tdee
        }
        // NUT-07: recomposition offered (never imposed) for a hypertrophy goal when novice, back from a ≥ 3-month layoff, or
        // above ≈ 20 % (men) / 30 % (women) body fat.
        if (goal == Goal.HYPERTROPHY && recompositionCase(profile, trainingAge)) {
            ids += RuleIds.NUT_07
            notes += "recomposition (maintenance to −200 kcal, protein ≥ 2.2 g/kg) is an option"
        }
        val override = state?.kcalTargetOverride
        if (override != null) { kcal = override; notes += "user-set energy target" }

        // Protein (NUT-01, AGE-01).
        val bf = profile.bodyFatPct
        val proteinBasisKg: Double
        var gPerKg: Double
        if (state?.proteinGPerKgOverride != null) {
            gPerKg = state.proteinGPerKgOverride
            proteinBasisKg = weightKg
            notes += "user-set protein"
        } else {
            when (goal) {
                Goal.FAT_LOSS -> if (bf != null) {
                    gPerKg = FAT_LOSS_FFM_G_PER_KG
                    proteinBasisKg = weightKg * (1.0 - bf / 100.0)
                    notes += "protein per kg fat-free mass"
                } else {
                    gPerKg = FAT_LOSS_BW_G_PER_KG
                    proteinBasisKg = weightKg
                }
                Goal.HYPERTROPHY, Goal.STRENGTH -> { gPerKg = c.nut.nut01DefaultGPerKg; proteinBasisKg = weightKg }
                Goal.GENERAL_FITNESS, Goal.ENDURANCE_SUPPORT -> { gPerKg = GENERAL_G_PER_KG; proteinBasisKg = weightKg }
            }
            if (age >= c.age.age01Threshold && proteinBasisKg == weightKg && gPerKg < c.age.age01ProteinGPerKg) {
                gPerKg = c.age.age01ProteinGPerKg
                ids += RuleIds.AGE_01
            }
            if (age >= SENIOR_AGE && gPerKg < SENIOR_FLOOR_G_PER_KG) gPerKg = SENIOR_FLOOR_G_PER_KG
        }
        val proteinG = gPerKg * proteinBasisKg
        val proteinKcal = proteinG * 4.0
        inputs["protein_g_per_kg"] = gPerKg.toString()

        // Fat (NUT-09) and carbs (NUT-08) with the floor precedence.
        ids += RuleIds.NUT_08
        ids += RuleIds.NUT_09
        val fatFloorG = c.nut.nut09FatGPerKgFloor * weightKg
        val carbFloorG = c.nut.nut08CarbFloorGPerKg * weightKg
        var fatG = c.nut.nut09FatGPerKgDefault * weightKg
        var carbsG = (kcal - proteinKcal - fatG * 9.0) / 4.0
        var floorsBind = false
        if (carbsG < carbFloorG - EPS) {
            // NUT-08: cut fat towards its floor before carbs drop below 3 g/kg.
            fatG = maxOf(fatFloorG, (kcal - proteinKcal - carbFloorG * 4.0) / 9.0)
            carbsG = (kcal - proteinKcal - fatG * 9.0) / 4.0
            if (carbsG < carbFloorG - EPS) {
                val floorKcal = proteinKcal + fatFloorG * 9.0 + carbFloorG * 4.0
                kcal = Math.round(floorKcal).toInt()
                fatG = fatFloorG
                carbsG = carbFloorG
                floorsBind = true
            }
        }
        val delta = kcal - tdee
        if (floorsBind) {
            notes += "floors bind; deficit reduced to ${maxOf(0, -delta)} kcal — the deficit comes from activity (steps) instead"
        }
        val carbBand = when {
            goal == Goal.ENDURANCE_SUPPORT -> ENDURANCE_CARB_G_PER_KG
            cardioSessionsPerWeek >= CARDIO_HIGH_SESSIONS -> CARDIO_CARB_G_PER_KG
            else -> null
        }
        if (carbBand != null && carbsG < carbBand * weightKg - EPS) notes += "with this much cardio aim for about ${carbBand.toInt()} g/kg carbohydrate"

        // Fibre (NUT-10), water (NUT-11).
        ids += RuleIds.NUT_10
        ids += RuleIds.NUT_11
        val fibreFloor = if (profile.sex == Sex.MALE) FIBRE_FLOOR_MEN else FIBRE_FLOOR_WOMEN
        val fibre = maxOf(Math.round(FIBRE_G_PER_1000 * kcal / 1000.0).toInt(), fibreFloor)
        val water = if (profile.sex == Sex.MALE) WATER_MEN_L else WATER_WOMEN_L

        val proteinRounded = Math.round(proteinG).toInt()
        val fatRounded = Math.round(fatG).toInt()
        val carbsRounded = Math.round(carbsG).toInt()
        inputs["kcal"] = kcal.toString()
        inputs["energy_delta_kcal"] = delta.toString()
        val message = buildString {
            append("$kcal kcal (")
            append(if (delta >= 0) "+" else "")
            append("$delta vs maintenance $tdee); protein $proteinRounded g, carbs $carbsRounded g, fat $fatRounded g, fibre $fibre g, water $water L")
            if (notes.isNotEmpty()) append(" — ").append(notes.joinToString("; "))
        }
        val explanationIds = if (floorsBind) listOf(RuleIds.NUT_08) + ids.filter { it != RuleIds.NUT_08 } else ids.toList()
        return NutritionTargets(
            kcal = kcal,
            proteinG = proteinRounded,
            carbsG = carbsRounded,
            fatG = fatRounded,
            fibreG = fibre,
            waterL = water,
            energyDeltaKcal = delta,
            goalRateLo = rateLo,
            goalRateHi = rateHi,
            nextReviewDate = (state?.lastReviewDate ?: today).plusDays(c.nut.nut04WindowDays.toLong()),
            explanation = Explanations.of(explanationIds, message, inputs, Confidence.MODERATE),
        )
    }

    /**
     * NUT-16/NUT-05 14-day review. Null when no rate is known, the review is not yet due, or the goal has no rate band.
     * Loss faster than nut05MaxRatePctPerWeek → +nut05FastLossStepKcal and the supervision sentence; outside the band →
     * a ±(midpoint of nut16StepKcal) kcal proposal; inside → (null, "on track").
     */
    fun review(targets: NutritionTargets, observedRatePctPerWeek: Double?, today: LocalDate): Pair<PlanMutation?, Explanation>? {
        val observed = observedRatePctPerWeek ?: return null
        val due = targets.nextReviewDate
        if (due != null && today.isBefore(due)) return null
        val inputs = mapOf(
            "observed_pct_per_week" to fmt(observed),
            "band_lo" to (targets.goalRateLo?.let { fmt(it) } ?: ""),
            "band_hi" to (targets.goalRateHi?.let { fmt(it) } ?: ""),
            "kcal" to targets.kcal.toString(),
        )
        if (observed < -c.nut.nut05MaxRatePctPerWeek) {
            val step = c.nut.nut05FastLossStepKcal
            val kcal = targets.kcal + step
            val text = "Losing faster than ${fmt(c.nut.nut05MaxRatePctPerWeek)} % per week (${fmt(observed)} %/week) — consider professional supervision; energy target +$step kcal → $kcal kcal"
            return PlanMutation.UpdateNutritionTargets(kcalTarget = kcal, rationale = text) to
                Explanations.of(listOf(RuleIds.NUT_05, RuleIds.NUT_16), text, inputs + ("step_kcal" to step.toString()), Confidence.MODERATE)
        }
        val lo = targets.goalRateLo ?: return null
        val hi = targets.goalRateHi ?: return null
        val step = (c.nut.nut16StepKcal.lo + c.nut.nut16StepKcal.hi) / 2
        return when {
            observed > hi + EPS -> {
                val kcal = targets.kcal - step
                val text = "Weight trend ${fmt(observed)} %/week is above the goal band ${fmt(lo)}…${fmt(hi)} %/week: energy target −$step kcal → $kcal kcal; next review in ${c.nut.nut04WindowDays} days"
                PlanMutation.UpdateNutritionTargets(kcalTarget = kcal, rationale = text) to
                    Explanations.of(listOf(RuleIds.NUT_16), text, inputs + ("step_kcal" to (-step).toString()), Confidence.MODERATE)
            }
            observed < lo - EPS -> {
                val kcal = targets.kcal + step
                val text = "Weight trend ${fmt(observed)} %/week is below the goal band ${fmt(lo)}…${fmt(hi)} %/week: energy target +$step kcal → $kcal kcal; next review in ${c.nut.nut04WindowDays} days"
                PlanMutation.UpdateNutritionTargets(kcalTarget = kcal, rationale = text) to
                    Explanations.of(listOf(RuleIds.NUT_16), text, inputs + ("step_kcal" to step.toString()), Confidence.MODERATE)
            }
            else -> null to Explanations.of(
                listOf(RuleIds.NUT_16),
                "Weight trend ${fmt(observed)} %/week is inside the goal band ${fmt(lo)}…${fmt(hi)} %/week: targets unchanged",
                inputs, Confidence.MODERATE,
            )
        }
    }

    private fun recompositionCase(profile: Profile, trainingAge: TrainingAge): Boolean {
        val returning = profile.layoffMonths >= RECOMP_LAYOFF_MONTHS && profile.monthsRetrainedSinceLayoff < RECOMP_LAYOFF_MONTHS
        if (trainingAge == TrainingAge.NOVICE || returning) return true
        val bf = profile.bodyFatPct ?: return false
        return when (profile.sex) {
            Sex.MALE -> bf > RECOMP_BF_MEN
            Sex.FEMALE -> bf > RECOMP_BF_WOMEN
        }
    }

    /** Leanness gate by the user's body-fat estimate: men ≤ 15 %, women ≤ 25 %; unknown → not lean. */
    private fun isLean(profile: Profile): Boolean {
        val bf = profile.bodyFatPct ?: return false
        return when (profile.sex) {
            Sex.MALE -> bf <= 15.0
            Sex.FEMALE -> bf <= 25.0
        }
    }

    private fun fmt(x: Double): String = String.format(java.util.Locale.ROOT, "%.2f", x)

    private companion object {
        const val EPS: Double = 1e-6
        const val ADVANCED_SURPLUS_KCAL: Int = 250          // NUT-06 advanced +200–300 kcal (midpoint)
        const val ADVANCED_RATE_LO: Double = 0.1
        const val ADVANCED_RATE_HI: Double = 0.25
        const val FAT_LOSS_BW_G_PER_KG: Double = 2.4        // NUT-01 deficit default per kg body weight
        const val FAT_LOSS_FFM_G_PER_KG: Double = 2.7       // NUT-01 2.3–3.1 g/kg FFM (midpoint)
        const val GENERAL_G_PER_KG: Double = 1.6            // NUT-01 1.4–2.0 (inside the band)
        const val SENIOR_AGE: Int = 65
        const val SENIOR_FLOOR_G_PER_KG: Double = 1.2
        const val CARDIO_HIGH_SESSIONS: Int = 4
        const val CARDIO_CARB_G_PER_KG: Double = 5.0
        const val ENDURANCE_CARB_G_PER_KG: Double = 6.0
        const val FIBRE_G_PER_1000: Double = 14.0
        const val FIBRE_FLOOR_MEN: Int = 38
        const val FIBRE_FLOOR_WOMEN: Int = 25
        const val WATER_MEN_L: Double = 3.7
        const val WATER_WOMEN_L: Double = 2.7
        const val RECOMP_LAYOFF_MONTHS: Int = 3             // NUT-07 returning from ≥ 3 months layoff
        const val RECOMP_BF_MEN: Double = 20.0              // NUT-07 ≈ 20 % (men)
        const val RECOMP_BF_WOMEN: Double = 30.0            // NUT-07 ≈ 30 % (women)
    }
}
