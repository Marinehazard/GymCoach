package com.gymcoach.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.app.ui.components.parseDecimal
import com.gymcoach.app.ui.onboarding.BuiltDay
import com.gymcoach.app.ui.onboarding.BuiltPlan
import com.gymcoach.app.ui.onboarding.ProfileDraft
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.LimitationRegion
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.repo.MetricsRepository
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.units.Units
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** §8.14 state. */
data class ProfileUiState(
    val profile: Profile?,
    val draft: ProfileDraft,
    val trainingAge: TrainingAge?,
    val dirty: Boolean,
    val planAffected: Boolean,
    val saving: Boolean,
    val error: String?,
)

/**
 * §8.14. Every onboarding field is editable; save shows the derived training age and "Plan affected - regenerate?".
 * [trainingAgeOf] is `container.planner(constants).trainingAgeOf` and [recompute] is `planCoordinator.recompute`.
 */
class ProfileViewModel(
    private val profiles: ProfileRepository,
    private val metrics: MetricsRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val trainingAgeOf: suspend (Profile, LocalDate) -> TrainingAge,
    private val recompute: suspend (String) -> Mesocycle,
    scopeOverride: CoroutineScope? = null,
    private val computeContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(ProfileUiState(null, ProfileDraft(), null, dirty = false, planAffected = false, saving = false, error = null))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val p = profiles.get() ?: return
        val unit = settings.get().unitSystem
        val today = clock.today()
        val weight = metrics.bodyMetricsBetween(today.minusDays(365), today).lastOrNull { it.weight != null }?.weight?.kg
        _state.update {
            it.copy(profile = p, draft = DraftRules.fromProfile(p, unit, weight), trainingAge = trainingAgeOf(p, today), dirty = false)
        }
    }

    fun onDraftChange(draft: ProfileDraft) {
        _state.update { it.copy(draft = draft, dirty = true, error = null) }
    }

    fun onSave() {
        val s = _state.value
        if (s.saving) return
        if (!DraftRules.heightOk(s.draft) || !DraftRules.birthYearOk(s.draft)) {
            _state.update { it.copy(error = ERROR_RANGE) }
            return
        }
        scope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val now = clock.now()
                val today = clock.today()
                val next = DraftRules.toProfile(s.draft, s.profile, now, today)
                profiles.save(next)
                settings.update { it.copy(unitSystem = s.draft.unitSystem) }
                val affected = s.profile == null || planFieldsDiffer(s.profile, next)
                _state.update {
                    it.copy(
                        profile = next, trainingAge = trainingAgeOf(next, today), dirty = false, saving = false,
                        planAffected = it.planAffected || affected,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: ERROR_SAVE) }
            }
        }
    }

    /** RED-01 region block: the limitation of that region stays on record but no longer blocks advice. */
    fun onClearRedFlagRegion(region: LimitationRegion) {
        val s = _state.value
        val p = s.profile ?: return
        scope.launch {
            val cleared = p.limitations.map { if (it.region == region) it.copy(active = false) else it }
            val next = p.copy(limitations = cleared, updatedAt = clock.now())
            profiles.save(next)
            _state.update { it.copy(profile = next, draft = it.draft.copy(limitations = cleared), planAffected = true) }
        }
    }

    fun onRegenerate() {
        scope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                withContext(computeContext) { recompute("profile") }
                _state.update { it.copy(saving = false, planAffected = false) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: ERROR_SAVE) }
            }
        }
    }

    private fun planFieldsDiffer(a: Profile, b: Profile): Boolean =
        a.copy(updatedAt = b.updatedAt, height = b.height, wakeTimeLocal = b.wakeTimeLocal, bedtimeLocal = b.bedtimeLocal,
            usualSessionTime = b.usualSessionTime, hasHrMonitor = b.hasHrMonitor, measuredHrMax = b.measuredHrMax,
            bodyFatPct = b.bodyFatPct, physicalJob = b.physicalJob) != b

    companion object {
        const val ERROR_RANGE = "Check height (120–230 cm) and birth year (1920–2015)."
        const val ERROR_SAVE = "Saving failed."
    }
}

