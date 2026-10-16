package com.gymcoach.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.app.ui.components.parseDecimal
import com.gymcoach.app.ui.profile.DraftRules
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.Limitation
import com.gymcoach.core.model.MedicalFlag
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.model.WeightUnit
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.MetricsRepository
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.ProgressionRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.seed.SeedProfile
import com.gymcoach.core.seed.SeedProfiles
import com.gymcoach.core.time.Clock
import com.gymcoach.core.units.Units
import com.gymcoach.core.util.IdGen
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * §8.5. All profile fields the Profile screen (§8.14) edits live here too; the fields after
 * [allowAdvanced] are WP-08 additions with defaults (the §8.5 list is a prefix of this class).
 */
@Serializable
data class ProfileDraft(
    val sex: Sex = Sex.MALE,
    val birthYear: Int = 1990,
    val heightValue: String = "178",
    val weightValue: String = "80",
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val goal: Goal = Goal.GENERAL_FITNESS,
    val daysPerWeek: Int = 3,
    val weekdays: List<Int> = listOf(1, 3, 5),
    val sessionMinutes: Int = 60,
    val trainingMonths: Int = 0,
    val layoffMonths: Int = 0,
    val retrainedMonths: Int = 0,
    val limitations: List<Limitation> = emptyList(),
    val medicalFlags: Set<MedicalFlag> = emptySet(),
    val knownLoads: Map<String, String> = emptyMap(),
    val allowAdvanced: Boolean = false,
    val clearanceConfirmedOn: String = "",
    val priorityMuscles: List<Muscle> = emptyList(),
    val likedExerciseIds: List<String> = emptyList(),
    val dislikedExerciseIds: List<String> = emptyList(),
    val bodyFatPct: String = "",
    val physicalJob: Boolean = false,
    val hasHrMonitor: Boolean = false,
    val measuredHrMax: String = "",
    val wakeTime: String = "06:30",
    val bedTime: String = "23:00",
    val usualSessionTime: String = "evening",
    // Phase 2 personal fields (carried through to Profile unchanged; the seed profile fills them).
    val splitId: String? = null,                                   // §3.4.1 personal split in use (null = generic split selection)
    val seedSplitId: String? = null,                               // the personal split offered by the seed/profile (toggle target)
    val strengthTargetsKg: Map<String, Double> = emptyMap(),
    val barbellRirFloor: Int? = null,
    val coachNotes: List<String> = emptyList(),
    val seedId: String? = null,                                    // SeedProfile.id the draft started from (null = generic path)
)

data class OnboardingUiState(
    val page: Int,
    val draft: ProfileDraft,
    val gymDraft: GymSet?,
    val presetId: String?,
    val building: Boolean,
    val error: String?,
    val builtSummary: String?,
)

/** One day of the page-6 strip. */
data class BuiltDay(val weekday: Int, val sessionName: String?, val sets: Int)

/** Page 6: the generated first week (from the STORED gym) and the under-target cards. */
data class BuiltPlan(val days: List<BuiltDay>, val underTarget: List<Explanation>, val totalSets: Int)

sealed class OnboardingEvent {
    data class OpenGymEditor(val gymId: String) : OnboardingEvent()
    object Finished : OnboardingEvent()
}

/** Extra state beside the frozen [OnboardingUiState]: gym id/name and field validity. */
data class OnboardingExtras(
    val gymId: String?,
    val gymName: String,
    val heightOk: Boolean,
    val weightOk: Boolean,
    val birthYearOk: Boolean,
    val seedName: String? = null,                                  // "Joe" while the draft comes from a seed profile
    val seedHints: List<String> = emptyList(),                     // SeedProfile.placeholders: fields the seed does not know
)

@Serializable
private data class StoredOnboarding(
    val page: Int = 1,
    val draft: ProfileDraft = ProfileDraft(),
    val presetId: String? = null,
    val gymId: String? = null,
    val gymName: String = "",
    val seedDeclined: Boolean = false,
)

/**
 * §8.5 onboarding. Dependencies are explicit (no Context) so the JVM test can drive it with FakeRepositories;
 * [buildPlan] is `container.planCoordinator.recompute("onboarding")` in production.
 */
