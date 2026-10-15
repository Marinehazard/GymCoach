// core/src/main/kotlin/com/gymcoach/core/model/Nutrition.kt
@file:UseSerializers(LocalDateIsoSerializer::class, InstantIsoSerializer::class)
package com.gymcoach.core.model

import com.gymcoach.core.json.InstantIsoSerializer
import com.gymcoach.core.json.LocalDateIsoSerializer
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class TdeeAdjustment(
    val date: LocalDate,
    @SerialName("observed_rate_kg_per_week") val observedRateKgPerWeek: Double,
    @SerialName("expected_rate_kg_per_week") val expectedRateKgPerWeek: Double,
    @SerialName("delta_kcal") val deltaKcal: Int,
    val explanation: Explanation,
)

@Serializable
data class EnergyModel(
    @SerialName("ree_kcal") val reeKcal: Int,                                   // NUT-04 Mifflin–St Jeor
    @SerialName("activity_factor") val activityFactor: Double,                  // NUT-04 [weak]
    @SerialName("tdee_estimate_kcal") val tdeeEstimateKcal: Int,                // after adaptive corrections
    val adjustments: List<TdeeAdjustment> = emptyList(),
    val explanation: Explanation,
)

@Serializable
data class NutritionTargets(
    val kcal: Int,
    @SerialName("protein_g") val proteinG: Int,                                 // NUT-01
    @SerialName("carbs_g") val carbsG: Int,                                     // NUT-08
    @SerialName("fat_g") val fatG: Int,                                         // NUT-09
    @SerialName("fibre_g") val fibreG: Int,                                     // NUT-10
    @SerialName("water_l") val waterL: Double,                                  // NUT-11
    @SerialName("energy_delta_kcal") val energyDeltaKcal: Int,                  // negative = deficit
    @SerialName("goal_rate_bw_pct_per_week_lo") val goalRateLo: Double? = null,
    @SerialName("goal_rate_bw_pct_per_week_hi") val goalRateHi: Double? = null,
    @SerialName("next_review_date") val nextReviewDate: LocalDate? = null,      // NUT-16
    val explanation: Explanation,
)

@Serializable
data class WeightTrendPoint(val date: LocalDate, @SerialName("raw_kg") val rawKg: Double? = null, @SerialName("ma7_kg") val ma7Kg: Double? = null, val noise: Boolean = false)

/** User-owned nutrition settings (targets can be overridden by an UpdateNutritionTargets override). */
@Serializable
data class NutritionState(
    @SerialName("tdee_estimate_kcal") val tdeeEstimateKcal: Int? = null,
    @SerialName("kcal_target_override") val kcalTargetOverride: Int? = null,
    @SerialName("protein_g_per_kg_override") val proteinGPerKgOverride: Double? = null,
    @SerialName("last_review_date") val lastReviewDate: LocalDate? = null,
    @SerialName("deficit_started_on") val deficitStartedOn: LocalDate? = null,   // NUT-17
    @SerialName("creatine_started_on") val creatineStartedOn: LocalDate? = null, // SUP-01 water-weight note
    @SerialName("updated_at") val updatedAt: Instant,
)
