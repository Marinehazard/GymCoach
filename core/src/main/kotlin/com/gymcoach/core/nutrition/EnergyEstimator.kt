// core/src/main/kotlin/com/gymcoach/core/nutrition/EnergyEstimator.kt
package com.gymcoach.core.nutrition

import com.gymcoach.core.explain.Explanations
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.EnergyModel
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.Sex
import com.gymcoach.core.planner.PlannerConstants

/**
 * NUT-04: Mifflin–St Jeor REE × an activity factor ("starting guess"); a stored adaptive TDEE (NutritionState,
 * written after TdeeAdapter reviews) replaces the factor estimate.
 */
class EnergyEstimator(private val c: PlannerConstants) {

    fun model(profile: Profile, weightKg: Double, age: Int, daysPerWeek: Int, stepsMean14d: Int?, state: NutritionState?): EnergyModel {
        val ree = Math.round(ree(profile.sex, weightKg, profile.height.cm, age)).toInt()
        val factor = profile.activityFactorOverride ?: activityFactor(daysPerWeek, stepsMean14d, profile.physicalJob)
        val estimate = Math.round(ree * factor).toInt()
        val adaptive = state?.tdeeEstimateKcal
        val tdee = adaptive ?: estimate
        val source = when {
            adaptive != null -> "adaptive estimate from your logs"
            profile.activityFactorOverride != null -> "your activity factor"
            else -> "starting guess (NUT-04)"
        }
        return EnergyModel(
            reeKcal = ree,
            activityFactor = factor,
            tdeeEstimateKcal = tdee,
            explanation = Explanations.of(
                listOf(RuleIds.NUT_04),
                "Resting energy $ree kcal (Mifflin–St Jeor) × activity ${factor} = $estimate kcal; maintenance $tdee kcal — $source",
                mapOf(
                    "weight_kg" to weightKg.toString(), "height_cm" to profile.height.cm.toString(), "age" to age.toString(),
                    "sex" to profile.sex.name.lowercase(), "days_per_week" to daysPerWeek.toString(),
                    "steps_mean_14d" to (stepsMean14d?.toString() ?: ""), "physical_job" to profile.physicalJob.toString(),
                    "factor_estimate_kcal" to estimate.toString(),
                ),
                Confidence.WEAK,
            ),
        )
    }

    /** Mifflin–St Jeor: men 10W + 6.25H − 5A + 5, women 10W + 6.25H − 5A − 161. */
    fun ree(sex: Sex, weightKg: Double, heightCm: Double, age: Int): Double {
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
        return when (sex) {
            Sex.MALE -> base + 5.0
            Sex.FEMALE -> base - 161.0
        }
    }

    /**
     * §5.5 table as the maximum of three readings (each [weak]): sessions/week (≤ 1 → 1.2, 2–3 → 1.375, 4–5 → 1.55,
     * ≥ 6 → 1.725), the 14-day step mean (< 5k → 1.2, 5–8k → 1.375, ≥ 8k → 1.55; unknown → 1.2) and a physical job
     * (1.55, or 1.725 together with training). 1.9 is user-set only.
     */
    fun activityFactor(daysPerWeek: Int, stepsMean14d: Int?, physicalJob: Boolean): Double {
        val f = c.nut.nut04ActivityFactors
        val bySessions = when {
            daysPerWeek >= 6 -> 3
            daysPerWeek >= 4 -> 2
            daysPerWeek >= 2 -> 1
            else -> 0
        }
        val bySteps = when {
            stepsMean14d == null -> 0
            stepsMean14d >= 8000 -> 2
            stepsMean14d >= 5000 -> 1
            else -> 0
        }
        val byJob = if (!physicalJob) 0 else if (daysPerWeek >= 1) 3 else 2
        val idx = maxOf(bySessions, bySteps, byJob).coerceAtMost(f.size - 1)
        return f[idx]
    }
}
