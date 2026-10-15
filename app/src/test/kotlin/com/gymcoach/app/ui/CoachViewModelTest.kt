package com.gymcoach.app.ui

import com.gymcoach.app.fakes.FakeCoachRepository
import com.gymcoach.app.fakes.FakeRepositories
import com.gymcoach.app.ui.coach.CoachPhase
import com.gymcoach.app.ui.coach.CoachViewModel
import com.gymcoach.app.ui.coach.startNewConversation
import com.gymcoach.core.coach.CoachTurnInputs
import com.gymcoach.core.coach.CoachTurnService
import com.gymcoach.core.coach.ProposalApplier
import com.gymcoach.core.coach.SpendMeter
import com.gymcoach.core.coach.ToolExecutor
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.ClasspathKnowledgeSource
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeLoader
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.OverrideSource
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanMutation
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.StreamFrame
import com.gymcoach.core.repo.TestResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * CoachViewModel over a REAL CoachTurnService, FakeRepositories and a scripted backend that replays Messages-API stream events
 * (R1 section 3.6 shapes). Pins: the card appears, Apply/Reject reach the next request as the FIRST tool_result, the read-only
 * auto follow-up chain is capped, an offline draft is queued and sent on reconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachViewModelTest {

    private val kb: KnowledgeBase by lazy { KnowledgeLoader(CoreJson.assets).load(ClasspathKnowledgeSource()) }

    /** FakeCoachRepository with the DAO semantics of CoachDao.open(): status open|awaiting_tool_results AND is_default. */
    private class DaoLikeCoach(private val inner: FakeCoachRepository = FakeCoachRepository()) : CoachRepository by inner {
        override suspend fun openConversation(): ChatConversation? =
            inner.listConversations().lastOrNull { (it.status == ConversationStatus.OPEN || it.status == ConversationStatus.AWAITING_TOOL_RESULTS) && it.isDefault }
    }

    private class ScriptedBackend(private val script: (Int) -> List<StreamFrame>) : ClaudeBackend {
        override val mode: BackendMode = BackendMode.DIRECT_API
        val requests = ArrayList<JsonObject>()
        override fun stream(body: JsonObject): Flow<StreamFrame> = flow {
            val n = requests.size
            requests.add(body)
            for (f in script(n)) emit(f)
        }
        override suspend fun complete(body: JsonObject): StreamFrame = error("complete() is not used by these tests")
        override suspend fun test(): TestResult = TestResult(null, null)
    }

    private fun ev(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): StreamFrame =
        StreamFrame(buildJsonObject { put("type", type); build() })

    private fun start(): StreamFrame = ev("message_start") {
        put("message", buildJsonObject {
            put("id", "msg_test"); put("type", "message"); put("role", "assistant"); put("model", "claude-opus-5")
            put("content", JsonArray(emptyList())); put("stop_reason", JsonNull); put("stop_sequence", JsonNull)
            put("usage", buildJsonObject { put("input_tokens", 1200); put("output_tokens", 1); put("cache_read_input_tokens", 0); put("cache_creation_input_tokens", 0) })
        })
    }

    private fun textBlock(index: Int, text: String): List<StreamFrame> = listOf(
        ev("content_block_start") { put("index", index); put("content_block", buildJsonObject { put("type", "text"); put("text", "") }) },
        ev("content_block_delta") { put("index", index); put("delta", buildJsonObject { put("type", "text_delta"); put("text", text) }) },
        ev("content_block_stop") { put("index", index) },
    )

    private fun toolBlock(index: Int, id: String, name: String, input: JsonObject): List<StreamFrame> = listOf(
        ev("content_block_start") {
            put("index", index)
            put("content_block", buildJsonObject { put("type", "tool_use"); put("id", id); put("name", name); put("input", buildJsonObject { }) })
        },
        ev("content_block_delta") { put("index", index); put("delta", buildJsonObject { put("type", "input_json_delta"); put("partial_json", "") }) },
        ev("content_block_delta") { put("index", index); put("delta", buildJsonObject { put("type", "input_json_delta"); put("partial_json", input.toString()) }) },
        ev("content_block_stop") { put("index", index) },
    )

    private fun finish(stop: String): List<StreamFrame> = listOf(
        ev("message_delta") {
            put("delta", buildJsonObject { put("stop_reason", stop); put("stop_sequence", JsonNull) })
            put("usage", buildJsonObject { put("output_tokens", 40) })
        },
        ev("message_stop"),
    )

    private fun textTurn(text: String): List<StreamFrame> = listOf(start()) + textBlock(0, text) + finish("end_turn")

    private fun proposalTurn(id: String): List<StreamFrame> = listOf(start()) + textBlock(0, "I suggest a strength focus.") +
        toolBlock(1, id, "propose_change_goal", buildJsonObject {
            put("contract_version", 1); put("goal", "strength"); put("start_next_week", true); put("rationale", "You asked for heavier lifts (DEF-06).")
        }) + finish("tool_use")

    private fun lookupTurn(id: String): List<StreamFrame> = listOf(start()) + textBlock(0, "Let me check.") +
        toolBlock(1, id, "lookup_exercises", buildJsonObject { put("query", "squat"); put("available_only", false) }) + finish("tool_use")

    private class Harness(val repos: FakeRepositories, val coach: CoachRepository, val backend: ScriptedBackend, val vm: CoachViewModel)

    private suspend fun TestScope.harness(mode: BackendMode = BackendMode.DIRECT_API, script: (Int) -> List<StreamFrame>): Harness {
        val repos = FakeRepositories()
        val coach = DaoLikeCoach()
        val backend = ScriptedBackend(script)
        val clock = repos.clock
        val gym = Presets.gymFromPreset(kb, Presets.HOTEL_GYM, "Hotel Ulm", "g-hotel", clock.now()).copy(isDefault = true)
        repos.gyms.upsert(gym)
        val profile = Profile(
            sex = Sex.MALE, birthYear = 1990, height = EnteredLength(178.0, LengthUnit.CM, 178.0), trainingMonthsConsistent = 12,
            goal = Goal.HYPERTROPHY, daysPerWeek = 3, sessionMinutes = 60, updatedAt = clock.now(),
        )
        repos.profile.save(profile)
        repos.settings.update { it.copy(backendMode = mode, activeGymId = gym.id) }
        val today = TodayContext(clock.today(), gym.id)
        val planInputs = PlanInputs(
            profile = profile, gyms = listOf(gym), defaultGymId = gym.id, overrides = emptyList(), progressionStates = emptyList(),
            recentSessions = emptyList(), recentSets = emptyList(), bodyMetrics = emptyList(), weeklyCheckins = emptyList(), readinessHistory = emptyList(),
        )
        val inputs = CoachTurnInputs(
            today = today, todaySession = null, planInputs = planInputs, constants = PlannerConstants.DEFAULT, mesocycle = null,
            trainingAge = TrainingAge.INTERMEDIATE, ledger = null, nutrition = null, energy = null, trendKgPerWeek = null, weightMa7Kg = null,
        )
        val spend = SpendMeter(coach, clock)
        val service = CoachTurnService(
            knowledge = kb, profile = repos.profile, gyms = repos.gyms, plans = repos.plans, workouts = repos.workouts, progression = repos.progression,
            metrics = repos.metrics, coach = coach, settings = repos.settings, backendFactory = { backend },
            toolExecutor = ToolExecutor(kb, repos.workouts, repos.progression, repos.gyms, repos.plans), spend = spend, network = repos.network, clock = clock,
        )
        val conversation = startNewConversation(coach, repos.settings, kb, clock)
        val vm = CoachViewModel(
            conversationId = conversation.id, coach = coach, settings = repos.settings, gyms = repos.gyms, plans = repos.plans, workouts = repos.workouts,
            network = repos.network, turns = service, applier = ProposalApplier(repos.plans, coach, clock), spend = spend, knowledge = kb, clock = clock,
            buildInputs = { skip -> inputs.copy(skipRedFlagScan = skip) }, recompute = { null }, compact = { error("compaction is not used here") },
            scopeOverride = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
        )
        advanceUntilIdle()
        return Harness(repos, coach, backend, vm)
    }

    private fun firstToolResult(body: JsonObject): JsonObject? =
        body["messages"]?.jsonArray?.lastOrNull()?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject

    private suspend fun TestScope.send(h: Harness, text: String) {
        h.vm.onDraft(text)
        h.vm.onSend()
        advanceUntilIdle()
    }

    @Test fun proposalCardAppearsAfterTheAssistantText() = runTest {
        val h = harness { n -> if (n == 0) proposalTurn("toolu_01") else textTurn("ok") }
        send(h, "Switch me to a strength focus")
        val s = h.vm.state.value
        val card = assertNotNull(s.proposals["toolu_01"])
        assertEquals(ProposalStatus.PENDING, card.status)
        assertTrue(card.mutation is PlanMutation.ChangeGoal)
        assertEquals(CoachPhase.ProposalPending, s.phase)
        assertEquals("", s.draft)
        val assistant = s.messages.last()
        assertTrue(assistant.displayText.contains("strength focus"))
        assertEquals(assistant.id, card.assistantMessageId)
        assertEquals(1, h.backend.requests.size)
    }

    @Test fun apply_isSentAsTheFirstToolResultOfTheNextRequest() = runTest {
        val h = harness { n -> if (n == 0) proposalTurn("toolu_01") else textTurn("Applied, thanks.") }
        send(h, "Switch me to a strength focus")
        h.vm.onApply("toolu_01")
        advanceUntilIdle()
        val applied = assertNotNull(h.vm.state.value.proposals["toolu_01"])
        assertEquals(ProposalStatus.APPLIED, applied.status)
        assertNotNull(applied.overrideId)
        assertEquals(OverrideSource.CHAT, h.repos.plans.overrides().single().source)
        assertTrue(h.repos.workouts.allAdjustments.any { it.proposalId == "toolu_01" })

        send(h, "Thanks")
        assertEquals(2, h.backend.requests.size)
        val first = assertNotNull(firstToolResult(h.backend.requests[1]))
        assertEquals("tool_result", first["type"]?.jsonPrimitive?.content)
        assertEquals("toolu_01", first["tool_use_id"]?.jsonPrimitive?.content)
        assertTrue(first["content"].toString().contains("applied"))
        assertEquals(CoachPhase.Idle, h.vm.state.value.phase)
    }

    @Test fun reject_isSentAsRejected() = runTest {
        val h = harness { n -> if (n == 0) proposalTurn("toolu_02") else textTurn("Understood.") }
        send(h, "Switch me to a strength focus")
        h.vm.onReject("toolu_02", "not now")
        advanceUntilIdle()
        assertEquals(ProposalStatus.REJECTED, h.vm.state.value.proposals.getValue("toolu_02").status)
        assertTrue(h.repos.plans.overrides().isEmpty())

        send(h, "ok")
        val first = assertNotNull(firstToolResult(h.backend.requests[1]))
        assertEquals("toolu_02", first["tool_use_id"]?.jsonPrimitive?.content)
        assertTrue(first["content"].toString().contains("rejected"))
    }

    @Test fun readOnlyFollowUpsAreCappedAtThree() = runTest {
        val h = harness { n -> lookupTurn("toolu_ro_$n") }
        send(h, "Which squat variations do I have?")
        assertEquals(4, h.backend.requests.size, "first response + at most 3 automatic follow-ups (section 6.7)")
        assertEquals(CoachPhase.Idle, h.vm.state.value.phase)
        // the synthetic tool_result-only user rows are not rendered
        assertTrue(h.vm.state.value.messages.none { it.role == com.gymcoach.core.model.ChatRole.USER && it.displayText.isEmpty() })
    }

    @Test fun offlineDraftIsQueuedOnceAndSentOnReconnect() = runTest {
        val h = harness { textTurn("Welcome back.") }
        h.repos.network.online = false
        send(h, "Hotel gym Tue-Thu")
        assertEquals(CoachPhase.Offline(draftQueued = true), h.vm.state.value.phase)
        assertEquals("Hotel gym Tue-Thu", h.vm.extras.value.queuedDraft)
        assertEquals(0, h.backend.requests.size)

        h.repos.network.online = true
        advanceUntilIdle()
        assertEquals(1, h.backend.requests.size)
        assertEquals(null, h.vm.extras.value.queuedDraft)
        assertEquals(CoachPhase.Idle, h.vm.state.value.phase)
        assertTrue(h.vm.state.value.messages.any { it.displayText == "Hotel gym Tue-Thu" })
    }

    @Test fun cancellingTheQueuedDraftKeepsTheText() = runTest {
        val h = harness { textTurn("never sent") }
        h.repos.network.online = false
        send(h, "Explain today's plan")
        h.vm.onCancelQueued()
        assertEquals("Explain today's plan", h.vm.state.value.draft)
        h.repos.network.online = true
        advanceUntilIdle()
        assertEquals(0, h.backend.requests.size)
    }

    @Test fun withoutABackendTheComposerShowsNoBackend() = runTest {
        val h = harness(BackendMode.NONE) { textTurn("unused") }
        send(h, "hello")
        assertEquals(CoachPhase.NoBackend, h.vm.state.value.phase)
        assertEquals(0, h.backend.requests.size)
    }

    @Test fun redFlagPhraseStopsTheSendUntilTheUserConfirms() = runTest {
        val h = harness { textTurn("Please see a professional.") }
        send(h, "I have chest pain when I press")
        assertNotNull(h.vm.state.value.redFlagHit)
        assertEquals(0, h.backend.requests.size)
        h.vm.onRedFlagSendAnyway()
        advanceUntilIdle()
        assertEquals(1, h.backend.requests.size)
        assertEquals(null, h.vm.state.value.redFlagHit)
    }
}
