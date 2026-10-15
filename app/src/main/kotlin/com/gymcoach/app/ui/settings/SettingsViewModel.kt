package com.gymcoach.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.app.ui.knowledge.ConstantsModel
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.RuleBook
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.Confidence
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.Effort
import com.gymcoach.core.model.UnitSystem
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.repo.ApiKeyStore
import com.gymcoach.core.repo.BackendError
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** §6.10 user copy for every [BackendError]; produced here because ViewModels have no Context. Never contains a key. */
object BackendCopy {
    fun of(error: BackendError): String = when (error) {
        BackendError.NotConfigured -> "Add an API key in Settings → Backend."
        BackendError.Offline -> "You are offline. Your draft is kept and sent when you are back online."
        is BackendError.Transport -> "Connection lost. Retry?"
        BackendError.Authentication -> "Key rejected or expired — enter a new key in Settings."
        BackendError.Billing -> "Console credits exhausted — top up at platform.claude.com."
        is BackendError.Permission -> error.message
        is BackendError.InvalidRequest -> when (error.status) {
            0 -> "Add a gym first: the coach needs to know your equipment."
            404 -> "Model not available for this key (update the app?)."
            413 -> "Message too large."
            else -> error.message
        }
        is BackendError.RateLimited -> error.retryAfterSec?.let { "Rate limited, try again in $it s." } ?: "Rate limited, try again in a minute."
        BackendError.SpendCapReached -> "Console spend cap reached."
        is BackendError.ServerError -> "Anthropic error (${error.status}). Try again."
        is BackendError.Overloaded -> "Overloaded — try again in a minute."
        is BackendError.StreamError -> error.type + ": " + error.message
        is BackendError.BridgeUnavailable -> "Home bridge: " + error.reason + " (PC offline, secret wrong, login expired, usage limit, or disabled by policy)."
        is BackendError.Unknown -> error.message
    }

    /** `error.type`-derived label shown next to the request id on the Test result. */
    fun typeOf(error: BackendError): String = error::class.java.simpleName
}

/** §8.18 main settings. */
data class SettingsUiState(val settings: AppSettings, val catalogVersion: Int, val appVersion: String, val error: String?)

class SettingsViewModel(
    private val settings: SettingsRepository,
    appVersion: String,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(SettingsUiState(AppSettings(), 1, appVersion, null))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        scope.launch {
            settings.observe().collect { s -> _state.update { it.copy(settings = s, catalogVersion = s.catalogVersionSeen.coerceAtLeast(1)) } }
        }
    }

    private fun change(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            try {
                settings.update(transform)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Could not save the setting.") }
            }
        }
    }

    fun onUnits(system: UnitSystem) = change { it.copy(unitSystem = system) }
    fun onWeekStart(monday: Boolean) = change { it.copy(weekStartsOnMonday = monday) }
    fun onKeepScreenOn(on: Boolean) = change { it.copy(keepScreenOnInSession = on) }
    fun onReminderTime(hhmm: String) = change { it.copy(readinessReminderTime = hhmm) }
    fun onCheckinWeekday(isoDay: Int) = change { it.copy(weeklyCheckinWeekday = isoDay.coerceIn(1, 7)) }
}

/** §8.18 backend screen. keyError holds a [KeyError] code, testResult a ready-to-show line. */
data class BackendSettingsUiState(
    val mode: BackendMode,
    val hasKey: Boolean,
    val keyDraft: String,
    val keyError: String?,
    val testing: Boolean,
    val testResult: String?,
    val bridgeUrl: String,
    val bridgeSecretSet: Boolean,
    val model: String,
    val effort: Effort,
    val monthlyBudgetUsd: Double,
    val showExperimental: Boolean,
    val openConversationWillClose: Boolean,
)

object KeyError {
    const val REJECTED = "key_rejected"      // does not start with sk-ant-api (OAuth / login tokens included)
    const val EMPTY = "key_empty"
    const val STORE = "key_store_failed"
    const val PREFIX = "sk-ant-api"

    /** Only Claude Console API keys (sk-ant-api…) are accepted; the rule also rejects `sk-ant-oat…` login tokens. */
    fun validate(raw: String): String? {
        val key = raw.trim()
        if (key.isEmpty()) return EMPTY
        if (!key.startsWith(PREFIX) || key.length < PREFIX.length + 8 || key.any { it.isWhitespace() }) return REJECTED
        return null
    }
}

