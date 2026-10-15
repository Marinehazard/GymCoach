// core/src/main/kotlin/com/gymcoach/core/coach/CoachTurnService.kt
package com.gymcoach.core.coach

import com.gymcoach.core.coach.wire.RequestBuilder
import com.gymcoach.core.coach.wire.StreamAccumulator
import com.gymcoach.core.coach.wire.ToolDefinition
import com.gymcoach.core.coach.wire.ToolUseBlock
import com.gymcoach.core.equipment.EquipmentResolver
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.EnergyModel
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.SessionEquipment
import com.gymcoach.core.model.SessionPlan
import com.gymcoach.core.model.SessionStatus
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.mutation.MutationValidator
import com.gymcoach.core.mutation.ValidationResult
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.repo.BackendError
import com.gymcoach.core.repo.BackendException
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.MetricsRepository
import com.gymcoach.core.repo.NetworkMonitor
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.ProgressionRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.util.Hashing
import com.gymcoach.core.util.IdGen
import com.gymcoach.core.volume.WeeklyLedger
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Everything one turn needs that only :app can compute (PlanCoordinator.buildInputs()/constants() live in :app, §7.6;
 * :core never calls them). Built by CoachViewModel.onSend (§8.17) once per send; CoachTurnService derives the §6.5
 * ContextInputs from it plus its own repositories, and builds `MutationValidator(knowledge, constants)` from it per turn.
 */
data class CoachTurnInputs(
    val today: TodayContext,
    val todaySession: SessionPlan?,           // the open session's frozen plan, else PlanCoordinator.realizeToday(today), else null (rest day / no plan)
    val planInputs: PlanInputs,               // PlanCoordinator.buildInputs(): the 12-week/8-checkin/14-day windows, patternLastTrained, anchor, deloadTriggersProposed …
    val constants: PlannerConstants,          // PlanCoordinator.constants() — user overrides included
    val mesocycle: Mesocycle?,                // PlanRepository.getCurrent()
    val trainingAge: TrainingAge,             // Planner.normalize(planInputs, today.date).trainingAge (§4.2, DEF-01)
    val ledger: WeeklyLedger?,                // WeeklyLedger.build(currentWeek, weekSets, weekSessions, todaySession, kb); null without a plan
    val nutrition: NutritionTargets?,         // MacroTargets.compute(...) (§5.5); null when no body weight is known
    val energy: EnergyModel?,                 // EnergyEstimator.model(...); null when no body weight is known
    val trendKgPerWeek: Double?,              // WeightTrend.delta14Kg(points) / 2, null below nut15MinDays
    val weightMa7Kg: Double?,                 // latest WeightTrendPoint.ma7Kg, null without weigh-ins
    val skipRedFlagScan: Boolean = false,     // true only for the "It's not that — send anyway" re-send (§6.12); the text is sent unchanged
)

/**
 * §6.7 turn orchestration. One user turn = red-flag / offline / gym preconditions, the tool_result echo of the last
 * assistant message, the `<context>` snapshot (first message) or `<delta>`, then a chain of at most 1 + 3 API
 * responses (automatic follow-ups only while every tool_use is read-only). Every request/response pair is persisted
 * as soon as its `message_stop` arrives (consecutive `seq`, content verbatim), so the next request's prefix is
 * byte-identical after process death. Refusals and failures persist nothing for their pair. Logged sets are never
 * touched; proposals are only stored (PENDING / INVALID) — applying them is [ProposalApplier]'s job after the user
 * confirms.
 */
