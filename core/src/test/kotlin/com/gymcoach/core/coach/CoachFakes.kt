package com.gymcoach.core.coach

import com.gymcoach.core.coach.wire.SseParser
import com.gymcoach.core.equipment.Presets
import com.gymcoach.core.json.CoreJson
import com.gymcoach.core.knowledge.ClasspathKnowledgeSource
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.knowledge.KnowledgeLoader
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.CardioLog
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.ConversationStatus
import com.gymcoach.core.model.EnteredLength
import com.gymcoach.core.model.Explanation
import com.gymcoach.core.model.Goal
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.LengthUnit
import com.gymcoach.core.model.MesoWeek
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.PlanInputs
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.PlannedSession
import com.gymcoach.core.model.PlannedSlot
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.Sex
import com.gymcoach.core.model.SlotRole
import com.gymcoach.core.model.SpendRecord
import com.gymcoach.core.model.SplitKind
import com.gymcoach.core.model.TodayContext
import com.gymcoach.core.model.TrainingAge
import com.gymcoach.core.model.WeekMode
import com.gymcoach.core.model.WeeklyCheckin
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
import com.gymcoach.core.repo.StreamFrame
import com.gymcoach.core.repo.TestResult
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Shared fixtures and in-memory fakes of the WP-05 tests. */
object CoachFixtures {
    val kb: KnowledgeBase by lazy { KnowledgeLoader(CoreJson.assets).load(ClasspathKnowledgeSource()) }
    val NOW: Instant = Instant.parse("2026-09-09T07:00:00Z")      // Wednesday
    val TODAY: LocalDate = LocalDate.of(2026, 9, 9)
    val MONDAY: LocalDate = LocalDate.of(2026, 9, 7)
    const val HOME_ID: String = "gym-home"
    const val HOTEL_ID: String = "gym-hotel"

    val home: GymSet by lazy { Presets.gymFromPreset(kb, Presets.HOME_GYM, "Home gym", HOME_ID, NOW).copy(isDefault = true) }
    val hotel: GymSet by lazy { Presets.gymFromPreset(kb, Presets.HOTEL_GYM, "Hotel Ulm", HOTEL_ID, NOW) }

