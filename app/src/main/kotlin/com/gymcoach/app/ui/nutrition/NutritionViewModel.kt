package com.gymcoach.app.ui.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.app.ui.components.parseDecimal
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.EnergyModel
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.NutritionTip
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.SupplementAdvice
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightTrendPoint
import com.gymcoach.core.nutrition.EnergyEstimator
import com.gymcoach.core.nutrition.MacroTargets
import com.gymcoach.core.nutrition.SupplementAdvisor
import com.gymcoach.core.nutrition.WeightTrend
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.repo.MetricsRepository
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.units.Units
import com.gymcoach.core.util.IdGen
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** §8.16 state. */
data class NutritionUiState(
    val energy: EnergyModel?,
    val targets: NutritionTargets?,
    val trend: List<WeightTrendPoint>,
    val todayLog: NutritionLog?,
    val weightValue: String,
    val stepsValue: String,
    val kcalValue: String,
    val proteinDays7: Int,
    val reviewCard: Explanation?,
    val supplements: List<SupplementAdvice>,
    val medicalGate: Boolean,
    val tips: List<NutritionTip>,
    val activityFactor: Double,
    val bodyFatPct: String,
)

/** Display-only extras beside [NutritionUiState]. */
data class NutritionExtras(val loading: Boolean, val unit: UnitSystem, val weighIns: Int, val noProfile: Boolean, val error: String?)