class OnboardingViewModel(
    private val profiles: ProfileRepository,
    private val gyms: GymRepository,
    private val metrics: MetricsRepository,
    private val settings: SettingsRepository,
    private val knowledge: KnowledgeBase,
    private val clock: Clock,
    private val buildPlan: suspend () -> Mesocycle,
    scopeOverride: CoroutineScope? = null,
    private val computeContext: CoroutineContext = Dispatchers.Default,
    /** Phase 2: the shipped starting profile (SeedProfiles.classpath()); null = the generic onboarding only. */
    private val seed: SeedProfile? = null,
    private val workouts: WorkoutRepository? = null,
    private val progression: ProgressionRepository? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow(
        OnboardingUiState(page = 1, draft = ProfileDraft(), gymDraft = null, presetId = null, building = false, error = null, builtSummary = null),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _extras = MutableStateFlow(OnboardingExtras(null, "", true, true, true))
    val extras: StateFlow<OnboardingExtras> = _extras.asStateFlow()

    private val _built = MutableStateFlow<BuiltPlan?>(null)
    val built: StateFlow<BuiltPlan?> = _built.asStateFlow()

    private val channel = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingEvent> = channel.receiveAsFlow()

    private var lastLoggedWeight: String? = null
    private var seedDeclined: Boolean = false

    init {
        scope.launch {
            if (!restoreDraft()) applySeed()
            gyms.observeAll().collect { list ->
                val id = _extras.value.gymId ?: return@collect
                val stored = list.firstOrNull { it.id == id } ?: return@collect
                _state.update { it.copy(gymDraft = stored) }
                _extras.update { it.copy(gymName = stored.name) }
            }
        }
    }

    /** True when a stored draft was restored (then the seed is NOT re-applied: the user's edits win). */
    private suspend fun restoreDraft(): Boolean {
        val json = settings.get().onboardingDraftJson
        if (json.isBlank()) return false
        val stored = try {
            CoreJson.stored.decodeFromString(StoredOnboarding.serializer(), json)
        } catch (e: Exception) {
            return false
        }
        seedDeclined = stored.seedDeclined
        val fromSeed = seed?.takeIf { stored.draft.seedId == it.id }
        _state.update { it.copy(page = stored.page.coerceIn(1, 6), draft = stored.draft, presetId = stored.presetId, gymDraft = if (stored.gymId == null) fromSeed?.let { s -> seedDefaultGym(s) } else null) }
        _extras.value = revalidate(stored.draft, OnboardingExtras(stored.gymId, stored.gymName, true, true, true, fromSeed?.displayName, fromSeed?.placeholders ?: emptyList()))
        return true
    }

    /**
     * Phase 2: prefill every page from the seed profile (first run, no stored draft, onboarding not done). The gym page shows the
     * seed's default gym; [ensureGymPersisted] stores all seed gyms. Body weight stays empty when the seed has none, so page 2
     * asks for it.
     */
    private suspend fun applySeed() {
        val s = seed ?: return
        if (seedDeclined || settings.get().onboardingDone) return
        val draft = DraftRules.fromSeed(s)
        val gym = seedDefaultGym(s)
        _state.update { it.copy(draft = draft, presetId = gym?.presetOrigin, gymDraft = gym) }
        _extras.value = revalidate(draft, OnboardingExtras(null, gym?.name ?: "", true, true, true, s.displayName, s.placeholders))
        persistDraft()
    }

    private fun seedDefaultGym(s: SeedProfile): GymSet? = s.gyms.firstOrNull { it.id == s.defaultGymId } ?: s.gyms.firstOrNull()

    /** Welcome page "Start from scratch": the generic onboarding with an empty draft; the seed is not offered again. */
    fun onUseGenericProfile() {
        seedDeclined = true
        _state.update { it.copy(draft = ProfileDraft(), presetId = null, gymDraft = null, error = null) }
        _extras.value = revalidate(ProfileDraft(), OnboardingExtras(null, "", true, true, true))
        persistDraft()
    }

    private fun revalidate(d: ProfileDraft, base: OnboardingExtras): OnboardingExtras =
        base.copy(heightOk = DraftRules.heightOk(d), weightOk = DraftRules.weightOk(d), birthYearOk = DraftRules.birthYearOk(d))

    private fun persistDraft() {
        val s = _state.value
        val x = _extras.value
        val text = CoreJson.stored.encodeToString(
            StoredOnboarding.serializer(),
            StoredOnboarding(page = s.page, draft = s.draft, presetId = s.presetId, gymId = x.gymId, gymName = x.gymName, seedDeclined = seedDeclined),
        )
        scope.launch { settings.update { it.copy(onboardingDraftJson = text) } }
    }

    fun onDraftChange(draft: ProfileDraft) {
        _state.update { it.copy(draft = draft, error = null) }
        _extras.update { revalidate(draft, it) }
        persistDraft()
    }

    fun onGymName(name: String) {
        _extras.update { it.copy(gymName = name) }
        persistDraft()
    }

    /** Phase 2 gym page: back to the seed profile's own gyms after a preset was tried. */
    fun onSeedGymsSelected() {
        val s = seed?.takeIf { _state.value.draft.seedId == it.id } ?: return
        if (_extras.value.gymId != null) return          // a gym is already stored; edit it in the gym editor
        val gym = seedDefaultGym(s) ?: return
        _state.update { it.copy(presetId = gym.presetOrigin, gymDraft = gym) }
        _extras.update { it.copy(gymName = gym.name) }
        persistDraft()
    }

    fun onPresetSelected(id: String) {
        val preset = knowledge.presets[id] ?: return
        val existingId = _extras.value.gymId
        val name = _extras.value.gymName.ifBlank { preset.name }
        val gym = Presets.gymFromPreset(knowledge, id, name, existingId ?: IdGen.newId(), clock.now())
        _state.update { it.copy(presetId = id, gymDraft = gym) }
        _extras.update { it.copy(gymName = name) }
        persistDraft()
    }

    fun onNext() {
        val s = _state.value
        if (s.building) return
        when (s.page) {
            2 -> if (!(_extras.value.heightOk && _extras.value.weightOk && _extras.value.birthYearOk)) {
                _state.update { it.copy(error = ERROR_ABOUT) }
                return
            }
            5 -> {
                scope.launch { generate() }
                return
            }
            else -> Unit
        }
        if (s.page < 6) goTo(s.page + 1)
    }

    fun onBack() {
        val s = _state.value
        if (s.page > 1 && !s.building) goTo(s.page - 1)
    }

    fun onSkipPage() {
        val s = _state.value
        when (s.page) {
            4 -> goTo(5)
            5 -> scope.launch { generate() }
            else -> Unit
        }
    }

    private fun goTo(page: Int) {
        _state.update { it.copy(page = page, error = null) }
        persistDraft()
    }

    /** Persists the preset gym FIRST (default, id kept in the draft), then asks the UI to open the editor. */
    fun onReviewEquipment() {
        scope.launch {
            val gym = ensureGymPersisted()
            persistDraft()
            channel.send(OnboardingEvent.OpenGymEditor(gym.id))
        }
    }

    private suspend fun ensureGymPersisted(): GymSet {
        val st = _state.value
        val x = _extras.value
        val existing = x.gymId?.let { gyms.get(it) }
        val name = x.gymName.ifBlank { st.gymDraft?.name ?: "" }
        val seeded = seed?.takeIf { st.draft.seedId == it.id && st.gymDraft?.id == it.defaultGymId }
        val gym: GymSet = if (existing != null) {
            if (name.isNotBlank() && name != existing.name) existing.copy(name = name).also { gyms.upsert(it) } else existing
        } else if (seeded != null) {
            // Phase 2: every gym of the seed profile is created on first run (ids kept); the seed's default gym is the default.
            val known = gyms.getAll(includeArchived = true).map { it.id }.toSet()
            for (g in seeded.gyms) {
                if (g.id in known) continue
                gyms.upsert(if (g.id == seeded.defaultGymId) g.copy(name = name.ifBlank { g.name }, isDefault = true) else g.copy(isDefault = false))
            }
            gyms.setDefault(seeded.defaultGymId)
            gyms.get(seeded.defaultGymId) ?: seeded.gyms.first { it.id == seeded.defaultGymId }
        } else {
            val presetId = st.presetId ?: Presets.BASE_GYM
            val base = st.gymDraft ?: Presets.gymFromPreset(knowledge, presetId, name.ifBlank { presetName(presetId) }, IdGen.newId(), clock.now())
            val toStore = base.copy(name = name.ifBlank { base.name }, isDefault = true)
            gyms.upsert(toStore)
            gyms.setDefault(toStore.id)
            toStore
        }
        _state.update { it.copy(gymDraft = gym, presetId = it.presetId ?: gym.presetOrigin) }
        _extras.update { it.copy(gymId = gym.id, gymName = gym.name) }
        return gym
    }

    private fun presetName(id: String): String = knowledge.presets[id]?.name ?: id

    /** Saves profile + first weigh-in + gym, then builds the plan from the stored data (page 6). */
    private suspend fun generate() {
        _state.update { it.copy(building = true, error = null) }
        try {
            saveProfileAndWeight()
            ensureGymPersisted()
            importSeedHistory()
            val meso = withContext(computeContext) { buildPlan() }
            val plan = DraftRules.summarize(meso)
            _built.value = plan
            val sessions = plan.days.count { it.sessionName != null }
            val summary = "${meso.split.name} · $sessions sessions · ${plan.totalSets} sets in week 1 · ${meso.weeks.size} weeks"
            _state.update { it.copy(page = 6, building = false, builtSummary = summary) }
            persistDraft()
        } catch (e: Exception) {
            _state.update { it.copy(building = false, error = e.message ?: ERROR_BUILD) }
        }
    }

    private suspend fun saveProfileAndWeight() {
        val d = _state.value.draft
        val now = clock.now()
        val today = clock.today()
        profiles.save(DraftRules.toProfile(d, profiles.get(), now, today))
        settings.update { it.copy(unitSystem = d.unitSystem) }
        val rawKg = DraftRules.weightKg(d)
        val raw = parseDecimal(d.weightValue)
        val key = d.weightValue + d.unitSystem.name
        if (rawKg != null && raw != null && key != lastLoggedWeight) {
            val unit = Units.weightUnitFor(d.unitSystem)
            val inRange = rawKg in DraftRules.WEIGHT_KG_MIN..DraftRules.WEIGHT_KG_MAX
            val clampedKg = rawKg.coerceIn(DraftRules.WEIGHT_KG_MIN, DraftRules.WEIGHT_KG_MAX)
            val entered = if (inRange) raw else if (unit == WeightUnit.KG) clampedKg else Units.kgToLb(clampedKg)
            metrics.addBodyMetric(BodyMetric(id = IdGen.newId(), date = today, weight = Units.enteredWeight(entered, unit), loggedAt = now))
            lastLoggedWeight = key
        }
    }

    /**
     * Phase 2: the seed's logged history (sessions + sets, copied verbatim) and its progression states, so the first plan starts
     * from the user's real loads instead of find-load. Idempotent: rows that already exist are never touched (sets are sacred).
     */
    private suspend fun importSeedHistory() {
        val s = seed?.takeIf { _state.value.draft.seedId == it.id } ?: return
        val w = workouts
        if (w != null) {
            for (record in s.history.sessions) {
                if (w.getSession(record.id) != null) continue
                w.startSession(record)
                for (set in s.history.sets.filter { it.sessionId == record.id }.sortedBy { it.setIndex }) w.addSet(set)
            }
        }
        val p = progression ?: return
        val gymId = _extras.value.gymId ?: s.defaultGymId
        val missing = SeedProfiles.progressionStates(s, knowledge, gymId).filter { p.get(it.key) == null }
        if (missing.isNotEmpty()) p.saveAll(missing)
    }

    /** "Start": make sure the plan exists, mark onboarding done, clear the draft. */
    fun onFinish() {
        scope.launch {
            if (_built.value == null) {
                generate()
                if (_built.value == null) return@launch
            }
            settings.update { it.copy(onboardingDone = true, onboardingDraftJson = "") }
            channel.send(OnboardingEvent.Finished)
        }
    }

    companion object {
        const val ERROR_ABOUT = "Check height (120–230 cm), weight (30–250 kg) and birth year (1920–2015)."
        const val ERROR_BUILD = "The plan could not be built."
    }
}