/** Pure validation / conversion helpers (JVM-tested). */
object DraftRules {
    const val HEIGHT_CM_MIN = 120.0
    const val HEIGHT_CM_MAX = 230.0
    const val WEIGHT_KG_MIN = 30.0
    const val WEIGHT_KG_MAX = 250.0
    const val BIRTH_YEAR_MIN = 1920
    const val BIRTH_YEAR_MAX = 2015
    val MAIN_LIFTS: List<String> = listOf("back_squat", "barbell_bench_press", "conventional_deadlift", "overhead_press_barbell", "barbell_row", "lat_pulldown")

    fun heightCm(d: ProfileDraft): Double? {
        val v = parseDecimal(d.heightValue) ?: return null
        return if (d.unitSystem == UnitSystem.METRIC) v else Units.inToCm(v)
    }

    fun weightKg(d: ProfileDraft): Double? {
        val v = parseDecimal(d.weightValue) ?: return null
        return if (d.unitSystem == UnitSystem.METRIC) v else Units.lbToKg(v)
    }

    fun heightOk(d: ProfileDraft): Boolean = heightCm(d)?.let { it in HEIGHT_CM_MIN..HEIGHT_CM_MAX } ?: false
    fun weightOk(d: ProfileDraft): Boolean = weightKg(d)?.let { it in WEIGHT_KG_MIN..WEIGHT_KG_MAX } ?: false
    fun birthYearOk(d: ProfileDraft): Boolean = d.birthYear in BIRTH_YEAR_MIN..BIRTH_YEAR_MAX

    /** Values are clamped on save (spec §8.5). */
    fun toProfile(d: ProfileDraft, previous: Profile?, now: Instant, today: LocalDate): Profile {
        val lengthUnit = Units.lengthUnitFor(d.unitSystem)
        val rawCm = heightCm(d) ?: previous?.height?.cm ?: 178.0
        val cm = rawCm.coerceIn(HEIGHT_CM_MIN, HEIGHT_CM_MAX)
        val entered = if (cm == rawCm) (parseDecimal(d.heightValue) ?: cm) else if (lengthUnit == LengthUnit.CM) cm else Units.cmToIn(cm)
        val weightUnit = Units.weightUnitFor(d.unitSystem)
        val known = LinkedHashMap<String, Double>()
        for ((id, text) in d.knownLoads) {
            val v = parseDecimal(text) ?: continue
            if (v <= 0.0) continue
            known[id] = if (weightUnit == WeightUnit.KG) v else Units.lbToKg(v)
        }
        val days = d.daysPerWeek.coerceIn(1, 6)
        val weekdays = d.weekdays.filter { it in 1..7 }.distinct().sorted()
        return Profile(
            sex = d.sex,
            birthYear = d.birthYear.coerceIn(BIRTH_YEAR_MIN, BIRTH_YEAR_MAX),
            height = Units.enteredLength(entered, lengthUnit),
            trainingMonthsConsistent = d.trainingMonths.coerceAtLeast(0),
            layoffMonths = d.layoffMonths.coerceAtLeast(0),
            monthsRetrainedSinceLayoff = d.retrainedMonths.coerceAtLeast(0),
            goal = d.goal,
            daysPerWeek = days,
            sessionMinutes = d.sessionMinutes.coerceIn(30, 120),
            preferredWeekdays = if (weekdays.size == days) weekdays else emptyList(),
            limitations = d.limitations,
            medicalFlags = d.medicalFlags,
            clearanceConfirmedOn = parseDateOrNull(d.clearanceConfirmedOn),
            priorityMuscles = d.priorityMuscles.take(3),
            likedExerciseIds = d.likedExerciseIds,
            dislikedExerciseIds = d.dislikedExerciseIds,
            knownLoadsKg = known,
            allowAdvancedExercises = d.allowAdvanced,
            bodyFatPct = parseDecimal(d.bodyFatPct)?.coerceIn(3.0, 60.0),
            physicalJob = d.physicalJob,
            activityFactorOverride = previous?.activityFactorOverride,
            hasHrMonitor = d.hasHrMonitor,
            measuredHrMax = d.measuredHrMax.trim().toIntOrNull()?.coerceIn(120, 230),
            wakeTimeLocal = d.wakeTime,
            bedtimeLocal = d.bedTime,
            usualSessionTime = d.usualSessionTime,
            cardioInsideSessionMinutes = previous?.cardioInsideSessionMinutes ?: false,
            updatedAt = now,
        )
    }

