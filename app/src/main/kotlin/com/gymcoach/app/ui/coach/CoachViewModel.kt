package com.gymcoach.app.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gymcoach.core.coach.CoachTurnInputs
import com.gymcoach.core.coach.CoachTurnService
import com.gymcoach.core.coach.ProposalApplier
import com.gymcoach.core.coach.SpendMeter
import com.gymcoach.core.coach.TurnEvent
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.Effort
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.repo.BackendError
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.NetworkMonitor
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class CoachPhase {
    object Idle : CoachPhase()
    object Sending : CoachPhase()
    data class Streaming(val partial: String) : CoachPhase()
    object AwaitingTool : CoachPhase()
    object ProposalPending : CoachPhase()
    data class Error(val error: BackendError, val retryable: Boolean) : CoachPhase()
    data class Offline(val draftQueued: Boolean) : CoachPhase()
    object NoBackend : CoachPhase()
    data class Refused(val category: String?) : CoachPhase()
    object Truncated : CoachPhase()
    object SpendCapReached : CoachPhase()
    object CompactionNeeded : CoachPhase()
}

data class CoachUiState(
    val conversation: ChatConversation?,
    val messages: List<ChatMessage>,
    val proposals: Map<String, Proposal>,
    val phase: CoachPhase,
    val draft: String,
    val contextPreview: String,
    val quickPrompts: List<String>,
    val monthSpendUsd: Double,
    val budgetUsd: Double,
    val model: String,
    val effort: Effort,
    val redFlagHit: String?,
)

/** Dialog / banner state beside the frozen [CoachUiState]. */
data class CoachExtras(
    val online: Boolean,
    val queuedDraft: String?,
    val readOnly: Boolean,
    val decideFirst: Boolean,        // "Retry on Sonnet 5" / compaction blocked while proposals are PENDING (§6.7, §6.9)
    val confirmBudget: Boolean,      // 100 % of the monthly budget: "Continue anyway"
    val conversationCostUsd: Double,
    val showContext: Boolean,
)

/**
 * §8.17. All collaborators are explicit so the JVM test can run a real [CoachTurnService] over FakeRepositories and a fake backend.
 * [buildInputs] is the §8.17 `CoachTurnInputs` builder (production: [CoachInputsBuilder]); [recompute] is `planCoordinator.recompute`.
 */