class NutritionViewModel(
    private val profiles: ProfileRepository,
    private val metrics: MetricsRepository,
    private val plans: PlanRepository,
    private val settings: SettingsRepository,
    private val knowledge: KnowledgeBase,
    private val clock: Clock,
    private val constants: suspend () -> PlannerConstants,
    private val energyOf: (PlannerConstants) -> EnergyEstimator,
    private val macrosOf: (PlannerConstants) -> MacroTargets,
    private val trendOf: (PlannerConstants) -> WeightTrend,
    private val supplementsOf: (PlannerConstants) -> SupplementAdvisor,
    private val trainingAgeOf: suspend (Profile, LocalDate) -> TrainingAge,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(
        NutritionUiState(null, null, emptyList(), null, "", "", "", 0, null, emptyList(), false, knowledge.nutritionTips, 1.55, ""),
    )
    val state: StateFlow<NutritionUiState> = _state.asStateFlow()
    private val _extras = MutableStateFlow(NutritionExtras(true, UnitSystem.METRIC, 0, false, null))
    val extras: StateFlow<NutritionExtras> = _extras.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        try {
            val today = clock.today()
            val unit = settings.get().unitSystem
            val profile = profiles.get()
            if (profile == null) {
                _extras.value = NutritionExtras(false, unit, 0, true, null)
                return
            }
            val c = constants()
            val points = metrics.bodyMetricsBetween(today.minusDays(84), today)
            val trend = trendOf(c).series(points)
            val lastWeight = points.lastOrNull { it.weight != null }?.weight
            val ma7 = trend.lastOrNull { it.ma7Kg != null }?.ma7Kg
            val weightKg = lastWeight?.kg ?: ma7
            val steps = points.filter { it.date >= today.minusDays(13) }.mapNotNull { it.steps }
            val stepsMean = if (steps.isEmpty()) null else steps.average().toInt()
            val nutritionState = plans.nutritionState()
            val age = profile.ageOn(today)
            val gate = profile.medicalFlags.isNotEmpty() && profile.clearanceConfirmedOn == null
            var energy: EnergyModel? = null
            var targets: NutritionTargets? = null
            var review: Explanation? = null
            if (weightKg != null) {
                energy = energyOf(c).model(profile, weightKg, age, profile.daysPerWeek, stepsMean, nutritionState)
                val cardio = plans.getCurrent()?.weeks?.firstOrNull { !it.startDate.isAfter(today) && it.startDate.plusDays(6) >= today }?.cardio?.size ?: 0
                val trainingAge = trainingAgeOf(profile, today)
                targets = macrosOf(c).compute(profile, weightKg, age, profile.goal, trainingAge, energy, nutritionState, cardio, today)
                review = macrosOf(c).review(targets, trendOf(c).ratePctPerWeek(trend), today)?.second
            }
            val logs = metrics.nutritionBetween(today.minusDays(6), today)
            val todayLog = logs.lastOrNull { it.date == today }
            val proteinTarget: Int? = targets?.proteinG
            val proteinDays = logs.groupBy { it.date }.count { (_, day) -> day.any { it.proteinTargetHit == true || (proteinTarget != null && (it.proteinG ?: 0) >= proteinTarget) } }
            val supplements = if (weightKg != null) supplementsOf(c).advise(profile, weightKg, profile.goal, gate, null, 0, false) else emptyList()
            val todayMetric = points.lastOrNull { it.date == today }
            _state.update {
                it.copy(
                    energy = energy, targets = targets, trend = trend, todayLog = todayLog,
                    weightValue = todayMetric?.weight?.let { w -> Units.trim(if (unit == UnitSystem.METRIC) w.kg else Units.kgToLb(w.kg)) } ?: "",
                    stepsValue = todayMetric?.steps?.toString() ?: "", kcalValue = todayLog?.kcal?.toString() ?: "",
                    proteinDays7 = proteinDays, reviewCard = review, supplements = supplements, medicalGate = gate,
                    activityFactor = energy?.activityFactor ?: profile.activityFactorOverride ?: 1.55,
                    bodyFatPct = profile.bodyFatPct?.let { v -> Units.trim(v) } ?: "",
                )
            }
            _extras.value = NutritionExtras(false, unit, points.count { it.weight != null }, false, null)
        } catch (e: Exception) {
            _extras.update { it.copy(loading = false, error = e.message ?: ERROR_LOAD) }
        }
    }

    fun onWeightValue(v: String) = _state.update { it.copy(weightValue = v) }
    fun onStepsValue(v: String) = _state.update { it.copy(stepsValue = v) }
    fun onKcalValue(v: String) = _state.update { it.copy(kcalValue = v) }

    /** Weight (typed value kept, HR-20) and steps for the date go into ONE BodyMetric. */
    fun onWeighIn(weight: String, steps: String) {
        scope.launch {
            try {
                val today = clock.today()
                val unit = Units.weightUnitFor(_extras.value.unit)
                val w = parseDecimal(weight)?.takeIf { it > 0.0 }?.let { Units.enteredWeight(it, unit) }
                val s = steps.trim().toIntOrNull()?.takeIf { it >= 0 }
                if (w == null && s == null) return@launch
                val previous = metrics.bodyMetricsBetween(today, today).lastOrNull()
                metrics.addBodyMetric(
                    BodyMetric(
                        id = IdGen.newId(), date = today, weight = w ?: previous?.weight, waistCm = previous?.waistCm,
                        restingHr = previous?.restingHr, steps = s ?: previous?.steps, note = previous?.note ?: "", loggedAt = clock.now(),
                    ),
                )
                refresh()
            } catch (e: Exception) {
                _extras.update { it.copy(error = e.message ?: ERROR_SAVE) }
            }
        }
    }

    /** Daily ordinal inputs (NUT-14): every call writes the merged row for today; null leaves a field as it was. */
    fun onLog(
        kcal: String? = null, proteinG: String? = null, proteinHit: Boolean? = null, meals: Int? = null,
        alcohol: Int? = null, travelDay: Boolean? = null,
    ) {
        scope.launch {
            try {
                val today = clock.today()
                val prev = metrics.nutritionBetween(today, today).lastOrNull()
                metrics.addNutritionLog(
                    NutritionLog(
                        id = IdGen.newId(), date = today,
                        kcal = kcal?.trim()?.toIntOrNull() ?: prev?.kcal,
                        proteinG = proteinG?.trim()?.toIntOrNull() ?: prev?.proteinG,
                        proteinTargetHit = proteinHit ?: prev?.proteinTargetHit,
                        mealsHeaviness = meals ?: prev?.mealsHeaviness,
                        alcoholDrinks = alcohol ?: prev?.alcoholDrinks,
                        travelDay = travelDay ?: prev?.travelDay ?: false,
                        note = prev?.note ?: "", loggedAt = clock.now(),
                    ),
                )
                refresh()
            } catch (e: Exception) {
                _extras.update { it.copy(error = e.message ?: ERROR_SAVE) }
            }
        }
    }

    fun onBodyFat(text: String) {
        _state.update { it.copy(bodyFatPct = text) }
        scope.launch {
            val p = profiles.get() ?: return@launch
            val v = parseDecimal(text)?.coerceIn(3.0, 60.0)
            if (text.isNotBlank() && v == null) return@launch
            profiles.save(p.copy(bodyFatPct = v, updatedAt = clock.now()))
            refresh()
        }
    }

    fun onActivityFactor(factor: Double?) {
        scope.launch {
            val p = profiles.get() ?: return@launch
            profiles.save(p.copy(activityFactorOverride = factor, updatedAt = clock.now()))
            refresh()
        }
    }

    companion object {
        const val ERROR_LOAD = "Could not load the nutrition data."
        const val ERROR_SAVE = "Could not save."
    }
}