    fun resource(path: String): String {
        val stream = checkNotNull(CoachFixtures::class.java.classLoader.getResourceAsStream(path)) { "missing test resource $path" }
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    fun sse(name: String): String = resource("sse/$name")

    /** The fixture parsed into Messages-API events (SSE data payloads as JSON). */
    fun frames(sseText: String, requestId: String? = "req_test"): List<StreamFrame> {
        val p = SseParser()
        val events = p.feed(sseText) + p.flush()
        return events.map { StreamFrame(CoreJson.wire.parseToJsonElement(it.data).jsonObject, requestId) }
    }

    fun profile(): Profile = Profile(
        sex = Sex.MALE, birthYear = 1988, height = EnteredLength(180.0, LengthUnit.CM, 180.0), trainingMonthsConsistent = 18,
        goal = Goal.HYPERTROPHY, daysPerWeek = 4, sessionMinutes = 60, updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
    )

    fun slot(id: String, role: SlotRole, exerciseId: String, sets: Int, heavy: Boolean = false): PlannedSlot {
        val e = kb.requireExercise(exerciseId)
        return PlannedSlot(id, role, e.pattern, exerciseId, 0, sets, heavy, null, Explanation(listOf("SEL"), "fixture"))
    }

    /** A hand-built one-week-of-four mesocycle in the home gym: UPPER_A Mon, LOWER_A Tue, UPPER_B Thu, LOWER_B Fri. */
    fun mesocycle(): Mesocycle {
        fun session(day: Int, template: String, slots: List<PlannedSlot>) =
            PlannedSession("meso-t:w1:d$day:$template", template, template, day, slots, 55.0, emptyMap(), emptyMap())
        val sessions = listOf(
            session(1, "UPPER_A", listOf(slot("s1", SlotRole.PRIMARY, "barbell_bench_press", 4, true), slot("s2", SlotRole.PRIMARY, "barbell_row", 3))),
            session(2, "LOWER_A", listOf(slot("s1", SlotRole.PRIMARY, "back_squat", 4, true), slot("s2", SlotRole.SECONDARY, "romanian_deadlift", 3))),
            session(4, "UPPER_B", listOf(slot("s1", SlotRole.PRIMARY, "pull_up", 4), slot("s2", SlotRole.PRIMARY, "incline_dumbbell_press", 4))),
            session(5, "LOWER_B", listOf(slot("s1", SlotRole.PRIMARY, "conventional_deadlift", 3, true), slot("s2", SlotRole.SECONDARY, "bulgarian_split_squat", 3))),
        )
        val weeks = (1..4).map { i ->
            MesoWeek(i, MONDAY.plusDays(7L * (i - 1)), if (i == 4) WeekMode.DELOAD else WeekMode.NORMAL, if (i == 1) 1 else 0, emptyMap(), sessions)
        }
        return Mesocycle("meso-t", MONDAY, Goal.HYPERTROPHY, TrainingAge.INTERMEDIATE, SplitKind.UPPER_LOWER_4, HOME_ID, weeks, 4, "hash")
    }

    fun planInputs(profile: Profile = profile(), states: List<ProgressionState> = emptyList(), sessions: List<SessionRecord> = emptyList(), sets: List<SetLog> = emptyList(),
                   readiness: List<ReadinessCheckin> = emptyList(), checkins: List<WeeklyCheckin> = emptyList(), overrides: List<PlanOverride> = emptyList()): PlanInputs =
        PlanInputs(
            profile = profile, gyms = listOf(home, hotel), defaultGymId = HOME_ID, overrides = overrides, progressionStates = states,
            recentSessions = sessions, recentSets = sets, bodyMetrics = emptyList(), weeklyCheckins = checkins, readinessHistory = readiness,
            mesocycleAnchor = MONDAY,
        )

    fun inputs(
        gymId: String = HOME_ID,
        planInputs: PlanInputs = planInputs(),
        mesocycle: Mesocycle? = mesocycle(),
        constants: PlannerConstants = PlannerConstants.DEFAULT,
        skipRedFlagScan: Boolean = false,
    ): CoachTurnInputs = CoachTurnInputs(
        today = TodayContext(TODAY, gymId), todaySession = null, planInputs = planInputs, constants = constants, mesocycle = mesocycle,
        trainingAge = TrainingAge.INTERMEDIATE, ledger = null, nutrition = null, energy = null, trendKgPerWeek = null, weightMa7Kg = 84.4,
        skipRedFlagScan = skipRedFlagScan,
    )

    fun state(exerciseId: String, kg: Double, gymScope: String? = null): ProgressionState = ProgressionState(
        key = ProgressionKey(exerciseId, gymScope), status = com.gymcoach.core.model.ProgressionStatus.PROGRESSING, workingLoadKg = kg,
        workingReps = com.gymcoach.core.model.Band(6, 10), repHiBase = 10, targetRir = 2, updatedAt = NOW,
    )
}

class MutableClock(var instant: Instant, private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")) : Clock {
    override fun now(): Instant = instant
    override fun today(): LocalDate = instant.atZone(zoneId).toLocalDate()
    override fun zone(): ZoneId = zoneId
    fun advanceSeconds(s: Long) { instant = instant.plusSeconds(s) }
}

class FakeNetwork(var online: Boolean = true) : NetworkMonitor {
    override fun isOnline(): Boolean = online
    override fun observe(): Flow<Boolean> = flowOf(online)
}

class FakeSettings(var value: AppSettings = AppSettings(backendMode = BackendMode.DIRECT_API, coachUserId = "user-123")) : SettingsRepository {
    override fun observe(): Flow<AppSettings> = flowOf(value)
    override suspend fun get(): AppSettings = value
    override suspend fun update(transform: (AppSettings) -> AppSettings) { value = transform(value) }
}

class FakeGyms(vararg initial: GymSet) : GymRepository {
    val gyms = LinkedHashMap<String, GymSet>().apply { initial.forEach { put(it.id, it) } }
    override fun observeAll(includeArchived: Boolean): Flow<List<GymSet>> = flowOf(gyms.values.toList())
    override suspend fun getAll(includeArchived: Boolean): List<GymSet> = gyms.values.filter { includeArchived || !it.archived }
    override suspend fun get(id: String): GymSet? = gyms[id]
    override suspend fun upsert(gym: GymSet) { gyms[gym.id] = gym }
    override suspend fun setDefault(id: String) { gyms.replaceAll { k, g -> g.copy(isDefault = k == id) } }
    override suspend fun touchLastUsed(id: String, at: Instant) {}
    override suspend fun setArchived(id: String, archived: Boolean) { gyms[id]?.let { gyms[id] = it.copy(archived = archived) } }
}

class FakeCoach : CoachRepository {
    val conversations = LinkedHashMap<String, ChatConversation>()
    val messages = ArrayList<ChatMessage>()
    val proposals = LinkedHashMap<String, Proposal>()
    val spend = ArrayList<SpendRecord>()
    val decisions = ArrayList<Pair<String, ProposalStatus>>()

