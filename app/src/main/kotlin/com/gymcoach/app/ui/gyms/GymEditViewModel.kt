package com.gymcoach.app.ui.gyms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.EquipmentCategory
import com.gymcoach.core.model.EquipmentDef
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.util.IdGen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** §8.13 editor state. `availableCount` is live: EquipmentResolver over the draft gym. */
data class GymEditUiState(
    val gym: GymSet,
    val query: String,
    val expanded: Set<EquipmentCategory>,
    val availableCount: Int,
    val totalExercises: Int,
    val dirty: Boolean,
    val saving: Boolean,
    val error: String?,
    val isNew: Boolean,
)

sealed class GymEditEvent {
    object Saved : GymEditEvent()
    object Archived : GymEditEvent()
    data class Message(val text: String) : GymEditEvent()
}

class GymEditViewModel(
    private val gymId: String,
    private val fromPreset: String,
    private val gyms: GymRepository,
    private val settings: SettingsRepository,
    private val knowledge: KnowledgeBase,
    private val clock: Clock,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val resolver = EquipmentResolver(knowledge)
    private val isNew = gymId.isBlank()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<GymEditUiState> = _state.asStateFlow()

    private val _loaded = MutableStateFlow(isNew)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _otherGyms = MutableStateFlow<List<GymSet>>(emptyList())
    /** Candidates for "Same as another gym". */
    val otherGyms: StateFlow<List<GymSet>> = _otherGyms.asStateFlow()

    private val channel = Channel<GymEditEvent>(Channel.BUFFERED)
    val events: Flow<GymEditEvent> = channel.receiveAsFlow()

    init {
        scope.launch {
            _otherGyms.value = gyms.getAll().filter { it.id != _state.value.gym.id }
            if (!isNew) {
                val stored = gyms.get(gymId)
                if (stored == null) {
                    _state.update { it.copy(error = ERROR_MISSING) }
                } else {
                    _state.value = recount(_state.value.copy(gym = stored, dirty = false))
                }
                _loaded.value = true
            }
        }
    }

    private fun initialState(): GymEditUiState {
        val now = clock.now()
        val preset = knowledge.presets[fromPreset]
        val gym = when {
            !isNew -> GymSet(id = gymId, name = "", items = emptyList(), createdAt = now)
            preset != null -> Presets.gymFromPreset(knowledge, fromPreset, preset.name, IdGen.newId(), now)
            else -> GymSet(id = IdGen.newId(), name = "", items = emptyList(), createdAt = now)
        }
        return recount(
            GymEditUiState(gym, "", emptySet(), 0, knowledge.exercises.size, dirty = isNew && preset != null, saving = false, error = null, isNew = isNew),
        )
    }

    private fun recount(s: GymEditUiState): GymEditUiState =
        s.copy(availableCount = resolver.availableExerciseIds(SessionEquipment(s.gym)).size, totalExercises = knowledge.exercises.size)

    private fun edit(transform: (GymSet) -> GymSet) {
        _state.update { recount(it.copy(gym = transform(it.gym), dirty = true, error = null)) }
    }

    fun onName(name: String) = edit { it.copy(name = name) }
    fun onColor(index: Int) = edit { it.copy(colorIndex = index.coerceIn(0, 7)) }
    fun onDefaultMinutes(minutes: Int?) = edit { it.copy(defaultSessionMinutes = minutes) }
    fun onNotes(notes: String) = edit { it.copy(notes = notes) }

    fun onQuery(query: String) = _state.update { it.copy(query = query) }

    fun onToggleCategory(category: EquipmentCategory) =
        _state.update { it.copy(expanded = if (category in it.expanded) it.expanded - category else it.expanded + category) }

    /** Replaces the item list with the preset (the screen confirms first when items exist). */
    fun onStartFromPreset(id: String) {
        val preset = knowledge.presets[id] ?: return
        edit {
            val fresh = Presets.gymFromPreset(knowledge, id, it.name.ifBlank { preset.name }, it.id, it.createdAt)
            it.copy(items = fresh.items, presetOrigin = id, defaultSessionMinutes = fresh.defaultSessionMinutes ?: it.defaultSessionMinutes, travelKitMerged = fresh.travelKitMerged, name = fresh.name)
        }
    }

    fun onToggleItem(equipmentId: String) = edit { g ->
        if (g.items.any { it.equipmentId == equipmentId }) g.copy(items = g.items.filterNot { it.equipmentId == equipmentId })
        else g.copy(items = g.items + GymItem(equipmentId))
    }

    /** JsonNull clears the key so the catalogue default applies again. */
    fun onParam(equipmentId: String, key: String, value: JsonElement) = edit { g ->
        g.copy(items = g.items.map { item ->
            if (item.equipmentId != equipmentId) item
            else item.copy(params = if (value is JsonNull) item.params - key else item.params + (key to value))
        })
    }

    fun onToggleTravelKit() = edit { g ->
        if (!g.travelKitMerged) {
            Presets.mergeTravelKit(knowledge, g)
        } else {
            val kit = knowledge.presets[Presets.TRAVEL_KIT]?.items?.map { it.equipmentId }?.toSet() ?: emptySet()
            val own = g.presetOrigin?.let { origin -> knowledge.presets[origin]?.items?.map { it.equipmentId }?.toSet() } ?: kit
            g.copy(items = g.items.filterNot { it.equipmentId in kit && it.equipmentId !in own }, travelKitMerged = false)
        }
    }

    fun onCopyFrom(otherId: String) {
        val other = _otherGyms.value.firstOrNull { it.id == otherId } ?: return
        edit { it.copy(items = other.items, travelKitMerged = other.travelKitMerged) }
    }

    /** "Add equipment today": one-off, written into the persisted TodayContext only (the gym stays unchanged). */
    fun onAddToday(equipmentId: String) {
        scope.launch {
            try {
                val today = clock.today()
                val s = settings.get()
                val existing = try {
                    if (s.todayContextJson.isBlank()) null else CoreJson.stored.decodeFromString(TodayContext.serializer(), s.todayContextJson)
                } catch (e: Exception) {
                    null
                }
                val base = if (existing != null && existing.date == today) existing else TodayContext(date = today, gymId = _state.value.gym.id)
                if (base.extraItems.none { it.equipmentId == equipmentId }) {
                    val next = base.copy(extraItems = base.extraItems + GymItem(equipmentId), removedEquipmentIds = base.removedEquipmentIds - equipmentId)
                    val json = CoreJson.stored.encodeToString(TodayContext.serializer(), next)
                    settings.update { it.copy(todayContextJson = json) }
                }
                channel.send(GymEditEvent.Message(MSG_TODAY_ADDED))
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: ERROR_SAVE) }
            }
        }
    }

    fun onSave() {
        val s = _state.value
        if (s.saving) return
        if (s.gym.name.isBlank()) {
            _state.update { it.copy(error = ERROR_NAME) }
            return
        }
        scope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val others = gyms.getAll()
                val toStore = if (s.isNew && others.none { it.isDefault }) s.gym.copy(isDefault = true) else s.gym
                gyms.upsert(toStore)
                _state.update { it.copy(gym = toStore, saving = false, dirty = false) }
                channel.send(GymEditEvent.Saved)
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: ERROR_SAVE) }   // the draft stays in the state
            }
        }
    }

    fun onSetDefault() {
        val s = _state.value
        scope.launch {
            try {
                if (s.dirty || s.isNew) gyms.upsert(s.gym)
                gyms.setDefault(s.gym.id)
                _state.update { it.copy(gym = it.gym.copy(isDefault = true), dirty = false) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: ERROR_SAVE) }
            }
        }
    }

    fun onArchive() {
        val s = _state.value
        if (s.isNew) return
        if (s.gym.isDefault) {
            _state.update { it.copy(error = ERROR_ARCHIVE_DEFAULT) }
            return
        }
        scope.launch {
            try {
                gyms.setArchived(s.gym.id, true)
                channel.send(GymEditEvent.Archived)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: ERROR_SAVE) }
            }
        }
    }

    /** Catalogue entries to show for the current query (names, DE/EN aliases, ids); session flags are excluded. */
    fun visibleEquipment(): List<EquipmentDef> = knowledge.searchEquipment(_state.value.query).filter { !it.sessionFlag }

    companion object {
        const val ERROR_NAME = "Give the gym a name."
        const val ERROR_SAVE = "Saving failed. Your changes are kept; try again."
        const val ERROR_MISSING = "This gym no longer exists."
        const val ERROR_ARCHIVE_DEFAULT = "Make another gym the default before archiving this one."
        const val MSG_TODAY_ADDED = "Added for today only."
    }
}
