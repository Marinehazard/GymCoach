package com.gymcoach.app.ui.readiness

import com.gymcoach.app.ui.today.ScopedViewModel
import com.gymcoach.app.ui.today.TrainingDeps
import com.gymcoach.app.ui.today.uiDispatcher
import com.gymcoach.core.explain.RuleIds
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AdjustmentKind
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.ReadinessBand
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.readiness.ReadinessInput
import com.gymcoach.core.units.Units
import com.gymcoach.core.util.IdGen
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ReadinessUiState(
    val date: LocalDate, val quickMode: Boolean, val sleptBucket: Int?, val feel: Int?, val sore: Int?,
    val sleepHours: String, val sleepQuality: Int?, val soreness: Int?, val sorenessRegion: Muscle?, val stress: Int?, val energy: Int?,
    val motivation: Int?, val restingHr: String, val steps: String, val illness: Boolean, val alcohol: Int, val weightValue: String, val proteinHit: Boolean?,
    val mealsHeaviness: Int?, val preview: ReadinessCheckin?, val saved: Boolean, val error: String?,
)

/** The 1–5 / free-text fields of the full RDY-01 form (`onField`). */
enum class ReadinessField { SLEEP_QUALITY, SORENESS, STRESS, ENERGY, MOTIVATION, MEALS_HEAVINESS }

