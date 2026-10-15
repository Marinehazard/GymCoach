package com.gymcoach.app.fakes

import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AppSettings
import com.gymcoach.core.model.BackendMode
import com.gymcoach.core.model.BodyMetric
import com.gymcoach.core.model.CardioLog
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.CheckinQuestion
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.NutritionLog
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.model.Profile
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.ReadinessCheckin
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SetKind
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.SpendRecord
import com.gymcoach.core.model.WeeklyCheckin
import com.gymcoach.core.repo.ApiKeyStore
import com.gymcoach.core.repo.BackendException
import com.gymcoach.core.repo.ClaudeBackend
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.KnowledgeSource
import com.gymcoach.core.repo.MetricsRepository
import com.gymcoach.core.repo.NetworkMonitor
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.ProgressionRepository
import com.gymcoach.core.repo.SettingsRepository
import com.gymcoach.core.repo.StreamFrame
import com.gymcoach.core.repo.TestResult
import com.gymcoach.core.repo.UnreadableDoc
import com.gymcoach.core.repo.UnreadableDocsRepository
import com.gymcoach.core.repo.WorkoutRepository
import com.gymcoach.core.time.Clock
import com.gymcoach.core.time.FixedClock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

/** In-memory implementations of every core.repo interface (WP-07; WP-08 tests use them read-only). All write order is visible in [FakeRepositories.events]. */
class FakeRepositories(val clock: Clock = FixedClock(Instant.parse("2026-10-15T07:00:00Z"))) {
    /** Ordered log of the writes the ViewModels make ("plans.addOverride", "plans.saveSnapshot", "settings.deloadTriggersProposed" …). */
    val events: MutableList<String> = ArrayList()
    val profile = FakeProfileRepository()
    val gyms = FakeGymRepository()
    val plans = FakePlanRepository(events)
    val workouts = FakeWorkoutRepository(events)
    val progression = FakeProgressionRepository()
    val metrics = FakeMetricsRepository()
    val coach = FakeCoachRepository()
    val settings = FakeSettingsRepository(events)
    val apiKeys = FakeApiKeyStore()
    val network = FakeNetworkMonitor()
    val unreadable = FakeUnreadableDocs()
}

class FakeProfileRepository(initial: Profile? = null) : ProfileRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<Profile?> = state
    override suspend fun get(): Profile? = state.value
    override suspend fun save(profile: Profile) { state.value = profile }
    /** Test hook: simulates a missing profile (planner calls then fail). */
    fun clear() { state.value = null }
}

class FakeGymRepository : GymRepository {
    private val state = MutableStateFlow<List<GymSet>>(emptyList())
    override fun observeAll(includeArchived: Boolean): Flow<List<GymSet>> = state.map { l -> l.filter { includeArchived || !it.archived } }
    override suspend fun getAll(includeArchived: Boolean): List<GymSet> = state.value.filter { includeArchived || !it.archived }
    override suspend fun get(id: String): GymSet? = state.value.firstOrNull { it.id == id }
    override suspend fun upsert(gym: GymSet) { state.value = state.value.filter { it.id != gym.id } + gym }
    override suspend fun setDefault(id: String) { state.value = state.value.map { it.copy(isDefault = it.id == id) } }
    override suspend fun touchLastUsed(id: String, at: Instant) { state.value = state.value.map { if (it.id == id) it.copy(lastUsedAt = at) else it } }
    override suspend fun setArchived(id: String, archived: Boolean) { state.value = state.value.map { if (it.id == id) it.copy(archived = archived) else it } }
}

class FakePlanRepository(private val events: MutableList<String> = ArrayList()) : PlanRepository {
    private val current = MutableStateFlow<Mesocycle?>(null)
    private val snapshots = LinkedHashMap<String, Mesocycle>()
    private val overridesState = MutableStateFlow<List<PlanOverride>>(emptyList())
    private var nutrition: NutritionState? = null
    override fun observeCurrent(): Flow<Mesocycle?> = current
    override suspend fun getCurrent(): Mesocycle? = current.value
    override suspend fun saveSnapshot(mesocycle: Mesocycle, computedAt: Instant) {
        events.add("plans.saveSnapshot")
        snapshots[mesocycle.inputsHash] = mesocycle
        current.value = mesocycle
    }
    override suspend fun snapshotByInputsHash(hash: String): Mesocycle? = snapshots[hash]
    override fun observeOverrides(): Flow<List<PlanOverride>> = overridesState
    override suspend fun overrides(): List<PlanOverride> = overridesState.value
    override suspend fun addOverride(override: PlanOverride) { events.add("plans.addOverride"); overridesState.value = overridesState.value + override }
    override suspend fun revokeOverride(id: String, at: Instant) {
        events.add("plans.revokeOverride")
        overridesState.value = overridesState.value.map { if (it.id == id) it.copy(revokedAt = at) else it }
    }
    override suspend fun nutritionState(): NutritionState? = nutrition
    override suspend fun saveNutritionState(state: NutritionState) { nutrition = state }
}

