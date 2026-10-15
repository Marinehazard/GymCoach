package com.gymcoach.core.coach

import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatRole
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.Effort
import com.gymcoach.core.model.EnergyModel
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Muscle
import com.gymcoach.core.model.NutritionTargets
import com.gymcoach.core.model.PlanDiff
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.planner.PlannerConstants
import com.gymcoach.core.repo.BackendError
import com.gymcoach.core.volume.VolumeView
import com.gymcoach.core.volume.WeeklyLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CoachTurnServiceTest {
    private val kb = CoachFixtures.kb

    private inner class Env(settings: AppSettings = AppSettings(backendMode = BackendMode.DIRECT_API, coachUserId = "user-123")) {
        val clock = MutableClock(CoachFixtures.NOW)
        val coach = FakeCoach()
        val gyms = FakeGyms(CoachFixtures.home, CoachFixtures.hotel)
        val settings = FakeSettings(settings)
        val network = FakeNetwork()
        val backend = FakeBackend()
        val workouts = FakeWorkouts()
        val plans = FakePlans()
        val progression = FakeProgression()
        val executor = ToolExecutor(kb, workouts, progression, gyms, plans)
        fun service(): CoachTurnService = CoachTurnService(
            kb, FakeProfiles(), gyms, plans, workouts, progression, FakeMetrics(), coach, settings, { backend }, executor, SpendMeter(coach, clock), network, clock,
        )
        val service = service()
        val applier = ProposalApplier(plans, coach, clock)

        fun send(text: String, inputs: CoachTurnInputs = CoachFixtures.inputs(), svc: CoachTurnService = service): List<TurnEvent> {
            val events = runBlocking { svc.send(text, inputs).toList() }
            clock.advanceSeconds(60)
            return events
        }
        fun conv(): ChatConversation = coach.conversations.values.last()
        fun rows() = coach.messages.filter { it.conversationId == conv().id }.sortedBy { it.seq }
    }

    private val swapInput = "{\"contract_version\":1,\"from_exercise_id\":\"barbell_bench_press\",\"to_exercise_id\":\"dumbbell_bench_press\",\"scope\":\"rest_of_mesocycle\",\"rationale\":\"TRV-04\"}"
    private val lookupInput = "{\"query\":\"row\",\"available_only\":true}"

    private fun messages(body: JsonObject): JsonArray = body["messages"]!!.jsonArray
    private fun lastUser(body: JsonObject): JsonArray = messages(body).last().jsonObject["content"]!!.jsonArray
    private fun types(content: JsonArray): List<String> = content.map { it.jsonObject["type"]!!.jsonPrimitive.content }
    private fun text(el: JsonElement): String = el.jsonObject["text"]!!.jsonPrimitive.content
    private fun enc(el: JsonElement): String = CoreJson.wire.encodeToString(JsonElement.serializer(), el)

    @Test
    fun endTurnPersistsOnePairAndEmitsDoneOnce() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.thinking("SigA"), Sse.text("Hi there.")))
        val events = env.send("Hello coach")
        assertEquals(TurnEvent.TextDelta("Hi there."), events[0])
        val usage = events.filterIsInstance<TurnEvent.Usage>().single()
        assertEquals(0.02175, usage.costUsd, 1e-12)
        val done = events.last() as TurnEvent.Done
        assertEquals("end_turn", done.stopReason)
        assertEquals(1, events.count { it is TurnEvent.Done })
        val rows = env.rows()
        assertEquals(listOf(1, 2), rows.map { it.seq })
        assertEquals(done.assistantMessageId, rows[1].id)
        assertEquals("Hello coach", rows[0].displayText)
        val sent = lastUser(env.backend.bodies.single())
        assertEquals(listOf("text", "text"), types(sent))
        assertTrue(text(sent[0]).startsWith("<context schema=\"1\">{\"today\":\"2026-09-09\""))
        assertEquals("Hello coach", text(sent[1]))
        assertEquals(enc(sent), rows[0].contentJson)
        assertEquals("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"SigA\"},{\"type\":\"text\",\"text\":\"Hi there.\"}]", rows[1].contentJson)
        assertEquals("req_1", rows[1].requestId)
        val c = env.conv()
        assertEquals(ConversationStatus.OPEN, c.status)
        assertEquals(1, c.assistantTurns)
        assertEquals(5500, c.lastInputTokens)
        assertEquals(SystemPrompt.hash(kb), c.systemPromptHash)
        assertEquals(1, env.coach.spend.size)
    }

    @Test
    fun proposalPendingThenAppliedThenNextRequestCarriesToolResultFirst() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.thinking("S1"), Sse.text("Swap it."), Sse.toolUse("toolu_01A", "propose_swap_exercise", swapInput), stop = "tool_use"))
        val events = env.send("No barbell today")
        val complete = events.filterIsInstance<TurnEvent.ToolUseComplete>().single()
        assertEquals(ProposalStatus.PENDING, complete.proposal!!.status)
        assertEquals(TurnEvent.ToolUseStarted("toolu_01A", "propose_swap_exercise"), events.filterIsInstance<TurnEvent.ToolUseStarted>().single())
        val p = env.coach.proposals["toolu_01A"]!!
        assertEquals(ProposalStatus.PENDING, p.status)
        assertEquals(env.rows()[1].id, p.assistantMessageId)
        assertEquals(CoachFixtures.TODAY.plusDays(7), p.expiresOn)
        assertFalse(p.resultSent)
        assertEquals(ConversationStatus.AWAITING_TOOL_RESULTS, env.conv().status)
        assertEquals(1, env.backend.bodies.size, "a proposal never triggers an automatic follow-up")

        // the user applies the card (§6.8): override appended, recompute (ViewModel), finalize with the diff
        val override = runBlocking { env.applier.apply(p, CoachFixtures.TODAY) }
        val diff = PlanDiff(listOf("Mon UPPER_A"), mapOf(Muscle.CHEST to 12.0), mapOf(Muscle.CHEST to 12.0))
        runBlocking { env.applier.finalize(p.id, diff, override.id) }
        env.clock.advanceSeconds(5)

        env.backend.enqueue(Sse.response("msg_2", Sse.text("Done.")))
        env.send("Thanks")
        val sent = lastUser(env.backend.bodies[1])
        assertEquals(listOf("tool_result", "text", "text"), types(sent))
        val tr = sent[0].jsonObject
        assertEquals("toolu_01A", tr["tool_use_id"]!!.jsonPrimitive.content)
        assertNull(tr["is_error"])
        assertTrue(tr["content"]!!.jsonPrimitive.content.startsWith("{\"status\":\"applied\",\"diff\":{\"sessions_changed\":[\"Mon UPPER_A\"]"), tr["content"].toString())
        assertTrue(text(sent[1]).startsWith("<delta schema=\"1\">"))
        assertTrue(text(sent[1]).contains("\"applied\":[\"toolu_01A\"]"), text(sent[1]))
        assertTrue(env.coach.proposals["toolu_01A"]!!.resultSent)
        // the assistant turn is echoed unchanged
        assertEquals(env.rows()[1].contentJson, enc(messages(env.backend.bodies[1])[1].jsonObject["content"]!!))
    }

    @Test
    fun undecidedProposalIsAnsweredPendingAndInvalidOnesAsIsError() {
        val env = Env()
        val bad = "{\"contract_version\":1,\"muscle\":\"chest\",\"delta_sets_per_week\":1,\"scope\":\"permanent\",\"rationale\":\"VOL-08\"}"
        env.backend.enqueue(Sse.response("msg_1", Sse.toolUse("toolu_P", "propose_swap_exercise", swapInput), Sse.toolUse("toolu_X", "propose_change_volume", bad), stop = "tool_use"))
        val events = env.send("Change things")
        val completes = events.filterIsInstance<TurnEvent.ToolUseComplete>()
        assertEquals(listOf(ProposalStatus.PENDING, ProposalStatus.INVALID), completes.map { it.proposal!!.status })
        assertNotNull(completes[1].error)
        assertNull(env.coach.proposals["toolu_X"]!!.mutation)
        env.backend.enqueue(Sse.response("msg_2", Sse.text("Ok.")))
        env.send("Later")
        val sent = lastUser(env.backend.bodies[1])
        assertEquals(listOf("tool_result", "tool_result", "text", "text"), types(sent))
        assertEquals("{\"status\":\"pending\",\"note\":\"the user has not decided; do not re-propose unless asked\"}", sent[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(sent[1].jsonObject["is_error"]!!.jsonPrimitive.boolean)
        assertTrue(sent[1].jsonObject["content"]!!.jsonPrimitive.content.startsWith("{\"status\":\"invalid\",\"errors\":["))
    }

    @Test
    fun mixedReadOnlyAndProposalTurnWaitsAndEchoesBothResultsInContentOrder() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.toolUse("toolu_L", "lookup_exercises", lookupInput), Sse.toolUse("toolu_A", "propose_swap_exercise", swapInput), stop = "tool_use"))
        val events = env.send("Find a row and swap bench")
        assertEquals(1, env.backend.bodies.size, "no partial follow-up for a mixed turn")
        val readOnly = events.filterIsInstance<TurnEvent.ToolUseComplete>().first()
        assertNotNull(readOnly.readOnlyResult)
        val stored = CoreJson.wire.parseToJsonElement(env.rows()[1].toolResultsJson!!).jsonObject
        assertEquals(setOf("toolu_L"), stored.keys)
        assertEquals(ConversationStatus.AWAITING_TOOL_RESULTS, env.conv().status)
        assertEquals("tool_use", (events.last() as TurnEvent.Done).stopReason)

        env.backend.enqueue(Sse.response("msg_2", Sse.text("Ok.")))
        env.send("go on")
        val sent = lastUser(env.backend.bodies[1])
        assertEquals(listOf("toolu_L", "toolu_A"), sent.take(2).map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content })
        assertEquals(stored["toolu_L"]!!.jsonPrimitive.content, sent[0].jsonObject["content"]!!.jsonPrimitive.content, "the read-only result comes from toolResultsJson")
    }

    @Test
    fun fourConsecutiveReadOnlyTurnsEndWithTheBudgetError() {
        val env = Env()
        repeat(4) { i -> env.backend.enqueue(Sse.response("msg_$i", Sse.toolUse("toolu_$i", "lookup_exercises", lookupInput), stop = "tool_use")) }
        val events = env.send("Search a lot")
        assertEquals(4, env.backend.bodies.size, "first response + 3 automatic follow-ups")
        assertEquals(4, events.count { it is TurnEvent.Usage })
        assertEquals(1, events.count { it is TurnEvent.Done })
        val rows = env.rows()
        assertEquals((1..8).toList(), rows.map { it.seq })
        val last = CoreJson.wire.parseToJsonElement(rows.last().toolResultsJson!!).jsonObject
        assertEquals("{\"status\":\"error\",\"errors\":[\"tool budget for this turn exhausted; answer with what you have\"]}", last["toolu_3"]!!.jsonPrimitive.content)
        assertEquals(ConversationStatus.AWAITING_TOOL_RESULTS, env.conv().status)

        env.backend.enqueue(Sse.response("msg_x", Sse.text("Here is what I have.")))
        env.send("ok")
        val first = lastUser(env.backend.bodies[4])[0].jsonObject
        assertEquals("toolu_3", first["tool_use_id"]!!.jsonPrimitive.content)
        assertTrue(first["is_error"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun chainPersistenceSeqOneToSixWithByteIdenticalPrefixes() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.thinking("T1"), Sse.toolUse("toolu_1", "lookup_exercises", lookupInput), stop = "tool_use"))
        env.backend.enqueue(Sse.response("msg_2", Sse.thinking("T2"), Sse.toolUse("toolu_2", "get_rule", "{\"rule_id\":\"VOL-04\"}"), stop = "tool_use"))
        env.backend.enqueue(Sse.response("msg_3", Sse.thinking("T3"), Sse.text("Use 12-18 sets (VOL-04).")))
        val events = env.send("How many sets?")
        assertEquals("end_turn", (events.last() as TurnEvent.Done).stopReason)
        val rows = env.rows()
        assertEquals((1..6).toList(), rows.map { it.seq })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT), rows.map { it.role })
        for (synthetic in listOf(rows[2], rows[4])) {
            assertEquals("", synthetic.displayText)
            val c = RequestBuilderAccess.parse(synthetic.contentJson)
            assertEquals(listOf("tool_result"), types(c), "no delta, no text in a synthetic follow-up")
        }
        val b = env.backend.bodies
        assertEquals(3, b.size)
        for (i in 1 until b.size) {
            assertEquals(enc(b[0]["system"]!!), enc(b[i]["system"]!!))
            assertEquals(enc(b[0]["tools"]!!), enc(b[i]["tools"]!!))
            val prev = messages(b[i - 1])
            val cur = messages(b[i])
            assertEquals(enc(prev), enc(JsonArray(cur.take(prev.size))), "request $i starts with request ${i - 1}")
            assertEquals(prev.size + 2, cur.size)
        }
        // process death: a fresh service on the same repositories rebuilds the same prefix from the stored rows
        env.backend.enqueue(Sse.response("msg_4", Sse.text("Sure.")))
        env.send("And for arms?", svc = env.service())
        val after = messages(env.backend.bodies[3])
        val before = messages(b[2])
        assertEquals(enc(before), enc(JsonArray(after.take(before.size))))
        assertEquals(enc(JsonArray(after.take(before.size + 1).drop(before.size))), "[{\"role\":\"assistant\",\"content\":" + rows[5].contentJson + "}]")
    }

    @Test
    fun refusalDiscardsThatPairButKeepsEarlierPairsOfTheChain() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.toolUse("toolu_1", "lookup_exercises", lookupInput), stop = "tool_use"))
        env.backend.enqueue(Sse.response("msg_2", Sse.text("I can"), stop = "refusal", stopDetails = "{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":null}"))
        val events = env.send("question")
        val done = events.last() as TurnEvent.Done
        assertEquals("refusal", done.stopReason)
        assertTrue(done.stopDetailsJson!!.contains("\"category\":\"cyber\""))
        assertEquals(listOf(1, 2), env.rows().map { it.seq }, "only the first pair survives")
        assertEquals(done.assistantMessageId, env.rows()[1].id)

        val env2 = Env()
        env2.backend.enqueue(CoachFixtures.sse("refusal.sse"))
        val e2 = env2.send("question")
        assertEquals("refusal", (e2.last() as TurnEvent.Done).stopReason)
        assertEquals(emptyList(), env2.coach.messages)
    }

    @Test
    fun failurePersistsNothingAndKeepsTheDraft() {
        val env = Env()
        env.backend.enqueueError(BackendError.Overloaded(midStream = false))
        val events = env.send("hello")
        assertEquals(listOf<TurnEvent>(TurnEvent.Failed(BackendError.Overloaded(false), draftKept = true)), events)
        assertEquals(emptyList(), env.coach.messages)
        env.backend.enqueue(CoachFixtures.sse("error_overloaded.sse"))
        val mid = env.send("hello")
        assertEquals(TurnEvent.Failed(BackendError.Overloaded(midStream = true), draftKept = true), mid.last())
        assertEquals(emptyList(), env.coach.messages)
        // the retry rebuilds the same first message
        env.backend.enqueue(Sse.response("msg_ok", Sse.text("Hi")))
        env.send("hello")
        assertEquals(listOf(1, 2), env.rows().map { it.seq })
        assertEquals(enc(lastUser(env.backend.bodies[0])).replace(Regex("\"today\":\"[0-9-]+\""), ""), enc(lastUser(env.backend.bodies[2])).replace(Regex("\"today\":\"[0-9-]+\""), ""))
    }

    @Test
    fun offlineFailsFastBeforeAnyBackendCall() {
        val env = Env()
        env.network.online = false
        val events = env.send("hello")
        assertEquals(listOf<TurnEvent>(TurnEvent.Failed(BackendError.Offline, draftKept = true)), events)
        assertEquals(0, env.backend.bodies.size)
        assertEquals(0, env.coach.conversations.size)
    }

    @Test
    fun noGymFailsWithTheLocalPrecondition() {
        val env = Env()
        env.gyms.gyms.clear()
        val events = env.send("hello")
        assertEquals(listOf<TurnEvent>(TurnEvent.Failed(BackendError.InvalidRequest(0, "no gym configured"), draftKept = true)), events)
        assertEquals(0, env.backend.bodies.size)
    }

    @Test
    fun redFlagHitSendsNothingAndSkipRedFlagScanSendsTheTextUnchanged() {
        val env = Env()
        val text = "I had chest pain on the treadmill"
        val events = env.send(text)
        assertEquals(listOf<TurnEvent>(TurnEvent.Done("red_flag", "{\"phrase\":\"chest pain\",\"emergency\":true}", null)), events)
        assertEquals(0, env.backend.bodies.size)
        env.backend.enqueue(Sse.response("msg_1", Sse.text("Stop now and call emergency services.")))
        env.send(text, CoachFixtures.inputs(skipRedFlagScan = true))
        assertEquals(text, text(lastUser(env.backend.bodies.single()).last()))
    }

    @Test
    fun contextInputsBuiltFromCoachTurnInputsCarryLedgerNutritionAndTrainingAge() {
        val env = Env()
        val expl = Explanation(listOf("NUT-04"), "fixture")
        val ledger = WeeklyLedger(CoachFixtures.MONDAY, mapOf(Muscle.CHEST to 14.0), mapOf(Muscle.CHEST to VolumeView(4, 6.0, 1)), mapOf(Muscle.CHEST to 4.0), emptyMap())
        val inputs = CoachFixtures.inputs(gymId = "unknown-gym").copy(
            trainingAge = TrainingAge.ADVANCED, ledger = ledger,
            nutrition = NutritionTargets(2870, 151, 350, 80, 35, 3.0, 261, explanation = expl), energy = EnergyModel(1850, 1.4, 2609, emptyList(), expl),
        )
        env.backend.enqueue(Sse.response("msg_1", Sse.text("Ok.")))
        env.send("status?", inputs)
        val ctx = text(lastUser(env.backend.bodies.single())[0])
        val o = CoreJson.wire.parseToJsonElement(ctx.removePrefix("<context schema=\"1\">").removeSuffix("</context>")).jsonObject
        assertEquals("advanced", o["profile"]!!.jsonObject["training_age"]!!.jsonPrimitive.content)
        assertEquals("{\"chest\":14}", o["ledger"]!!.jsonObject["targets"].toString())
        assertEquals("2870", o["nutrition"]!!.jsonObject["target_kcal"]!!.jsonPrimitive.content)
        assertEquals("2609", o["nutrition"]!!.jsonObject["tdee_est"]!!.jsonPrimitive.content)
        assertEquals("default", o["gym_today"]!!.jsonObject["fallback"]!!.jsonPrimitive.content, "unknown gym id falls back to the default gym")
        assertEquals(CoachFixtures.HOME_ID, o["gym_today"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, o["mesocycle"]!!.jsonObject["week"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun proposalIsValidatedWithTheConstantsOfTheInputs() {
        val volume = "{\"contract_version\":1,\"muscle\":\"chest\",\"delta_sets_per_week\":5,\"scope\":\"permanent\",\"rationale\":\"VOL-01\"}"
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.toolUse("toolu_V1", "propose_change_volume", volume), stop = "tool_use"))
        env.send("more chest")
        assertEquals(ProposalStatus.PENDING, env.coach.proposals["toolu_V1"]!!.status)
        val strict = PlannerConstants.DEFAULT.copy(vol = PlannerConstants.DEFAULT.vol.copy(vol01Minimum = 6))
        env.backend.enqueue(Sse.response("msg_2", Sse.toolUse("toolu_V2", "propose_change_volume", volume), stop = "tool_use"))
        env.send("more chest again", CoachFixtures.inputs(constants = strict))
        assertEquals(ProposalStatus.INVALID, env.coach.proposals["toolu_V2"]!!.status)
    }

    @Test
    fun maxTokensIsPersistedAndTheSupplementCaveatFlagIsSet() {
        val env = Env()
        env.backend.enqueue(CoachFixtures.sse("max_tokens.sse"))
        val events = env.send("creatine?")
        assertEquals("max_tokens", (events.last() as TurnEvent.Done).stopReason)
        val a = env.rows()[1]
        assertEquals("max_tokens", a.stopReason)
        assertTrue(a.caveatAppended)
        assertFalse(a.contentJson.contains("Not medical advice"), "the caveat is never written into the transcript")
    }

    @Test
    fun contextWindowExceededForcesCompactionBeforeTheNextSend() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.text("..."), stop = "model_context_window_exceeded", input = 10))
        env.send("long")
        val compactor = TranscriptCompactor(env.coach, { env.backend }, env.settings, env.clock)
        assertTrue(compactor.needsCompaction(env.conv(), env.clock.now()))
    }

    @Test
    fun compactionTriggerAndSeedingViaComplete() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.text("Noted: dumbbells only on trips."), input = 41_000, cacheRead = 0))
        env.send("I prefer dumbbells when travelling")
        val old = env.conv()
        val compactor = TranscriptCompactor(env.coach, { env.backend }, env.settings, env.clock)
        assertTrue(compactor.needsCompaction(old, env.clock.now()))
        env.backend.completeResponse = CoreJson.wire.parseToJsonElement(
            "{\"id\":\"msg_c\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Prefers dumbbells when travelling.\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":300,\"output_tokens\":20}}",
        ).jsonObject
        val fresh = runBlocking { compactor.compact(old.id, SystemPrompt.compactionPrompt()) }
        assertFalse(env.backend.completeBodies.single().containsKey("tools"))
        assertEquals(ConversationStatus.COMPACTED, env.coach.conversations[old.id]!!.status)
        assertEquals("Prefers dumbbells when travelling.", fresh.seedSummary)
        env.backend.enqueue(Sse.response("msg_2", Sse.text("Ok.")))
        env.send("next week plan?")
        val first = lastUser(env.backend.bodies.last())
        assertEquals(1, messages(env.backend.bodies.last()).size, "nothing of the old conversation is carried over")
        assertEquals("<summary>Prefers dumbbells when travelling.</summary>", text(first[0]))
        assertTrue(text(first[1]).startsWith("<context schema=\"1\">"))
        assertEquals("next week plan?", text(first[2]))
        assertEquals(fresh.id, env.rows()[0].conversationId)
    }

    @Test
    fun retryOnSonnetStartsANewSonnetConversationWithAFreshSnapshot() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.text("Hi.")))
        env.send("hello")
        val refused = env.conv()
        env.backend.enqueue(Sse.response("msg_2", Sse.text("I can"), stop = "refusal"))
        assertEquals("refusal", (env.send("tricky").last() as TurnEvent.Done).stopReason)
        env.backend.enqueue(Sse.response("msg_3", Sse.text("Sure.")))
        val events = runBlocking { env.service.retryOnSonnet("tricky", CoachFixtures.inputs(skipRedFlagScan = true)).toList() }
        assertEquals("end_turn", (events.last() as TurnEvent.Done).stopReason)
        val body = env.backend.bodies.last()
        assertEquals("claude-sonnet-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(1, messages(body).size)
        assertTrue(text(lastUser(body)[0]).startsWith("<context schema=\"1\">"))
        val now = env.conv()
        assertEquals("claude-sonnet-5", now.model)
        assertEquals("claude-opus-5", env.settings.value.modelId, "settings.modelId unchanged")
        val old = env.coach.conversations[refused.id]!!
        assertFalse(old.isDefault)
        assertEquals(ConversationStatus.OPEN, old.status)
        // the next normal send stays on the Sonnet conversation
        env.backend.enqueue(Sse.response("msg_4", Sse.text("Ok.")))
        env.send("and then?")
        assertEquals("claude-sonnet-5", env.backend.bodies.last()["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun homeBridgeDeclaresOnlyProposalToolsAndConveysOutcomesInTheDelta() {
        val env = Env(AppSettings(backendMode = BackendMode.HOME_BRIDGE, effort = Effort.MEDIUM))
        env.backend.enqueue(Sse.response("msg_1", Sse.toolUse("toolu_B", "propose_swap_exercise", swapInput), stop = "tool_use"))
        env.send("swap bench")
        val b0 = env.backend.bodies[0]
        assertEquals(16, b0["tools"]!!.jsonArray.size)
        assertFalse(b0.containsKey("metadata"))
        runBlocking { env.applier.reject(env.coach.proposals["toolu_B"]!!, null) }
        env.clock.advanceSeconds(5)
        env.backend.enqueue(Sse.response("msg_2", Sse.text("Ok, keeping the bench.")))
        env.send("no thanks")
        val sent = lastUser(env.backend.bodies[1])
        assertEquals(listOf("text", "text"), types(sent), "tool_result blocks are dropped for the bridge")
        assertTrue(text(sent[0]).contains("\"results\":{\"toolu_B\":{\"status\":\"rejected\",\"reason\":\"rejected by user\"}}"), text(sent[0]))
        assertTrue(env.coach.proposals["toolu_B"]!!.resultSent)
        assertEquals("{\"effort\":\"medium\"}", env.backend.bodies[1]["output_config"].toString())
    }

    @Test
    fun changedPromptHashStartsANewConversation() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.text("Hi.")))
        env.send("hello")
        val old = env.conv()
        env.coach.conversations[old.id] = old.copy(systemPromptHash = "stale")
        env.backend.enqueue(Sse.response("msg_2", Sse.text("Hi again.")))
        env.send("hello again")
        assertEquals(ConversationStatus.CLOSED, env.coach.conversations[old.id]!!.status)
        assertEquals(1, messages(env.backend.bodies.last()).size)
        assertTrue(text(lastUser(env.backend.bodies.last())[0]).startsWith("<context"))
    }

    @Test
    fun expirePendingProposalsMarksOldCardsExpired() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.toolUse("toolu_E", "propose_swap_exercise", swapInput), stop = "tool_use"))
        env.send("swap")
        runBlocking { env.service.expirePendingProposals(env.clock.now().plusSeconds(8L * 24 * 3600)) }
        assertEquals(ProposalStatus.EXPIRED, env.coach.proposals["toolu_E"]!!.status)
    }

    @Test
    fun deltaReportsANewDateAndAGymChange() {
        val env = Env()
        env.backend.enqueue(Sse.response("msg_1", Sse.text("Hi.")))
        env.send("hello")
        env.clock.advanceSeconds(24L * 3600)
        val tomorrow = CoachFixtures.inputs(gymId = CoachFixtures.HOTEL_ID).let { it.copy(today = it.today.copy(date = CoachFixtures.TODAY.plusDays(1))) }
        env.backend.enqueue(Sse.response("msg_2", Sse.text("Ok.")))
        env.send("hotel today", tomorrow)
        val delta = text(lastUser(env.backend.bodies[1])[0])
        assertTrue(delta.startsWith("<delta schema=\"1\">{\"today\":\"2026-09-10\""), delta)
        assertTrue(delta.contains("\"gym_changed\":true,\"gym_today\":{\"id\":\"gym-hotel\",\"name\":\"Hotel Ulm\"}"), delta)
    }
}

/** Test-side access to the content parser (keeps the assertions readable). */
object RequestBuilderAccess {
    fun parse(json: String): JsonArray = com.gymcoach.core.coach.wire.RequestBuilder.parseContent(json)
}