    override suspend fun openConversation(): ChatConversation? = conversations.values.lastOrNull {
        it.isDefault && (it.status == ConversationStatus.OPEN || it.status == ConversationStatus.AWAITING_TOOL_RESULTS)
    }
    override suspend fun conversation(id: String): ChatConversation? = conversations[id]
    override suspend fun listConversations(): List<ChatConversation> = conversations.values.toList()
    override suspend fun createConversation(conversation: ChatConversation) { conversations[conversation.id] = conversation }
    override suspend fun updateConversation(conversation: ChatConversation) { conversations[conversation.id] = conversation }
    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = flowOf(messages.filter { it.conversationId == conversationId })
    override suspend fun messages(conversationId: String): List<ChatMessage> = messages.filter { it.conversationId == conversationId }.sortedBy { it.seq }
    override suspend fun addMessage(message: ChatMessage) { messages.add(message) }
    override suspend fun updateMessage(message: ChatMessage) { messages.replaceAll { if (it.id == message.id) message else it } }
    override fun observePendingProposals(): Flow<List<Proposal>> = flowOf(proposals.values.filter { it.status == ProposalStatus.PENDING })
    override suspend fun proposals(conversationId: String): List<Proposal> = proposals.values.filter { it.conversationId == conversationId }
    override suspend fun proposal(id: String): Proposal? = proposals[id]
    override suspend fun addProposal(proposal: Proposal) { proposals[proposal.id] = proposal }
    override suspend fun updateProposal(proposal: Proposal) { proposals[proposal.id] = proposal }
    override suspend fun decideProposal(id: String, status: ProposalStatus, decidedAt: Instant, resultSummary: String, overrideId: String?) {
        decisions.add(Pair(id, status))
        proposals[id]?.let { proposals[id] = it.copy(status = status, decidedAt = decidedAt, resultSummary = resultSummary, overrideId = overrideId ?: it.overrideId) }
    }
    override suspend fun addSpend(record: SpendRecord) { spend.add(record) }
    override suspend fun spendSince(from: Instant): List<SpendRecord> = spend.filter { !it.at.isBefore(from) }
}

class FakePlans : PlanRepository {
    var current: Mesocycle? = null
    val overrides = ArrayList<PlanOverride>()
    override fun observeCurrent(): Flow<Mesocycle?> = MutableStateFlow(current)
    override suspend fun getCurrent(): Mesocycle? = current
    override suspend fun saveSnapshot(mesocycle: Mesocycle, computedAt: Instant) { current = mesocycle }
    override suspend fun snapshotByInputsHash(hash: String): Mesocycle? = null
    override fun observeOverrides(): Flow<List<PlanOverride>> = flowOf(overrides.toList())
    override suspend fun overrides(): List<PlanOverride> = overrides.toList()
    override suspend fun addOverride(override: PlanOverride) { overrides.add(override) }
    override suspend fun revokeOverride(id: String, at: Instant) { overrides.replaceAll { if (it.id == id) it.copy(revokedAt = at) else it } }
    override suspend fun nutritionState(): NutritionState? = null
    override suspend fun saveNutritionState(state: NutritionState) {}
}

/** Records every write so tests can assert that the coach never touches logged sets. */
class FakeWorkouts(val sessions: List<SessionRecord> = emptyList(), val sets: List<SetLog> = emptyList()) : WorkoutRepository {
    val writes = ArrayList<String>()
    override suspend fun startSession(record: SessionRecord) { writes.add("startSession") }
    override suspend fun updateSession(record: SessionRecord) { writes.add("updateSession") }
    override suspend fun getSession(id: String): SessionRecord? = sessions.firstOrNull { it.id == id }
    override fun observeOpenSession(): Flow<SessionRecord?> = flowOf(null)
    override suspend fun sessionsBetween(from: LocalDate, to: LocalDate): List<SessionRecord> = sessions.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    override fun observeSets(sessionId: String): Flow<List<SetLog>> = flowOf(sets.filter { it.sessionId == sessionId })
    override suspend fun setsForSession(sessionId: String, includeSuperseded: Boolean): List<SetLog> = sets.filter { it.sessionId == sessionId }
    override suspend fun setsForSessions(sessionIds: List<String>): List<SetLog> = sets.filter { it.sessionId in sessionIds }
    override suspend fun setsForExercise(exerciseId: String, limitSessions: Int): List<SetLog> = sets.filter { it.exerciseId == exerciseId }
    override suspend fun lastLoggedAtByExercise(): Map<String, Instant> = emptyMap()
    override suspend fun addSet(set: SetLog) { writes.add("addSet") }
    override suspend fun supersedeSet(oldId: String, replacement: SetLog) { writes.add("supersedeSet") }
    override suspend fun recordAdjustment(adjustment: Adjustment) { writes.add("recordAdjustment") }
    override suspend fun undoAdjustment(id: String, at: Instant) { writes.add("undoAdjustment") }
    override fun observeAdjustments(limit: Int): Flow<List<Adjustment>> = flowOf(emptyList())
    override suspend fun adjustmentsForSession(sessionId: String): List<Adjustment> = emptyList()
}

class FakeProgression(val states: List<ProgressionState> = emptyList()) : ProgressionRepository {
    override suspend fun get(key: ProgressionKey): ProgressionState? = states.firstOrNull { it.key == key }
    override suspend fun getAll(): List<ProgressionState> = states
    override suspend fun getForExercise(exerciseId: String): List<ProgressionState> = states.filter { it.key.exerciseId == exerciseId }
    override suspend fun save(state: ProgressionState) {}
    override suspend fun saveAll(states: List<ProgressionState>) {}
}

class FakeMetrics(val questions: List<CheckinQuestion> = emptyList()) : MetricsRepository {
    override fun observeBodyMetrics(days: Int): Flow<List<BodyMetric>> = flowOf(emptyList())
    override suspend fun bodyMetricsBetween(from: LocalDate, to: LocalDate): List<BodyMetric> = emptyList()
    override suspend fun addBodyMetric(metric: BodyMetric) {}
    override suspend fun addCardioLog(log: CardioLog) {}
    override suspend fun cardioBetween(from: LocalDate, to: LocalDate): List<CardioLog> = emptyList()
    override suspend fun addNutritionLog(log: NutritionLog) {}
    override suspend fun nutritionBetween(from: LocalDate, to: LocalDate): List<NutritionLog> = emptyList()
    override suspend fun addReadiness(checkin: ReadinessCheckin) {}
    override suspend fun readinessFor(date: LocalDate): ReadinessCheckin? = null
    override suspend fun readinessBetween(from: LocalDate, to: LocalDate): List<ReadinessCheckin> = emptyList()
    override fun observeReadiness(days: Int): Flow<List<ReadinessCheckin>> = flowOf(emptyList())
    override suspend fun saveWeeklyCheckin(checkin: WeeklyCheckin) {}
    override suspend fun weeklyCheckinFor(weekStart: LocalDate): WeeklyCheckin? = null
    override suspend fun latestWeeklyCheckins(count: Int): List<WeeklyCheckin> = emptyList()
    override fun observeQuestions(): Flow<List<CheckinQuestion>> = flowOf(questions)
    override suspend fun activeQuestions(on: LocalDate): List<CheckinQuestion> = questions
    override suspend fun saveQuestion(question: CheckinQuestion) {}
}

class FakeProfiles(var profile: Profile? = CoachFixtures.profile()) : ProfileRepository {
    override fun observe(): Flow<Profile?> = flowOf(profile)
    override suspend fun get(): Profile? = profile
    override suspend fun save(profile: Profile) { this.profile = profile }
}

/**
 * Replays one scripted response per stream() call (SSE text parsed by the real SseParser) and records every body.
 * A script entry can also be a BackendError, thrown as BackendException before any event.
 */
class FakeBackend(override val mode: BackendMode = BackendMode.DIRECT_API) : ClaudeBackend {
    val bodies = ArrayList<JsonObject>()
    val completeBodies = ArrayList<JsonObject>()
    val script = ArrayDeque<Any>()          // String (SSE text) | BackendError
    var completeResponse: JsonObject? = null