class FakeWorkoutRepository(private val events: MutableList<String> = ArrayList()) : WorkoutRepository {
    private val sessions = LinkedHashMap<String, SessionRecord>()
    private val sets = ArrayList<SetLog>()
    private val adjustments = ArrayList<Adjustment>()
    private val version = MutableStateFlow(0L)
    private val open = MutableStateFlow<SessionRecord?>(null)
    val allAdjustments: List<Adjustment> get() = adjustments.toList()
    val allSets: List<SetLog> get() = sets.toList()

    private fun bump() { version.value = version.value + 1; open.value = sessions.values.lastOrNull { it.status == com.gymcoach.core.model.SessionStatus.IN_PROGRESS } }

    override suspend fun startSession(record: SessionRecord) { events.add("workouts.startSession"); sessions[record.id] = record; bump() }
    override suspend fun updateSession(record: SessionRecord) { events.add("workouts.updateSession"); sessions[record.id] = record; bump() }
    override suspend fun getSession(id: String): SessionRecord? = sessions[id]
    override fun observeOpenSession(): Flow<SessionRecord?> = open
    override suspend fun sessionsBetween(from: LocalDate, to: LocalDate): List<SessionRecord> = sessions.values.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    override fun observeSets(sessionId: String): Flow<List<SetLog>> = version.map { sets.filter { it.sessionId == sessionId && it.supersededById == null }.sortedBy { it.loggedAt } }
    override suspend fun setsForSession(sessionId: String, includeSuperseded: Boolean): List<SetLog> =
        sets.filter { it.sessionId == sessionId && (includeSuperseded || it.supersededById == null) }
    override suspend fun setsForSessions(sessionIds: List<String>): List<SetLog> = sets.filter { it.sessionId in sessionIds && it.supersededById == null }
    override suspend fun setsForExercise(exerciseId: String, limitSessions: Int): List<SetLog> {
        val ids = sets.filter { it.exerciseId == exerciseId }.sortedByDescending { it.loggedAt }.map { it.sessionId }.distinct().take(limitSessions)
        return sets.filter { it.exerciseId == exerciseId && it.sessionId in ids }
    }
    override suspend fun lastLoggedAtByExercise(): Map<String, Instant> = sets
        .filter { it.supersededById == null && it.kind != SetKind.RAMP }.groupBy { it.exerciseId }.mapValues { (_, l) -> l.maxOf { it.loggedAt } }
    override suspend fun addSet(set: SetLog) { events.add("workouts.addSet"); sets.add(set); bump() }
    /** Never an in-place value update: the old row only gets supersededById, the replacement is a new row. */
    override suspend fun supersedeSet(oldId: String, replacement: SetLog) {
        events.add("workouts.supersedeSet")
        val i = sets.indexOfFirst { it.id == oldId }
        require(i >= 0) { "unknown set $oldId" }
        sets[i] = sets[i].copy(supersededById = replacement.id)
        sets.add(replacement.copy(supersedesId = oldId))
        bump()
    }
    override suspend fun recordAdjustment(adjustment: Adjustment) { events.add("workouts.recordAdjustment"); adjustments.add(adjustment); bump() }
    override suspend fun undoAdjustment(id: String, at: Instant) { adjustments.replaceAll { if (it.id == id) it.copy(undoneAt = at) else it }; bump() }
    override fun observeAdjustments(limit: Int): Flow<List<Adjustment>> = version.map { adjustments.sortedByDescending { it.createdAt }.take(limit) }
    override suspend fun adjustmentsForSession(sessionId: String): List<Adjustment> = adjustments.filter { it.sessionId == sessionId }
}