class BackendSettingsViewModel(
    private val settings: SettingsRepository,
    private val keys: ApiKeyStore,
    private val backendFor: (BackendMode) -> ClaudeBackend,
    private val coach: CoachRepository,
    private val clock: Clock,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(
        BackendSettingsUiState(BackendMode.NONE, false, "", null, false, null, "", false, AppSettings().modelId, AppSettings().effort, AppSettings().monthlyBudgetUsd, false, false),
    )
    val state: StateFlow<BackendSettingsUiState> = _state.asStateFlow()

    init {
        scope.launch { reload() }
    }

    private suspend fun reload() {
        val s = settings.get()
        _state.update {
            it.copy(
                mode = s.backendMode, hasKey = keys.hasKey(), bridgeUrl = s.bridgeUrl, bridgeSecretSet = keys.readBridgeSecret() != null,
                model = s.modelId, effort = s.effort, monthlyBudgetUsd = s.monthlyBudgetUsd, showExperimental = s.showExperimental,
                openConversationWillClose = coach.openConversation() != null,
            )
        }
    }

    /** §6.9: model, effort and backend are frozen per conversation; a change closes the open one. */
    private suspend fun closeOpenConversation() {
        val open = coach.openConversation() ?: return
        coach.updateConversation(open.copy(status = ConversationStatus.CLOSED, endedAt = clock.now()))
    }

    fun onMode(mode: BackendMode) {
        scope.launch {
            if (mode != _state.value.mode) closeOpenConversation()
            settings.update { it.copy(backendMode = mode) }
            reload()
        }
    }

    fun onKeyDraft(text: String) = _state.update { it.copy(keyDraft = text, keyError = null) }

    fun onSaveKey() {
        val error = KeyError.validate(_state.value.keyDraft)
        if (error != null) {
            _state.update { it.copy(keyError = error) }
            return
        }
        scope.launch {
            try {
                keys.store(_state.value.keyDraft.trim())
                val firstKey = _state.value.mode == BackendMode.NONE
                if (firstKey) settings.update { it.copy(backendMode = BackendMode.DIRECT_API) }
                _state.update { it.copy(keyDraft = "", keyError = null, testResult = null) }
                reload()
            } catch (e: Exception) {
                _state.update { it.copy(keyError = KeyError.STORE) }
            }
        }
    }

    fun onRemoveKey() {
        scope.launch {
            keys.clear()
            if (_state.value.mode == BackendMode.DIRECT_API) {
                closeOpenConversation()
                settings.update { it.copy(backendMode = BackendMode.NONE) }
            }
            _state.update { it.copy(testResult = null) }
            reload()
        }
    }

    /** "Test connection": one 32-token non-streamed request through ClaudeBackend.test(); shows request-id, never the key. */
    fun onTest() {
        val mode = _state.value.mode
        if (_state.value.testing) return
        scope.launch {
            _state.update { it.copy(testing = true, testResult = null) }
            val line = try {
                val r = backendFor(mode).test()
                val id = r.requestId?.let { " · request-id $it" } ?: ""
                val err = r.error
                if (err == null) "OK$id" else BackendCopy.typeOf(err) + ": " + BackendCopy.of(err) + id
            } catch (e: Exception) {
                "Unknown: " + (e.message ?: "test failed")
            }
            _state.update { it.copy(testing = false, testResult = line) }
        }
    }

    fun onBridgeUrl(url: String) {
        _state.update { it.copy(bridgeUrl = url) }
        scope.launch { settings.update { it.copy(bridgeUrl = url.trim()) } }
    }

    fun onBridgeSecret(secret: String) {
        scope.launch {
            if (secret.isNotBlank()) keys.storeBridgeSecret(secret.trim())
            _state.update { it.copy(bridgeSecretSet = keys.readBridgeSecret() != null) }
        }
    }

    fun onShowExperimental(on: Boolean) {
        scope.launch {
            settings.update { it.copy(showExperimental = on) }
            reload()
        }
    }

    fun onModel(id: String) {
        scope.launch {
            if (id != _state.value.model) closeOpenConversation()
            settings.update { it.copy(modelId = id) }
            reload()
        }
    }

    fun onEffort(effort: Effort) {
        scope.launch {
            if (effort != _state.value.effort) closeOpenConversation()
            settings.update { it.copy(effort = effort) }
            reload()
        }
    }

    fun onBudget(usd: Double) {
        scope.launch {
            settings.update { it.copy(monthlyBudgetUsd = usd.coerceIn(1.0, 500.0)) }
            reload()
        }
    }
}