    fun enqueue(sseText: String): FakeBackend { script.addLast(sseText); return this }
    fun enqueueError(error: BackendError): FakeBackend { script.addLast(error); return this }

    override fun stream(body: JsonObject): Flow<StreamFrame> = flow {
        bodies.add(body)
        val next = script.removeFirstOrNull() ?: throw BackendException(BackendError.Unknown("no scripted response"))
        if (next is BackendError) throw BackendException(next, "req_err")
        for (f in CoachFixtures.frames(next as String, "req_${bodies.size}")) emit(f)
    }

    override suspend fun complete(body: JsonObject): StreamFrame {
        completeBodies.add(body)
        return StreamFrame(checkNotNull(completeResponse) { "no complete() response scripted" }, "req_complete")
    }

    override suspend fun test(): TestResult = TestResult(null, "req_test")
}

/** Builds Messages-API SSE streams for the service tests (the same shapes as the R1 §3.6 fixtures). */
object Sse {
    private fun ev(name: String, json: String): String = "event: $name\ndata: $json\n\n"
    private fun q(s: String): String = CoreJson.wire.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(s))

    fun response(id: String, vararg blocks: String, stop: String = "end_turn", input: Int = 2000, cacheRead: Int = 3500, output: Int = 400, stopDetails: String? = null): String {
        val sb = StringBuilder()
        sb.append(ev("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":${q(id)},\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-opus-5\",\"stop_reason\":null,\"usage\":{\"input_tokens\":$input,\"output_tokens\":1,\"cache_read_input_tokens\":$cacheRead,\"cache_creation_input_tokens\":0}}}"))
        blocks.forEachIndexed { i, b -> sb.append(b.replace("#I#", i.toString())) }
        val details = stopDetails?.let { ",\"stop_details\":$it" } ?: ""
        sb.append(ev("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"$stop\",\"stop_sequence\":null$details},\"usage\":{\"output_tokens\":$output}}"))
        sb.append(ev("message_stop", "{\"type\":\"message_stop\"}"))
        return sb.toString()
    }

    fun thinking(signature: String): String =
        ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":#I#,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}") +
            ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":#I#,\"delta\":{\"type\":\"signature_delta\",\"signature\":${q(signature)}}}") +
            ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":#I#}")

    fun text(t: String): String =
        ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":#I#,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}") +
            ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":#I#,\"delta\":{\"type\":\"text_delta\",\"text\":${q(t)}}}") +
            ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":#I#}")

    fun toolUse(id: String, name: String, inputJson: String): String {
        val half = inputJson.length / 2
        return ev("content_block_start", "{\"type\":\"content_block_start\",\"index\":#I#,\"content_block\":{\"type\":\"tool_use\",\"id\":${q(id)},\"name\":${q(name)},\"input\":{}}}") +
            ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":#I#,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\"}}") +
            ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":#I#,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":${q(inputJson.substring(0, half))}}}") +
            ev("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":#I#,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":${q(inputJson.substring(half))}}}") +
            ev("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":#I#}")
    }
}