class FakeProgressionRepository : ProgressionRepository {
    private val states = LinkedHashMap<String, ProgressionState>()
    override suspend fun get(key: ProgressionKey): ProgressionState? = states[key.id]
    override suspend fun getAll(): List<ProgressionState> = states.values.toList()
    override suspend fun getForExercise(exerciseId: String): List<ProgressionState> = states.values.filter { it.key.exerciseId == exerciseId }
    override suspend fun save(state: ProgressionState) { states[state.key.id] = state }
    override suspend fun saveAll(states: List<ProgressionState>) { for (s in states) this.states[s.key.id] = s }
}

class FakeMetricsRepository : MetricsRepository {
    private val body = MutableStateFlow<List<BodyMetric>>(emptyList())
    private val cardio = ArrayList<CardioLog>()
    private val nutrition = ArrayList<NutritionLog>()
    private val readiness = MutableStateFlow<List<ReadinessCheckin>>(emptyList())
    private val weekly = ArrayList<WeeklyCheckin>()
    private val questions = MutableStateFlow<List<CheckinQuestion>>(emptyList())
    val cardioLogs: List<CardioLog> get() = cardio.toList()
    override fun observeBodyMetrics(days: Int): Flow<List<BodyMetric>> = body
    override suspend fun bodyMetricsBetween(from: LocalDate, to: LocalDate): List<BodyMetric> = body.value.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.date }
    override suspend fun addBodyMetric(metric: BodyMetric) { body.value = body.value + metric }
    override suspend fun addCardioLog(log: CardioLog) { cardio.add(log) }
    override suspend fun cardioBetween(from: LocalDate, to: LocalDate): List<CardioLog> = cardio.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    override suspend fun addNutritionLog(log: NutritionLog) { nutrition.add(log) }
    override suspend fun nutritionBetween(from: LocalDate, to: LocalDate): List<NutritionLog> = nutrition.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    override suspend fun addReadiness(checkin: ReadinessCheckin) { readiness.value = readiness.value + checkin }
    override suspend fun readinessFor(date: LocalDate): ReadinessCheckin? = readiness.value.filter { it.date == date }.maxByOrNull { it.loggedAt }
    override suspend fun readinessBetween(from: LocalDate, to: LocalDate): List<ReadinessCheckin> = readiness.value.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.loggedAt }
    override fun observeReadiness(days: Int): Flow<List<ReadinessCheckin>> = readiness
    override suspend fun saveWeeklyCheckin(checkin: WeeklyCheckin) { weekly.removeAll { it.weekStart == checkin.weekStart }; weekly.add(checkin) }
    override suspend fun weeklyCheckinFor(weekStart: LocalDate): WeeklyCheckin? = weekly.firstOrNull { it.weekStart == weekStart }
    override suspend fun latestWeeklyCheckins(count: Int): List<WeeklyCheckin> = weekly.sortedByDescending { it.weekStart }.take(count)
    override fun observeQuestions(): Flow<List<CheckinQuestion>> = questions
    override suspend fun activeQuestions(on: LocalDate): List<CheckinQuestion> =
        questions.value.filter { (it.activeFrom == null || !it.activeFrom!!.isAfter(on)) && (it.activeUntil == null || !it.activeUntil!!.isBefore(on)) }
    override suspend fun saveQuestion(question: CheckinQuestion) { questions.value = questions.value.filter { it.id != question.id } + question }
}

