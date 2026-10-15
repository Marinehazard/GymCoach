package com.gymcoach.app.ui.gyms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.util.IdGen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** §8.13 gyms list. */
data class GymsUiState(
    val gyms: List<GymSet>,
    val defaultId: String?,
    val activeId: String?,
    val loading: Boolean,
    val error: String?,
)

/** Rows ordered by last use (newest first), never-used gyms last in creation order. */
class GymsViewModel(
    private val gyms: GymRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(GymsUiState(emptyList(), null, null, loading = true, error = null))
    val state: StateFlow<GymsUiState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(gyms.observeAll(), settings.observe()) { list, s ->
                val ordered = list.sortedWith(
                    compareByDescending<GymSet> { it.lastUsedAt?.toEpochMilli() ?: Long.MIN_VALUE }.thenBy { it.createdAt.toEpochMilli() },
                )
                GymsUiState(ordered, ordered.firstOrNull { it.isDefault }?.id, s.activeGymId.ifBlank { null }, loading = false, error = null)
            }.catch { e ->
                _state.update { it.copy(loading = false, error = e.message ?: ERROR_LOAD) }
            }.collect { _state.value = it }
        }
    }

    fun onSetDefault(id: String) = run { gyms.setDefault(id) }

    /** "Use today": writes AppSettings.activeGymId (the Today gym switcher shares it). */
    fun onUse(id: String) = run {
        settings.update { it.copy(activeGymId = id) }
        gyms.touchLastUsed(id, clock.now())
    }

    fun onArchive(id: String) {
        val gym = _state.value.gyms.firstOrNull { it.id == id } ?: return
        if (gym.isDefault) {
            _state.update { it.copy(error = ERROR_ARCHIVE_DEFAULT) }
            return
        }
        run { gyms.setArchived(id, true) }
    }

    fun onDuplicate(id: String) {
        val gym = _state.value.gyms.firstOrNull { it.id == id } ?: return
        run {
            gyms.upsert(gym.copy(id = IdGen.newId(), name = gym.name + COPY_SUFFIX, isDefault = false, lastUsedAt = null, createdAt = clock.now(), archived = false))
        }
    }

    fun onDismissError() = _state.update { it.copy(error = null) }

    private fun run(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: ERROR_LOAD) }
            }
        }
    }

    companion object {
        const val COPY_SUFFIX = " (copy)"
        const val ERROR_LOAD = "Could not update the gym list."
        const val ERROR_ARCHIVE_DEFAULT = "Make another gym the default before archiving this one."
    }
}