/** §8.18 advanced row; `current` is the editable text (bands/lists comma-separated). */
data class ConstantRow(
    val group: String,
    val field: String,
    val ruleId: String,
    val confidence: Confidence,
    val citation: String,
    val default: String,
    val current: String,
    val adjustable: Boolean,
    val kind: String,
)

data class AdvancedConstantsUiState(val rows: List<ConstantRow>, val query: String, val dirty: Boolean, val error: String?)

/** Walks `PlannerConstants.leafFields()`; only `user_adjustable` rules are editable; writes the NESTED overrides JSON. */
class AdvancedConstantsViewModel(
    private val settings: SettingsRepository,
    private val rules: RuleBook,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private var allRows: List<ConstantRow> = emptyList()
    private var kinds: Map<String, JsonElement> = emptyMap()
    private val edits = LinkedHashMap<String, String>()
    private var query: String = ""
    private val _state = MutableStateFlow(AdvancedConstantsUiState(emptyList(), "", false, null))
    val state: StateFlow<AdvancedConstantsUiState> = _state.asStateFlow()

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val json = settings.get().plannerOverridesJson
        val current = PlannerConstants.withOverrides(PlannerConstants.DEFAULT, json, CoreJson.stored)
        val leaves = ConstantsModel.leaves(PlannerConstants.DEFAULT, current, rules)
        kinds = leaves.associate { it.id to it.defaultJson }
        allRows = leaves.map { leaf ->
            val rule = rules.get(leaf.ruleId)
            ConstantRow(
                group = leaf.group, field = leaf.field, ruleId = leaf.ruleId, confidence = rule?.confidence ?: Confidence.UNVERIFIED,
                citation = rule?.citation ?: "", default = ConstantsModel.format(leaf.kind, leaf.defaultJson),
                current = ConstantsModel.format(leaf.kind, leaf.currentJson), adjustable = rule?.userAdjustable ?: false, kind = leaf.kind,
            )
        }
        edits.clear()
        publish()
    }

    private fun publish(error: String? = null) {
        val q = query.trim().lowercase()
        val rows = allRows
            .filter { q.isEmpty() || it.ruleId.lowercase().contains(q) || it.field.lowercase().contains(q) || it.group.lowercase() == q }
            .map { row -> edits[row.group + "." + row.field]?.let { row.copy(current = it) } ?: row }
        _state.value = AdvancedConstantsUiState(rows, query, edits.isNotEmpty(), error)
    }

    fun onQuery(q: String) {
        query = q
        publish()
    }

    /** Edits the text of one leaf (bands as "lo,hi", lists comma-separated). Not saved until [onSave]. */
    fun onEdit(group: String, field: String, text: String) {
        val row = allRows.firstOrNull { it.group == group && it.field == field }
        if (row == null || !row.adjustable) return
        edits[group + "." + field] = text
        publish()
    }

    fun onSave() {
        scope.launch {
            try {
                val overrides = ConstantsModel.parseOverrides(settings.get().plannerOverridesJson).toMutableMap()
                val bad = ArrayList<String>()
                for ((id, text) in edits) {
                    val row = allRows.firstOrNull { it.group + "." + it.field == id } ?: continue
                    val value = ConstantsModel.parse(row.kind, text, kinds[id] ?: continue)
                    if (value == null) {
                        bad.add(row.field)
                        continue
                    }
                    val groupObj = ((overrides[row.group] as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
                    groupObj[row.field] = value
                    overrides[row.group] = JsonObject(groupObj)
                }
                if (bad.isNotEmpty()) {
                    publish("Invalid value for: " + bad.joinToString(", "))
                    return@launch
                }
                settings.update { it.copy(plannerOverridesJson = CoreJson.stored.encodeToString(JsonObject.serializer(), JsonObject(overrides))) }
                load()
            } catch (e: Exception) {
                publish(e.message ?: "Could not save the constants.")
            }
        }
    }

    /** Removes one leaf from the overrides (back to the default). */
    fun onReset(group: String, field: String) {
        scope.launch {
            val overrides = ConstantsModel.parseOverrides(settings.get().plannerOverridesJson).toMutableMap()
            val groupObj = ((overrides[group] as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
            groupObj.remove(field)
            if (groupObj.isEmpty()) overrides.remove(group) else overrides[group] = JsonObject(groupObj)
            settings.update { it.copy(plannerOverridesJson = CoreJson.stored.encodeToString(JsonObject.serializer(), JsonObject(overrides))) }
            load()
        }
    }

    fun onResetAll() {
        scope.launch {
            settings.update { it.copy(plannerOverridesJson = "{}") }
            load()
        }
    }
}