class FakeCoachRepository : CoachRepository {
    private val conversations = ArrayList<ChatConversation>()
    private val messages = ArrayList<ChatMessage>()
    private val proposalsState = MutableStateFlow<List<Proposal>>(emptyList())
    private val spend = ArrayList<SpendRecord>()
    private val messageVersion = MutableStateFlow(0)
    override suspend fun openConversation(): ChatConversation? = conversations.lastOrNull { it.status == com.gymcoach.core.model.ConversationStatus.OPEN }
    override suspend fun conversation(id: String): ChatConversation? = conversations.firstOrNull { it.id == id }
    override suspend fun listConversations(): List<ChatConversation> = conversations.toList()
    override suspend fun createConversation(conversation: ChatConversation) { conversations.add(conversation) }
    override suspend fun updateConversation(conversation: ChatConversation) { conversations.replaceAll { if (it.id == conversation.id) conversation else it } }
    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = messageVersion.map { messages.filter { it.conversationId == conversationId }.sortedBy { it.seq } }
    override suspend fun messages(conversationId: String): List<ChatMessage> = messages.filter { it.conversationId == conversationId }.sortedBy { it.seq }
    override suspend fun addMessage(message: ChatMessage) { messages.add(message); messageVersion.value = messageVersion.value + 1 }
    override suspend fun updateMessage(message: ChatMessage) { messages.replaceAll { if (it.id == message.id) message else it }; messageVersion.value = messageVersion.value + 1 }
    override fun observePendingProposals(): Flow<List<Proposal>> = proposalsState.map { l -> l.filter { it.status == ProposalStatus.PENDING } }
    override suspend fun proposals(conversationId: String): List<Proposal> = proposalsState.value.filter { it.conversationId == conversationId }
    override suspend fun proposal(id: String): Proposal? = proposalsState.value.firstOrNull { it.id == id }
    override suspend fun addProposal(proposal: Proposal) { proposalsState.value = proposalsState.value + proposal }
    override suspend fun updateProposal(proposal: Proposal) { proposalsState.value = proposalsState.value.map { if (it.id == proposal.id) proposal else it } }
    override suspend fun decideProposal(id: String, status: ProposalStatus, decidedAt: Instant, resultSummary: String, overrideId: String?) {
        proposalsState.value = proposalsState.value.map { if (it.id == id) it.copy(status = status, decidedAt = decidedAt, resultSummary = resultSummary, overrideId = overrideId) else it }
    }
    override suspend fun addSpend(record: SpendRecord) { spend.add(record) }
    override suspend fun spendSince(from: Instant): List<SpendRecord> = spend.filter { !it.at.isBefore(from) }
}

class FakeSettingsRepository(private val events: MutableList<String> = ArrayList(), initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<AppSettings> = state.asStateFlow()
    override suspend fun get(): AppSettings = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val before = state.value
        val after = transform(before)
        if (after.deloadTriggersProposed != before.deloadTriggersProposed) events.add("settings.deloadTriggersProposed")
        else if (after != before) events.add("settings.update")
        state.value = after
    }
}

class FakeApiKeyStore : ApiKeyStore {
    private var key: String? = null
    private var bridge: String? = null
    override suspend fun hasKey(): Boolean = key != null
    override suspend fun store(key: String) { this.key = key }
    override suspend fun read(): String? = key
    override suspend fun clear() { key = null }
    override suspend fun storeBridgeSecret(secret: String) { bridge = secret }
    override suspend fun readBridgeSecret(): String? = bridge
}

class FakeKnowledgeSource(private val files: Map<String, String> = emptyMap()) : KnowledgeSource {
    override fun exists(relativePath: String): Boolean = files.containsKey(relativePath)
    override fun readText(relativePath: String): String = checkNotNull(files[relativePath]) { "missing $relativePath" }
}

/** Scripted connectivity: tests flip [online]. */
class FakeNetworkMonitor(initial: Boolean = true) : NetworkMonitor {
    private val state = MutableStateFlow(initial)
    var online: Boolean
        get() = state.value
        set(value) { state.value = value }
    override fun isOnline(): Boolean = state.value
    override fun observe(): Flow<Boolean> = state
}

class FakeUnreadableDocs : UnreadableDocsRepository {
    private val state = MutableStateFlow<List<UnreadableDoc>>(emptyList())
    override fun observe(): Flow<List<UnreadableDoc>> = state
    override fun report(doc: UnreadableDoc) { if (state.value.none { it.type == doc.type && it.id == doc.id }) state.value = state.value + doc }
}

/** Scripted backend: [frames] are replayed for every stream() call; complete() returns [completion]. */
class FakeClaudeBackend(
    override val mode: BackendMode = BackendMode.DIRECT_API,
    var frames: List<StreamFrame> = emptyList(),
    var completion: StreamFrame = StreamFrame(JsonObject(emptyMap())),
    var failure: BackendException? = null,
) : ClaudeBackend {
    val requests: MutableList<JsonObject> = ArrayList()
    override fun stream(body: JsonObject): Flow<StreamFrame> = flow {
        requests.add(body)
        failure?.let { throw it }
        for (f in frames) emit(f)
    }
    override suspend fun complete(body: JsonObject): StreamFrame { requests.add(body); failure?.let { throw it }; return completion }
    override suspend fun test(): TestResult = TestResult(error = failure?.error, requestId = null)
}