class CoachViewModel(
    conversationId: String,
    private val coach: CoachRepository,
    private val settings: SettingsRepository,
    private val gyms: GymRepository,
    private val plans: PlanRepository,
    private val workouts: WorkoutRepository,
    private val network: NetworkMonitor,
    private val turns: CoachTurnService,
    private val applier: ProposalApplier,
    private val spend: SpendMeter,
    private val knowledge: KnowledgeBase,
    private val clock: Clock,
    private val buildInputs: suspend (skipRedFlagScan: Boolean) -> CoachTurnInputs,
    private val recompute: suspend (String) -> Mesocycle?,
    private val compact: suspend (String) -> ChatConversation,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private var convId: String = conversationId
    private var persisted: List<ChatMessage> = emptyList()
    private var localUser: String? = null
    private var lastSent: String? = null
    private var redFlagText: String? = null
    private var budgetConfirmed = false
    private var turnJob: Job? = null
    private val proposalController = ProposalController(applier, plans, workouts, coach, clock, recompute)

    private val _state = MutableStateFlow(
        CoachUiState(null, emptyList(), emptyMap(), CoachPhase.Idle, "", "", QuickPrompts.DEFAULT, 0.0, 0.0, DEFAULT_MODEL, Effort.LOW, null),
    )
    val state: StateFlow<CoachUiState> = _state.asStateFlow()
    private val _extras = MutableStateFlow(CoachExtras(network.isOnline(), null, false, false, false, 0.0, false))
    val extras: StateFlow<CoachExtras> = _extras.asStateFlow()

    init {
        scope.launch { reload() }
        scope.launch {
            network.observe().collect { online ->
                _extras.update { it.copy(online = online) }
                val queued = _extras.value.queuedDraft
                if (online && queued != null) {
                    _extras.update { it.copy(queuedDraft = null) }
                    startTurn(queued, skipRedFlagScan = false, retryOnSonnet = false)
                }
            }
        }
    }

    // ---- composer -------------------------------------------------------------------------------------------------

    fun onDraft(text: String) = _state.update { it.copy(draft = text, redFlagHit = null) }

    fun onQuickPrompt(text: String) = _state.update { it.copy(draft = text) }

    fun onSend() {
        val s = _state.value
        val text = s.draft.trim()
        if (text.isEmpty() || busy(s.phase) || _extras.value.readOnly) return
        scope.launch {
            val cfg = settings.get()
            if (cfg.backendMode == BackendMode.NONE) {
                _state.update { it.copy(phase = CoachPhase.NoBackend) }
                return@launch
            }
            if (!network.isOnline()) {
                _extras.update { it.copy(queuedDraft = text) }          // exactly one queued draft
                _state.update { it.copy(phase = CoachPhase.Offline(draftQueued = true)) }
                return@launch
            }
            if (!budgetConfirmed && cfg.monthlyBudgetUsd > 0.0 && spend.monthToDate() >= cfg.monthlyBudgetUsd) {
                _extras.update { it.copy(confirmBudget = true) }
                return@launch
            }
            startTurn(text, skipRedFlagScan = false, retryOnSonnet = false)
        }
    }

    fun onBudgetContinue() {
        budgetConfirmed = true
        _extras.update { it.copy(confirmBudget = false) }
        onSend()
    }

    fun onBudgetCancel() = _extras.update { it.copy(confirmBudget = false) }

    fun onCancelQueued() {
        val queued = _extras.value.queuedDraft
        _extras.update { it.copy(queuedDraft = null) }
        _state.update { it.copy(phase = CoachPhase.Idle, draft = queued ?: it.draft) }
    }

    fun onStop() {
        turnJob?.cancel()
        turnJob = null
        localUser = null
        _state.update { it.copy(phase = CoachPhase.Idle, draft = lastSent ?: it.draft) }
        publishMessages()
    }

    fun onRetry() {
        val text = lastSent ?: return
        if (busy(_state.value.phase)) return
        startTurn(text, skipRedFlagScan = true, retryOnSonnet = false)
    }

    fun onContinue() {
        if (busy(_state.value.phase)) return
        startTurn("continue", skipRedFlagScan = true, retryOnSonnet = false)
    }

    /** "Retry on Sonnet 5": blocked (dialog) while this conversation has PENDING proposals. */
    fun onRetryOnSonnet() {
        val text = lastSent ?: return
        if (_state.value.proposals.values.any { it.status == ProposalStatus.PENDING }) {
            _extras.update { it.copy(decideFirst = true) }
            return
        }
        startTurn(text, skipRedFlagScan = true, retryOnSonnet = true)
    }

    fun onDismissDecideFirst() = _extras.update { it.copy(decideFirst = false) }

    fun onRedFlagSendAnyway() {
        val text = redFlagText ?: return
        redFlagText = null
        _state.update { it.copy(redFlagHit = null) }
        startTurn(text, skipRedFlagScan = true, retryOnSonnet = false)
    }

    fun onRedFlagCancel() {
        val text = redFlagText
        redFlagText = null
        _state.update { it.copy(redFlagHit = null, draft = text ?: it.draft) }
    }

    fun onShowContext() = _extras.update { it.copy(showContext = !it.showContext) }

    // ---- turn orchestration ---------------------------------------------------------------------------------------

    private fun busy(p: CoachPhase): Boolean =
        p is CoachPhase.Sending || p is CoachPhase.Streaming || p is CoachPhase.AwaitingTool

    private fun startTurn(text: String, skipRedFlagScan: Boolean, retryOnSonnet: Boolean) {
        lastSent = text
        localUser = text
        _state.update { it.copy(phase = CoachPhase.Sending, draft = "", redFlagHit = null) }
        publishMessages()
        turnJob = scope.launch {
            var streamed = ""
            try {
                val inputs = buildInputs(skipRedFlagScan)
                val flow = if (retryOnSonnet) turns.retryOnSonnet(text, inputs) else turns.send(text, inputs)
                flow.collect { event ->
                    when (event) {
                        is TurnEvent.TextDelta -> {
                            streamed += event.text
                            _state.update { it.copy(phase = CoachPhase.Streaming(streamed)) }
                        }
                        is TurnEvent.ToolUseStarted -> _state.update { it.copy(phase = CoachPhase.AwaitingTool) }
                        is TurnEvent.ToolUseComplete -> {
                            if (event.proposal != null) _state.update { it.copy(phase = CoachPhase.ProposalPending) }
                        }
                        is TurnEvent.Usage -> Unit
                        is TurnEvent.Done -> onDone(event, text)
                        is TurnEvent.Failed -> onFailed(event, text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = CoachPhase.Error(BackendError.Unknown(e.message ?: "unexpected error"), retryable = true), draft = text) }
            } finally {
                localUser = null
                reload()
            }
        }
    }

    private suspend fun onDone(event: TurnEvent.Done, text: String) {
        val details = parseStopDetails(event.stopDetailsJson)
        when (event.stopReason) {
            "red_flag" -> {
                redFlagText = text
                _state.update { it.copy(phase = CoachPhase.Idle, redFlagHit = details["phrase"] ?: "", draft = "") }
            }
            "refusal" -> _state.update { it.copy(phase = CoachPhase.Refused(details["category"])) }
            "max_tokens" -> _state.update { it.copy(phase = CoachPhase.Truncated) }
            "model_context_window_exceeded" -> _state.update { it.copy(phase = CoachPhase.CompactionNeeded) }
            else -> {
                val pending = coach.proposals(convId).any { it.status == ProposalStatus.PENDING }
                _state.update { it.copy(phase = if (pending) CoachPhase.ProposalPending else CoachPhase.Idle) }
            }
        }
    }

    private fun onFailed(event: TurnEvent.Failed, text: String) {
        val restore = if (event.draftKept) text else ""
        when (val err = event.error) {
            BackendError.Offline -> {
                _extras.update { it.copy(queuedDraft = text) }
                _state.update { it.copy(phase = CoachPhase.Offline(draftQueued = true), draft = "") }
            }
            BackendError.NotConfigured -> _state.update { it.copy(phase = CoachPhase.NoBackend, draft = restore) }
            BackendError.SpendCapReached -> _state.update { it.copy(phase = CoachPhase.SpendCapReached, draft = restore) }
            else -> _state.update { it.copy(phase = CoachPhase.Error(err, isRetryable(err)), draft = restore) }
        }
    }

    // ---- proposals (logic in ProposalController, MessageBubble.kt) -----------------------------------------------

    fun onApply(proposalId: String) {
        val p = _state.value.proposals[proposalId] ?: return
        scope.launch { proposalController.apply(p); reload() }
    }

    fun onReject(proposalId: String, reason: String?) {
        val p = _state.value.proposals[proposalId] ?: return
        scope.launch { applier.reject(p, reason); reload() }
    }

    fun onLater(proposalId: String) {
        val p = _state.value.proposals[proposalId] ?: return
        scope.launch { applier.defer(p); reload() }
    }

    fun canUndo(p: Proposal): Boolean = proposalController.canUndo(p)

    fun onUndo(proposalId: String) {
        val p = _state.value.proposals[proposalId] ?: return
        scope.launch { proposalController.undo(p); reload() }
    }

    // ---- conversations --------------------------------------------------------------------------------------------

    /** Closes the open conversation and creates a fresh one; [onCreated] receives its id (the screen navigates to it). */
    fun onNewConversation(onCreated: (String) -> Unit) {
        scope.launch {
            val created = startNewConversation(coach, settings, knowledge, clock)
            convId = created.id
            reload()
            onCreated(created.id)
        }
    }

    fun onCompactAndContinue(onCreated: (String) -> Unit) {
        if (_state.value.proposals.values.any { it.status == ProposalStatus.PENDING }) {
            _extras.update { it.copy(decideFirst = true) }
            return
        }
        scope.launch {
            try {
                val fresh = compact(convId)
                convId = fresh.id
                _state.update { it.copy(phase = CoachPhase.Idle) }
                reload()
                onCreated(fresh.id)
            } catch (e: Exception) {
                _state.update { it.copy(phase = CoachPhase.Error(BackendError.Unknown(e.message ?: "compaction failed"), retryable = true)) }
            }
        }
    }

    // ---- state assembly -------------------------------------------------------------------------------------------

    private suspend fun reload() {
        val open = coach.openConversation()
        persisted = coach.messages(convId).filter { !(it.role == ChatRole.USER && it.displayText.isEmpty()) }
        val props = coach.proposals(convId).associateBy { it.id }
        val cfg = settings.get()
        val month = spend.monthToDate()
        val convCost = coach.spendSince(Instant.EPOCH).filter { it.conversationId == convId }.sumOf { it.costUsd }
        val current = coach.conversation(convId)
        val readOnly = current != null && (open == null || open.id != current.id || !current.isDefault)
        val preview = contextLine(gyms, plans, clock, cfg.activeGymId)
        _state.update {
            it.copy(
                conversation = current, proposals = props, monthSpendUsd = month, budgetUsd = cfg.monthlyBudgetUsd,
                model = current?.model ?: cfg.modelId, effort = current?.effort ?: cfg.effort, contextPreview = preview,
                phase = if (it.phase == CoachPhase.Idle && cfg.backendMode == BackendMode.NONE) CoachPhase.NoBackend else it.phase,
            )
        }
        _extras.update { it.copy(readOnly = readOnly, conversationCostUsd = convCost, online = network.isOnline()) }
        publishMessages()
    }

    private fun publishMessages() {
        val local = localUser
        val shown = if (local == null) persisted else persisted + ChatMessage(
            id = LOCAL_ID, conversationId = convId, seq = Int.MAX_VALUE, role = ChatRole.USER, contentJson = "[]",
            displayText = local, createdAt = clock.now(),
        )
        _state.update { it.copy(messages = shown) }
    }

    companion object {
        const val LOCAL_ID = "local-pending"
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}