    /** Profile → draft in the given unit system (Profile screen). `weightKg` comes from the latest weigh-in. */
    fun fromProfile(p: Profile, unit: UnitSystem, weightKg: Double?): ProfileDraft {
        val heightText = if (unit == UnitSystem.METRIC) Units.trim(p.height.cm) else Units.trim(Units.cmToIn(p.height.cm))
        val weightText = when {
            weightKg == null -> ""
            unit == UnitSystem.METRIC -> Units.trim(weightKg)
            else -> Units.trim(Units.kgToLb(weightKg))
        }
        val loads = p.knownLoadsKg.mapValues { (_, kg) -> if (unit == UnitSystem.METRIC) Units.trim(kg) else Units.trim(Units.kgToLb(kg)) }
        return ProfileDraft(
            sex = p.sex, birthYear = p.birthYear, heightValue = heightText, weightValue = weightText, unitSystem = unit,
            goal = p.goal, daysPerWeek = p.daysPerWeek, weekdays = p.preferredWeekdays, sessionMinutes = p.sessionMinutes,
            trainingMonths = p.trainingMonthsConsistent, layoffMonths = p.layoffMonths, retrainedMonths = p.monthsRetrainedSinceLayoff,
            limitations = p.limitations, medicalFlags = p.medicalFlags, knownLoads = loads, allowAdvanced = p.allowAdvancedExercises,
            clearanceConfirmedOn = p.clearanceConfirmedOn?.toString() ?: "", priorityMuscles = p.priorityMuscles,
            likedExerciseIds = p.likedExerciseIds, dislikedExerciseIds = p.dislikedExerciseIds,
            bodyFatPct = p.bodyFatPct?.let { Units.trim(it) } ?: "", physicalJob = p.physicalJob, hasHrMonitor = p.hasHrMonitor,
            measuredHrMax = p.measuredHrMax?.toString() ?: "", wakeTime = p.wakeTimeLocal, bedTime = p.bedtimeLocal,
            usualSessionTime = p.usualSessionTime,
        )
    }

    /** Unit toggle: converts the typed height/weight/known loads so the same person stays the same person. */
    fun switchUnits(d: ProfileDraft, to: UnitSystem): ProfileDraft {
        if (d.unitSystem == to) return d
        fun conv(text: String, toImperial: Boolean, kind: Int): String {
            val v = parseDecimal(text) ?: return text
            val out = when (kind) {
                0 -> if (toImperial) Units.cmToIn(v) else Units.inToCm(v)
                else -> if (toImperial) Units.kgToLb(v) else Units.lbToKg(v)
            }
            return Units.trim(Math.round(out * 10.0) / 10.0)
        }
        val imperial = to == UnitSystem.IMPERIAL
        return d.copy(
            unitSystem = to,
            heightValue = conv(d.heightValue, imperial, 0),
            weightValue = conv(d.weightValue, imperial, 1),
            knownLoads = d.knownLoads.mapValues { (_, v) -> conv(v, imperial, 1) },
        )
    }

    fun parseDateOrNull(text: String): LocalDate? = try {
        if (text.isBlank()) null else LocalDate.parse(text.trim())
    } catch (e: DateTimeParseException) {
        null
    }

    /** The first mesocycle week as a Monday-first 7-day strip with set counts. */
    fun summarize(m: Mesocycle): BuiltPlan {
        val week = m.weeks.firstOrNull()
        val byDay = week?.sessions?.associateBy { it.weekday } ?: emptyMap()
        val days = (1..7).map { wd ->
            val s = byDay[wd]
            BuiltDay(wd, s?.name, s?.slots?.sumOf { it.sets } ?: 0)
        }
        return BuiltPlan(days, m.underTargetReport, days.sumOf { it.sets })
    }
}