class CoachTurnService(
    private val knowledge: KnowledgeBase, private val profile: ProfileRepository, private val gyms: GymRepository, private val plans: PlanRepository,
    private val workouts: WorkoutRepository, private val progression: ProgressionRepository, private val metrics: MetricsRepository,
    private val coach: CoachRepository, private val settings: SettingsRepository, private val backendFactory: (BackendMode) -> ClaudeBackend,
    private val toolExecutor: ToolExecutor, private val spend: SpendMeter, private val network: NetworkMonitor, private val clock: Clock,
) {
    companion object {
        const val MAX_FOLLOW_UPS: Int = 3
        const val SONNET_MODEL: String = "claude-sonnet-5"
        const val PROPOSAL_TTL_DAYS: Long = 7
        const val BUDGET_EXHAUSTED: String = "tool budget for this turn exhausted; answer with what you have"
        const val PENDING_NOTE: String = "the user has not decided; do not re-propose unless asked"
        const val NO_GYM: String = "no gym configured"
        private val GYM_ID_IN_CONTEXT = Regex("\"gym_today\":\\{\"id\":\"((?:[^\"\\\\]|\\\\.)*)\"")
    }

    private class StreamFailure(val error: BackendError) : Exception(error.toString())

    private val contextBuilder = CoachContextBuilder(knowledge, TokenEstimator)
    private val resolver = EquipmentResolver(knowledge)
    private val systemText: String by lazy { SystemPrompt.assemble(knowledge) }
    private val promptHash: String by lazy { Hashing.sha256Hex(systemText) }

    /** §6.7. One user turn: snapshot/delta from `inputs`, tool chain, persistence; proposals validated with MutationValidator(knowledge, inputs.constants). */
    fun send(userText: String, inputs: CoachTurnInputs): Flow<TurnEvent> = flow { runTurn(userText, inputs, sonnetRetry = false) }

    /** "Retry on Sonnet 5" after a refusal (§6.7): new conversation on claude-sonnet-5 for this conversation only, fresh snapshot from `inputs`, re-sends `userText`. */
    fun retryOnSonnet(userText: String, inputs: CoachTurnInputs): Flow<TurnEvent> = flow { runTurn(userText, inputs, sonnetRetry = true) }

    suspend fun expirePendingProposals(now: Instant) {
        val today = now.atZone(clock.zone()).toLocalDate()
        for (conv in coach.listConversations()) {
            for (p in coach.proposals(conv.id)) {
                if ((p.status == ProposalStatus.PENDING || p.status == ProposalStatus.DEFERRED) && p.expiresOn.isBefore(today)) {
                    coach.decideProposal(p.id, ProposalStatus.EXPIRED, now, "{\"status\":\"expired\"}", null)
                }
            }
        }
    }

    // ---- the turn -----------------------------------------------------------------------------------------------------

    private suspend fun FlowCollector<TurnEvent>.runTurn(userText: String, inputs: CoachTurnInputs, sonnetRetry: Boolean) {
        // 0. preconditions — nothing is built or sent when one fails
        if (!inputs.skipRedFlagScan) {
            val hit = RedFlagScanner.scan(userText)
            if (hit != null) {
                val details = JsonObject(linkedMapOf("phrase" to JsonPrimitive(hit.phrase), "emergency" to JsonPrimitive(hit.emergency)))
                emit(TurnEvent.Done("red_flag", encode(details), null))
                return
            }
        }
        if (!network.isOnline()) {
            emit(TurnEvent.Failed(BackendError.Offline, draftKept = true))
            return
        }
        val gym = resolveGym(inputs.today.gymId)
        if (gym == null) {
            emit(TurnEvent.Failed(BackendError.InvalidRequest(0, NO_GYM), draftKept = true))
            return
        }
        val validator = MutationValidator(knowledge, inputs.constants)
        val contract = ToolContract(knowledge, validator, clock)
        val cfg = settings.get()
        expirePendingProposals(clock.now())

        // 1. conversation (frozen hash / contract / model / effort / backend)
        var conv = openOrCreate(cfg, sonnetRetry)
        val bridge = conv.backend == BackendMode.HOME_BRIDGE
        val convSettings = cfg.copy(modelId = conv.model, effort = conv.effort, backendMode = conv.backend)
        val tools: List<ToolDefinition> = if (bridge) ToolSchemas.proposals else ToolSchemas.all
        val history = ArrayList(coach.messages(conv.id).sortedBy { it.seq })

        // 2. new user content: tool_results first (one per tool_use of the last assistant message), then context, then text
        val content = ArrayList<JsonElement>()
        val proposalResults = LinkedHashMap<String, String>()
        val toAnswer = ArrayList<String>()
        val last = history.lastOrNull()?.takeIf { it.role == ChatRole.ASSISTANT }
        if (last != null) {
            val proposals = coach.proposals(conv.id).associateBy { it.id }
            val stored = parseResults(last.toolResultsJson)
            for ((id, name) in RequestBuilder.toolUses(RequestBuilder.parseContent(last.contentJson))) {
                if (ToolSchemas.isReadOnly(name)) {
                    val text = stored[id] ?: ToolExecutor.errorJson("error", listOf(BUDGET_EXHAUSTED))
                    if (!bridge) content.add(RequestBuilder.toolResultBlock(id, text, ToolExecutor.isErrorResult(text)))
                } else {
                    val p = proposals[id]
                    val text = proposalResult(p)
                    val isError = p == null || p.status == ProposalStatus.INVALID || p.status == ProposalStatus.FAILED
                    if (bridge) proposalResults[id] = text else content.add(RequestBuilder.toolResultBlock(id, text, isError))
                    if (p != null && !p.resultSent) toAnswer.add(id)
                }
            }
        }
        if (history.isEmpty()) {
            conv.seedSummary?.takeIf { it.isNotBlank() }?.let { content.add(RequestBuilder.textBlock(contextBuilder.summaryBlock(it))) }
            content.add(RequestBuilder.textBlock(contextBuilder.snapshot(contextInputs(inputs, gym, conv, cfg))))
        } else {
            content.add(RequestBuilder.textBlock(contextBuilder.delta(deltaInputs(inputs, gym, conv, history), proposalResults)))
        }
        content.add(RequestBuilder.textBlock(userText))

        // 3. body
        var newUser = JsonArray(content)
        var body: JsonObject = try {
            RequestBuilder.build(convSettings, systemText, tools, history, newUser)
        } catch (e: IllegalArgumentException) {
            emit(TurnEvent.Failed(BackendError.InvalidRequest(0, e.message ?: "invalid request"), draftKept = true))
            return
        }

        // 4. the chain
        val backend = backendFactory(conv.backend)
        var displayText = userText
        var followUps = 0
        var lastAssistantId: String? = history.lastOrNull { it.role == ChatRole.ASSISTANT }?.id
        var firstPair = true
        while (true) {
            val acc = StreamAccumulator()
            val assistantId = IdGen.newId()
            val readOnly = LinkedHashMap<String, ToolExecution>()
            val newProposals = ArrayList<Proposal>()
            var requestId: String? = null
            try {
                backend.stream(body).collect { frame ->
                    if (requestId == null) requestId = frame.requestId
                    for (ev in acc.accept(frame.event)) {
                        if (ev is TurnEvent.Failed) throw StreamFailure(ev.error)
                        emit(ev)
                    }
                    for (tu in acc.takeCompletedToolUses()) {
                        if (ToolSchemas.isReadOnly(tu.name)) {
                            val exec = executeReadOnly(tu, inputs.today, bridge)
                            readOnly[tu.id] = exec
                            emit(TurnEvent.ToolUseComplete(null, exec.resultJson, if (exec.isError) exec.resultJson else null))
                        } else {
                            val p = buildProposal(tu, conv, assistantId, contract, inputs)
                            newProposals.add(p)
                            emit(TurnEvent.ToolUseComplete(p, null, if (p.status == ProposalStatus.INVALID) p.validationErrors.joinToString("; ") else null))
                        }
                    }
                }
            } catch (e: StreamFailure) {
                emit(TurnEvent.Failed(e.error, draftKept = true))
                return
            } catch (e: BackendException) {
                emit(TurnEvent.Failed(e.error, draftKept = true))
                return
            }
            if (!acc.isComplete) {
                emit(TurnEvent.Failed(BackendError.Transport("stream ended before message_stop", acc.producedOutput), draftKept = true))
                return
            }
            val msg = acc.result()
            val stop = msg.stopReason ?: "end_turn"
            val stopDetailsJson = msg.stopDetails?.let { encode(it) }
            val record = spend.record(conv.model, msg.usage, conv.backend, conv.id)
            emit(TurnEvent.Usage(msg.usage, record.costUsd))
            if (stop == "refusal") {
                emit(TurnEvent.Done("refusal", stopDetailsJson, lastAssistantId))
                return
            }
            val toolUses = acc.toolUses()
            val allReadOnly = toolUses.isNotEmpty() && toolUses.all { ToolSchemas.isReadOnly(it.name) }
            val follow = stop == "tool_use" && allReadOnly && followUps < MAX_FOLLOW_UPS && !bridge
            if (stop == "tool_use" && allReadOnly && !follow) {
                for (tu in toolUses) readOnly[tu.id] = ToolExecution(ToolExecutor.errorJson("error", listOf(BUDGET_EXHAUSTED)), isError = true)
            }

            // 5. persist this request/response pair
            val now = clock.now()
            val seq = (history.maxOfOrNull { it.seq } ?: 0) + 1
            val assistantText = textOf(msg.content)
            val userMsg = ChatMessage(
                id = IdGen.newId(), conversationId = conv.id, seq = seq, role = ChatRole.USER,
                contentJson = RequestBuilder.contentJson(newUser), displayText = displayText, createdAt = now,
            )
            val assistantMsg = ChatMessage(
                id = assistantId, conversationId = conv.id, seq = seq + 1, role = ChatRole.ASSISTANT,
                contentJson = RequestBuilder.contentJson(msg.content), displayText = assistantText, createdAt = now,
                usage = msg.usage, requestId = requestId, stopReason = stop, stopDetailsJson = stopDetailsJson,
                caveatAppended = SupplementCaveat.check(assistantText),
                toolResultsJson = if (readOnly.isEmpty()) null else encode(JsonObject(readOnly.mapValues { JsonPrimitive(it.value.resultJson) })),
            )
            coach.addMessage(userMsg)
            coach.addMessage(assistantMsg)
            for (p in newProposals) coach.addProposal(p)
            if (firstPair) {
                for (id in toAnswer) coach.proposal(id)?.let { coach.updateProposal(it.copy(resultSent = true)) }
            }
            var lastInput = msg.usage.totalInput
            if (stop == "model_context_window_exceeded") lastInput = maxOf(lastInput, TranscriptCompactor.MAX_INPUT_TOKENS + 1)
            conv = conv.copy(
                assistantTurns = conv.assistantTurns + 1,
                lastInputTokens = lastInput,
                status = if (toolUses.isNotEmpty()) ConversationStatus.AWAITING_TOOL_RESULTS else ConversationStatus.OPEN,
            )
            coach.updateConversation(conv)
            history.add(userMsg)
            history.add(assistantMsg)
            lastAssistantId = assistantId
            firstPair = false

            if (follow) {
                followUps++
                newUser = JsonArray(toolUses.map { tu ->
                    val exec = readOnly[tu.id] ?: ToolExecution(ToolExecutor.errorJson("error", listOf(BUDGET_EXHAUSTED)), isError = true)
                    RequestBuilder.toolResultBlock(tu.id, exec.resultJson, exec.isError)
                })
                displayText = ""
                body = RequestBuilder.build(convSettings, systemText, tools, history, newUser)
                continue
            }
            emit(TurnEvent.Done(stop, stopDetailsJson, assistantId))
            return
        }
    }

    // ---- conversation -------------------------------------------------------------------------------------------------

    private suspend fun openOrCreate(cfg: AppSettings, sonnetRetry: Boolean): ChatConversation {
        val open = coach.openConversation()
        if (sonnetRetry) {
            if (open != null) coach.updateConversation(open.copy(isDefault = false))
            return newConversation(cfg, SONNET_MODEL, null)
        }
        if (open == null || open.status == ConversationStatus.COMPACTED || open.status == ConversationStatus.CLOSED) return newConversation(cfg, cfg.modelId, null)
        if (open.systemPromptHash != promptHash || open.toolContractVersion != ToolContract.CONTRACT_VERSION) {
            // app update changed the prompt or the tool contract: the frozen prefix is gone, start over (§6.4)
            val empty = coach.messages(open.id).isEmpty()
            coach.updateConversation(open.copy(status = ConversationStatus.CLOSED, endedAt = clock.now(), isDefault = false))
            return newConversation(cfg, open.model, if (empty) open.seedSummary else null)
        }
        return open
    }

    private suspend fun newConversation(cfg: AppSettings, model: String, seedSummary: String?): ChatConversation {
        val conv = ChatConversation(
            id = IdGen.newId(), startedAt = clock.now(), backend = cfg.backendMode, model = model, effort = cfg.effort,
            systemPromptHash = promptHash, toolContractVersion = ToolContract.CONTRACT_VERSION, seedSummary = seedSummary,
            status = ConversationStatus.OPEN,
        )
        coach.createConversation(conv)
        return conv
    }

    // ---- context ------------------------------------------------------------------------------------------------------

    private suspend fun resolveGym(gymId: String): GymSet? =
        gyms.get(gymId)?.takeUnless { it.archived } ?: gyms.getAll().firstOrNull { it.isDefault } ?: gyms.getAll().firstOrNull()

    /** §6.5: ContextInputs derived from [CoachTurnInputs] plus the service's repositories (no planner, no PlanCoordinator). */
    private suspend fun contextInputs(inputs: CoachTurnInputs, gym: GymSet, conv: ChatConversation, cfg: AppSettings): ContextInputs {
        val today = inputs.today
        val pi = inputs.planInputs
        val equipment = SessionEquipment(gym, today.extraItems, today.removedEquipmentIds.toSet(), today.spotter)
        val week = inputs.mesocycle?.weeks?.firstOrNull { !today.date.isBefore(it.startDate) && today.date.isBefore(it.startDate.plusDays(7)) }
        val weekSessions = if (week == null) emptyList() else pi.recentSessions.filter { !it.date.isBefore(week.startDate) && it.date.isBefore(week.startDate.plusDays(7)) }
        val from7 = today.date.minusDays(6)
        val others = coach.listConversations().filter { it.id != conv.id }
        val previousEnd: Instant? = others.mapNotNull { it.endedAt }.maxOrNull()
        val appliedSince = if (previousEnd == null) emptyList() else others.flatMap { coach.proposals(it.id) }
            .filter { p -> p.status == ProposalStatus.APPLIED && p.decidedAt?.isAfter(previousEnd) == true }
            .sortedBy { it.decidedAt }
        return ContextInputs(
            today = today, units = cfg.unitSystem, profile = pi.profile, trainingAge = inputs.trainingAge, weightMa7Kg = inputs.weightMa7Kg,
            gym = gym, availableExerciseIds = resolver.availableExerciseIds(equipment).toList(), mesocycle = inputs.mesocycle,
            weekIndex = week?.index ?: 0, weekSessions = weekSessions, ledger = inputs.ledger, todaySession = inputs.todaySession,
            readiness7d = pi.readinessHistory.filter { !it.date.isBefore(from7) && !it.date.isAfter(today.date) },
            states = pi.progressionStates, recentSessions = pi.recentSessions, recentSets = pi.recentSets,
            openQuestions = metrics.activeQuestions(today.date), lastCheckin = pi.weeklyCheckins.maxByOrNull { it.weekStart },
            nutrition = inputs.nutrition, energy = inputs.energy, trendKgPerWeek = inputs.trendKgPerWeek,
            pendingProposals = coach.proposals(conv.id).filter { it.status == ProposalStatus.PENDING }, appliedSince = appliedSince,
        )
    }

    /** What changed since the last persisted message of the conversation (§6.5 delta). */
    private suspend fun deltaInputs(inputs: CoachTurnInputs, gym: GymSet, conv: ChatConversation, history: List<ChatMessage>): DeltaInputs {
        val since = history.last().createdAt
        val pi = inputs.planInputs
        val lastUserDate: LocalDate? = history.lastOrNull { it.role == ChatRole.USER && it.displayText.isNotEmpty() }?.createdAt?.atZone(clock.zone())?.toLocalDate()
        val logged = pi.recentSessions.filter { s -> s.status == SessionStatus.COMPLETED && (s.finishedAt ?: s.startedAt)?.isAfter(since) == true }
        val loggedIds = logged.map { it.id }.toSet()
        val decided = coach.proposals(conv.id).filter { p -> p.decidedAt?.isAfter(since) == true }
        var lastGymId: String? = null
        for (m in history.asReversed()) {
            if (m.role != ChatRole.USER) continue
            val hit = GYM_ID_IN_CONTEXT.findAll(m.contentJson.replace("\\\"", "\"")).lastOrNull()
            if (hit != null) { lastGymId = hit.groupValues[1]; break }
        }
        val planChanged = pi.overrides.filter { o -> o.createdAt.isAfter(since) || o.revokedAt?.isAfter(since) == true }.map { mutationType(it.mutation) }
        val readiness = inputs.today.readiness?.takeIf { it.loggedAt.isAfter(since) }
            ?: pi.readinessHistory.filter { it.loggedAt.isAfter(since) }.maxByOrNull { it.loggedAt }
        return DeltaInputs(
            today = if (lastUserDate == null || lastUserDate != inputs.today.date) inputs.today.date else null,
            sessionsLogged = logged,
            sets = pi.recentSets.filter { it.sessionId in loggedIds },
            proposals = decided,
            profileChanged = pi.profile.updatedAt.isAfter(since),
            gymChanged = lastGymId != null && lastGymId != gym.id,
            planChangedFields = planChanged,
            readiness = readiness,
            checkinAnswered = pi.weeklyCheckins.any { it.completedAt?.isAfter(since) == true },
            gym = gym,
        )
    }

    // ---- tools --------------------------------------------------------------------------------------------------------

    private suspend fun executeReadOnly(tu: ToolUseBlock, today: TodayContext, bridge: Boolean): ToolExecution {
        if (tu.inputError != null) return ToolExecutor.invalid(tu.inputError)
        if (bridge) return ToolExecutor.invalid("read-only tools are not available over the home bridge")
        return toolExecutor.execute(tu.name, tu.input, today)
    }

    private fun buildProposal(tu: ToolUseBlock, conv: ChatConversation, assistantId: String, contract: ToolContract, inputs: CoachTurnInputs): Proposal {
        val parsed = if (tu.inputError != null) ParsedTool.Invalid(listOf(tu.inputError)) else contract.parse(tu.name, tu.input)
        var mutation: PlanMutation? = null
        var status = ProposalStatus.INVALID
        var warnings: List<String> = emptyList()
        var errors: List<String> = emptyList()
        when (parsed) {
            is ParsedTool.ProposalCall -> when (val v = contract.validate(parsed.mutation, inputs.planInputs, inputs.mesocycle, inputs.today, inputs.nutrition)) {
                is ValidationResult.Valid -> { mutation = parsed.mutation; status = ProposalStatus.PENDING; warnings = v.warnings }
                is ValidationResult.Invalid -> errors = v.errors
            }
            is ParsedTool.Invalid -> errors = parsed.errors
            is ParsedTool.ReadOnly -> errors = listOf("'${tu.name}' is a read-only tool")
        }
        val rationale = (tu.input["rationale"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""
        val version = (tu.input["contract_version"] as? JsonPrimitive)?.intOrNull ?: ToolContract.CONTRACT_VERSION
        return Proposal(
            id = tu.id, conversationId = conv.id, assistantMessageId = assistantId, toolName = tu.name, contractVersion = version,
            payloadJson = encode(tu.input), mutation = mutation, rationale = rationale, status = status,
            validationWarnings = warnings, validationErrors = errors, createdAt = clock.now(),
            expiresOn = clock.today().plusDays(PROPOSAL_TTL_DAYS),
        )
    }

    /** The §6.7 tool_result text for a proposal tool_use. */
    private fun proposalResult(p: Proposal?): String {
        val m = LinkedHashMap<String, JsonElement>()
        if (p == null) return ToolExecutor.errorJson("error", listOf("proposal not found on the phone; propose again if still needed"))
        when (p.status) {
            ProposalStatus.APPLIED -> {
                m["status"] = JsonPrimitive("applied")
                m["diff"] = parseOrString(p.resultSummary ?: "{}")
            }
            ProposalStatus.REJECTED -> {
                m["status"] = JsonPrimitive("rejected")
                m["reason"] = JsonPrimitive(p.resultSummary ?: ProposalApplier.REJECTED_SUMMARY)
            }
            ProposalStatus.PENDING, ProposalStatus.DEFERRED -> {
                m["status"] = JsonPrimitive("pending")
                m["note"] = JsonPrimitive(PENDING_NOTE)
            }
            ProposalStatus.EXPIRED -> m["status"] = JsonPrimitive("expired")
            ProposalStatus.INVALID -> {
                m["status"] = JsonPrimitive("invalid")
                m["errors"] = JsonArray(p.validationErrors.map { JsonPrimitive(it) })
            }
            ProposalStatus.FAILED -> {
                m["status"] = JsonPrimitive("failed")
                m["message"] = JsonPrimitive(p.resultSummary ?: "")
            }
        }
        return encode(JsonObject(m))
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private fun parseResults(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val obj = try { CoreJson.wire.parseToJsonElement(json) as? JsonObject } catch (e: Exception) { null } ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in obj) out[k] = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: encode(v)
        return out
    }

    private fun textOf(content: JsonArray): String = content.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        if ((o["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
        (o["text"] as? JsonPrimitive)?.contentOrNull
    }.joinToString("")

    private fun mutationType(m: PlanMutation): String =
        ((CoreJson.stored.encodeToJsonElement(PlanMutation.serializer(), m) as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull ?: "override"

    private fun parseOrString(json: String): JsonElement = try { CoreJson.wire.parseToJsonElement(json) } catch (e: Exception) { JsonPrimitive(json) }

    private fun encode(el: JsonElement): String = CoreJson.wire.encodeToString(JsonElement.serializer(), el)
}
