// core/src/main/kotlin/com/gymcoach/core/repo/Repositories.kt
package com.gymcoach.core.repo

import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.AppSettings
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
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.model.SpendRecord
import com.gymcoach.core.model.WeeklyCheckin
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    fun observe(): Flow<Profile?>
    suspend fun get(): Profile?
    suspend fun save(profile: Profile)
}

interface GymRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<GymSet>>
    suspend fun getAll(includeArchived: Boolean = false): List<GymSet>
    suspend fun get(id: String): GymSet?
    suspend fun upsert(gym: GymSet)
    suspend fun setDefault(id: String)
    suspend fun touchLastUsed(id: String, at: Instant)
    suspend fun setArchived(id: String, archived: Boolean)
}

interface PlanRepository {
    fun observeCurrent(): Flow<Mesocycle?>
    suspend fun getCurrent(): Mesocycle?
    suspend fun saveSnapshot(mesocycle: Mesocycle, computedAt: Instant)     // marks it current; keeps older snapshots
    suspend fun snapshotByInputsHash(hash: String): Mesocycle?
    fun observeOverrides(): Flow<List<PlanOverride>>
    suspend fun overrides(): List<PlanOverride>
    suspend fun addOverride(override: PlanOverride)
    suspend fun revokeOverride(id: String, at: Instant)
    suspend fun nutritionState(): NutritionState?
    suspend fun saveNutritionState(state: NutritionState)
}

interface WorkoutRepository {
    suspend fun startSession(record: SessionRecord)
    suspend fun updateSession(record: SessionRecord)
    suspend fun getSession(id: String): SessionRecord?
    fun observeOpenSession(): Flow<SessionRecord?>                          // status IN_PROGRESS
    suspend fun sessionsBetween(from: LocalDate, to: LocalDate): List<SessionRecord>
    fun observeSets(sessionId: String): Flow<List<SetLog>>                  // non-superseded, ordered
    suspend fun setsForSession(sessionId: String, includeSuperseded: Boolean = false): List<SetLog>
    suspend fun setsForSessions(sessionIds: List<String>): List<SetLog>
    suspend fun setsForExercise(exerciseId: String, limitSessions: Int): List<SetLog>
    /** exerciseId -> instant of the newest non-superseded WORKING/AMRAP/CALIBRATION/DROP set, over ALL history (LAY-01 gaps beyond 12 weeks, §4.7 step 2). */
    suspend fun lastLoggedAtByExercise(): Map<String, Instant>
    suspend fun addSet(set: SetLog)
    suspend fun supersedeSet(oldId: String, replacement: SetLog)            // never UPDATE values in place
    suspend fun recordAdjustment(adjustment: Adjustment)
    suspend fun undoAdjustment(id: String, at: Instant)
    fun observeAdjustments(limit: Int): Flow<List<Adjustment>>
    suspend fun adjustmentsForSession(sessionId: String): List<Adjustment>
}

interface ProgressionRepository {
    suspend fun get(key: ProgressionKey): ProgressionState?
    suspend fun getAll(): List<ProgressionState>
    suspend fun getForExercise(exerciseId: String): List<ProgressionState>
    suspend fun save(state: ProgressionState)
    suspend fun saveAll(states: List<ProgressionState>)
}

interface MetricsRepository {
    fun observeBodyMetrics(days: Int): Flow<List<BodyMetric>>
    suspend fun bodyMetricsBetween(from: LocalDate, to: LocalDate): List<BodyMetric>
    suspend fun addBodyMetric(metric: BodyMetric)
    suspend fun addCardioLog(log: CardioLog)
    suspend fun cardioBetween(from: LocalDate, to: LocalDate): List<CardioLog>
    suspend fun addNutritionLog(log: NutritionLog)
    suspend fun nutritionBetween(from: LocalDate, to: LocalDate): List<NutritionLog>
    suspend fun addReadiness(checkin: ReadinessCheckin)
    suspend fun readinessFor(date: LocalDate): ReadinessCheckin?             // latest for the date
    suspend fun readinessBetween(from: LocalDate, to: LocalDate): List<ReadinessCheckin>
    fun observeReadiness(days: Int): Flow<List<ReadinessCheckin>>
    suspend fun saveWeeklyCheckin(checkin: WeeklyCheckin)
    suspend fun weeklyCheckinFor(weekStart: LocalDate): WeeklyCheckin?
    suspend fun latestWeeklyCheckins(count: Int): List<WeeklyCheckin>
    fun observeQuestions(): Flow<List<CheckinQuestion>>
    suspend fun activeQuestions(on: LocalDate): List<CheckinQuestion>
    suspend fun saveQuestion(question: CheckinQuestion)
}

interface CoachRepository {
    suspend fun openConversation(): ChatConversation?
    suspend fun conversation(id: String): ChatConversation?
    suspend fun listConversations(): List<ChatConversation>
    suspend fun createConversation(conversation: ChatConversation)
    suspend fun updateConversation(conversation: ChatConversation)
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun messages(conversationId: String): List<ChatMessage>
    suspend fun addMessage(message: ChatMessage)
    suspend fun updateMessage(message: ChatMessage)
    fun observePendingProposals(): Flow<List<Proposal>>
    suspend fun proposals(conversationId: String): List<Proposal>
    suspend fun proposal(id: String): Proposal?
    suspend fun addProposal(proposal: Proposal)
    suspend fun updateProposal(proposal: Proposal)
    suspend fun decideProposal(id: String, status: ProposalStatus, decidedAt: Instant, resultSummary: String, overrideId: String?)
    suspend fun addSpend(record: SpendRecord)
    suspend fun spendSince(from: Instant): List<SpendRecord>
}

interface SettingsRepository {
    fun observe(): Flow<AppSettings>
    suspend fun get(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface ApiKeyStore {
    suspend fun hasKey(): Boolean
    suspend fun store(key: String)
    suspend fun read(): String?            // null = "no key stored" (also after any decrypt failure, R2 §6.1)
    suspend fun clear()
    suspend fun storeBridgeSecret(secret: String)
    suspend fun readBridgeSecret(): String?
}

/** Byte source for the knowledge JSON; :core provides the class-path implementation, :app the assets fallback. */
interface KnowledgeSource {
    fun exists(relativePath: String): Boolean          // relative to "gymcoach/knowledge/"
    fun readText(relativePath: String): String
}