class ReadinessViewModel(
    private val date: LocalDate,
    private val deps: TrainingDeps,
    dispatcher: CoroutineDispatcher = uiDispatcher(),
) : ScopedViewModel(dispatcher) {
    private val _state = MutableStateFlow(
        ReadinessUiState(date, true, null, null, null, "", null, null, null, null, null, null, "", "", false, 0, "", null, null, null, false, null),
    )
    val state: StateFlow<ReadinessUiState> = _state
    private val _modifiers = MutableStateFlow<Explanation?>(null)
    /** "Amber 56: +1 RIR, −1 set after the first two exercises — RDY-02" (the modifiers that WILL apply), built from PlannerConstants. */
    val modifierPreview: StateFlow<Explanation?> = _modifiers
    private val _update = MutableStateFlow(false)
    /** True when a check-in for this date already exists: the button reads "Update today's check-in" (a new row; the latest wins). */
    val isUpdate: StateFlow<Boolean> = _update

    init {
        scope.launch {
            _update.value = deps.metrics.readinessFor(date) != null
            rescore()
        }
    }

    private fun edit(f: (ReadinessUiState) -> ReadinessUiState) {
        _state.value = f(_state.value)
        scope.launch { rescore() }
    }

    fun onQuickSlept(bucket: Int) = edit { it.copy(sleptBucket = bucket) }
    fun onQuickFeel(v: Int) = edit { it.copy(feel = v, energy = v, motivation = v) }
    fun onQuickSore(v: Int) = edit { it.copy(sore = v, soreness = v) }
    fun onExpand() = edit { it.copy(quickMode = false) }
    fun onSleepHours(text: String) = edit { it.copy(sleepHours = text) }
    fun onSorenessRegion(m: Muscle?) = edit { it.copy(sorenessRegion = m) }
    fun onRestingHr(text: String) = edit { it.copy(restingHr = text) }
    fun onProteinHit(v: Boolean?) = edit { it.copy(proteinHit = v) }
    fun onField(field: ReadinessField, value: Int?) = edit {
        when (field) {
            ReadinessField.SLEEP_QUALITY -> it.copy(sleepQuality = value)
            ReadinessField.SORENESS -> it.copy(soreness = value, sore = value)
            ReadinessField.STRESS -> it.copy(stress = value)
            ReadinessField.ENERGY -> it.copy(energy = value, feel = value)
            ReadinessField.MOTIVATION -> it.copy(motivation = value)
            ReadinessField.MEALS_HEAVINESS -> it.copy(mealsHeaviness = value)
        }
    }
    fun onIllness(v: Boolean) = edit { it.copy(illness = v) }
    fun onAlcohol(v: Int) = edit { it.copy(alcohol = v.coerceAtLeast(0)) }
    fun onWeight(text: String) { _state.value = _state.value.copy(weightValue = text) }
    fun onSteps(text: String) { _state.value = _state.value.copy(steps = text) }

    private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    private suspend fun input(s: ReadinessUiState): ReadinessInput {
        val prior = deps.metrics.bodyMetricsBetween(date.minusDays(7), date.minusDays(1)).filter { it.restingHr != null }
        val baseline = prior.mapNotNull { it.restingHr }.takeIf { it.isNotEmpty() }?.average()
        var elevated = 0
        if (baseline != null) {
            for (m in prior.sortedByDescending { it.date }) { if ((m.restingHr ?: 0) >= baseline + 5) elevated++ else break }
        }
        val hours = if (s.quickMode) s.sleptBucket?.let { BUCKET_HOURS.getOrNull(it) } else parse(s.sleepHours)
        return ReadinessInput(
            date = date, quickMode = s.quickMode, sleepHours = hours, sleepQuality = if (s.quickMode) null else s.sleepQuality,
            soreness = s.soreness, sorenessRegion = s.sorenessRegion, stress = if (s.quickMode) null else s.stress, energy = s.energy, motivation = s.motivation,
            restingHr = s.restingHr.trim().toIntOrNull(), restingHrBaseline7d = baseline, alcoholDrinksLastNight = s.alcohol, restingHrElevatedDays = elevated, illness = s.illness,
        )
    }

    private suspend fun rescore() {
        val s = _state.value
        val scorer = deps.readinessFactory(deps.coordinator.constants())
        val checkin = try { scorer.score(input(s), "preview", deps.clock.now()) } catch (e: IllegalArgumentException) { null }
        _state.value = _state.value.copy(preview = checkin)
        _modifiers.value = checkin?.let { modifiersFor(it) }
    }

    private suspend fun modifiersFor(c: ReadinessCheckin): Explanation {
        val k = deps.coordinator.constants().rdy
        val text = when (c.band) {
            ReadinessBand.GREEN -> "Green ${c.score}: train as planned."
            ReadinessBand.AMBER -> "Amber ${c.score}: +${k.rdy02AmberRirOffset} RIR, −1 set after the first ${k.rdy02AmberSetDropAfter} exercises."
            ReadinessBand.RED -> "Red ${c.score}: sets × ${k.rdy02RedSetFactor}, +${k.rdy02RedRirOffset} RIR — or Zone-2 + mobility instead."
        }
        return Explanation(listOf(RuleIds.RDY_02), text)
    }

    /** Saves the check-in (a new row) plus the optional morning weight, steps, RHR, nutrition chips; keeps the illness flag in today's context. */
    fun onDone() {
        val s = _state.value
        scope.launch {
            try {
                val now = deps.clock.now()
                val scorer = deps.readinessFactory(deps.coordinator.constants())
                val checkin = scorer.score(input(s), IdGen.newId(), now)
                deps.metrics.addReadiness(checkin)
                val unit = Units.weightUnitFor(deps.settings.get().unitSystem)
                val weight = parse(s.weightValue)?.let { Units.enteredWeight(it, unit) }
                val steps = s.steps.trim().toIntOrNull()
                val rhr = s.restingHr.trim().toIntOrNull()
                if (weight != null || steps != null || rhr != null) {
                    deps.metrics.addBodyMetric(BodyMetric(id = IdGen.newId(), date = date, weight = weight, restingHr = rhr, steps = steps, loggedAt = now))
                }
                if (s.proteinHit != null || s.mealsHeaviness != null || s.alcohol > 0) {
                    deps.metrics.addNutritionLog(NutritionLog(id = IdGen.newId(), date = date, proteinTargetHit = s.proteinHit, mealsHeaviness = s.mealsHeaviness,
                        alcoholDrinks = s.alcohol.takeIf { it > 0 }, loggedAt = now))
                }
                saveContextFlags(s)
                _state.value = _state.value.copy(saved = true, preview = checkin, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun saveContextFlags(s: ReadinessUiState) {
        if (!s.illness && s.alcohol == 0) return
        val settings = deps.settings.get()
        val stored = try { CoreJson.stored.decodeFromString(TodayContext.serializer(), settings.todayContextJson) } catch (e: IllegalArgumentException) { null }
        val gymId = stored?.gymId ?: settings.activeGymId.ifBlank { deps.gyms.getAll().firstOrNull { it.isDefault }?.id ?: deps.gyms.getAll().firstOrNull()?.id ?: return }
        val base = if (stored != null && stored.date == date) stored else TodayContext(date = date, gymId = gymId)
        val json = CoreJson.stored.encodeToString(TodayContext.serializer(), base.copy(illnessSymptoms = s.illness, alcoholDrinksLastNight = s.alcohol, readiness = null))
        deps.settings.update { it.copy(todayContextJson = json) }
    }

    /** RED-01 "Something feels wrong": records the flag on a new check-in and Adjustment(SAFETY, RED-01). */
    fun onSomethingWrong(itemText: String) {
        val s = _state.value
        scope.launch {
            val now = deps.clock.now()
            val scorer = deps.readinessFactory(deps.coordinator.constants())
            val checkin = scorer.score(input(s).copy(redFlagReported = true), IdGen.newId(), now)
            deps.metrics.addReadiness(checkin)
            deps.workouts.recordAdjustment(Adjustment(id = IdGen.newId(), kind = AdjustmentKind.SAFETY, ruleIds = listOf(RuleIds.RED_01),
                summary = "Reported a symptom: $itemText", beforeJson = "{}", afterJson = "{}", source = OverrideSource.USER, createdAt = now))
            _state.value = _state.value.copy(saved = true, preview = checkin)
        }
    }

    companion object {
        /** Midpoints of the "Slept" chips `< 5 · 5–6 · 6–7 · 7–7.5 · 7.5+` (§5.1). */
        val BUCKET_HOURS: List<Double> = listOf(4.5, 5.5, 6.5, 7.25, 8.0)
    }
}
