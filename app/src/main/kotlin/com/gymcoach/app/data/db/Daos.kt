// app/src/main/kotlin/com/gymcoach/app/data/db/Daos.kt
package com.gymcoach.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1") fun observe(): Flow<ProfileEntity?>
    @Query("SELECT * FROM profile WHERE id = 1") suspend fun get(): ProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: ProfileEntity)
}
@Dao interface GymDao {
    @Query("SELECT * FROM gyms WHERE (:includeArchived = 1 OR archived = 0) ORDER BY last_used_at DESC, created_at DESC") fun observeAll(includeArchived: Boolean): Flow<List<GymEntity>>
    @Query("SELECT * FROM gyms WHERE (:includeArchived = 1 OR archived = 0) ORDER BY last_used_at DESC, created_at DESC") suspend fun getAll(includeArchived: Boolean): List<GymEntity>
    @Query("SELECT * FROM gyms WHERE id = :id") suspend fun get(id: String): GymEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: GymEntity)
    @Query("UPDATE gyms SET is_default = (id = :id)") suspend fun setDefault(id: String)
    @Query("UPDATE gyms SET last_used_at = :at WHERE id = :id") suspend fun touch(id: String, at: Long)
    @Query("UPDATE gyms SET archived = :archived WHERE id = :id") suspend fun setArchived(id: String, archived: Boolean)
}
@Dao abstract class PlanDao {
    @Query("SELECT * FROM plan_snapshots WHERE is_current = 1 LIMIT 1") abstract fun observeCurrent(): Flow<PlanSnapshotEntity?>
    @Query("SELECT * FROM plan_snapshots WHERE is_current = 1 LIMIT 1") abstract suspend fun current(): PlanSnapshotEntity?
    @Query("SELECT * FROM plan_snapshots WHERE inputs_hash = :hash ORDER BY computed_at DESC LIMIT 1") abstract suspend fun byHash(hash: String): PlanSnapshotEntity?
    @Query("UPDATE plan_snapshots SET is_current = 0") abstract suspend fun clearCurrent()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertSnapshot(e: PlanSnapshotEntity)
    @Transaction open suspend fun saveCurrent(e: PlanSnapshotEntity) { clearCurrent(); insertSnapshot(e) }
    @Query("SELECT * FROM overrides ORDER BY created_at ASC") abstract fun observeOverrides(): Flow<List<OverrideEntity>>
    @Query("SELECT * FROM overrides ORDER BY created_at ASC") abstract suspend fun overrides(): List<OverrideEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertOverride(e: OverrideEntity)
    @Query("UPDATE overrides SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL") abstract suspend fun revoke(id: String, at: Long)
    // nutrition_state lives here because RoomPlanRepository (PlanDao only, §1.5) implements PlanRepository.nutritionState()
    @Query("SELECT * FROM nutrition_state WHERE id = 1") abstract suspend fun nutritionState(): NutritionStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertNutritionState(e: NutritionStateEntity)
}
@Dao abstract class WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertSession(e: SessionEntity)
    @Update abstract suspend fun updateSession(e: SessionEntity)
    @Query("SELECT * FROM sessions WHERE id = :id") abstract suspend fun session(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE status = 'in_progress' ORDER BY started_at DESC LIMIT 1") abstract fun observeOpen(): Flow<SessionEntity?>
    @Query("SELECT * FROM sessions WHERE date BETWEEN :from AND :to ORDER BY date ASC") abstract suspend fun sessionsBetween(from: String, to: String): List<SessionEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertSet(e: SetLogEntity)
    @Query("UPDATE set_logs SET superseded_by_id = :newId WHERE id = :oldId AND superseded_by_id IS NULL") abstract suspend fun markSuperseded(oldId: String, newId: String): Int
    @Transaction open suspend fun supersede(oldId: String, replacement: SetLogEntity) { insertSet(replacement); markSuperseded(oldId, replacement.id) }
    @Query("SELECT * FROM set_logs WHERE session_id = :sessionId AND superseded_by_id IS NULL ORDER BY logged_at ASC") abstract fun observeSets(sessionId: String): Flow<List<SetLogEntity>>
    @Query("SELECT * FROM set_logs WHERE session_id = :sessionId AND (:includeSuperseded = 1 OR superseded_by_id IS NULL) ORDER BY logged_at ASC") abstract suspend fun setsForSession(sessionId: String, includeSuperseded: Boolean): List<SetLogEntity>
    @Query("SELECT * FROM set_logs WHERE session_id IN (:ids) AND superseded_by_id IS NULL ORDER BY logged_at ASC") abstract suspend fun setsForSessions(ids: List<String>): List<SetLogEntity>
    @Query("SELECT * FROM set_logs WHERE exercise_id = :exerciseId AND superseded_by_id IS NULL ORDER BY logged_at DESC LIMIT :limit") abstract suspend fun recentSetsForExercise(exerciseId: String, limit: Int): List<SetLogEntity>
    @Query("SELECT exercise_id, MAX(logged_at) AS last_logged_at FROM set_logs WHERE superseded_by_id IS NULL AND kind IN ('working','amrap','calibration','drop') GROUP BY exercise_id") abstract suspend fun lastLoggedAtByExercise(): List<ExerciseLastLogged>
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertAdjustment(e: AdjustmentEntity)
    @Query("UPDATE adjustments SET undone_at = :at WHERE id = :id AND undone_at IS NULL") abstract suspend fun undoAdjustment(id: String, at: Long)
    @Query("SELECT * FROM adjustments ORDER BY created_at DESC LIMIT :limit") abstract fun observeAdjustments(limit: Int): Flow<List<AdjustmentEntity>>
    @Query("SELECT * FROM adjustments WHERE session_id = :sessionId ORDER BY created_at ASC") abstract suspend fun adjustmentsForSession(sessionId: String): List<AdjustmentEntity>
}
@Dao interface ProgressionDao {
    @Query("SELECT * FROM progression_states WHERE `key` = :key") suspend fun get(key: String): ProgressionStateEntity?
    @Query("SELECT * FROM progression_states") suspend fun getAll(): List<ProgressionStateEntity>
    @Query("SELECT * FROM progression_states WHERE exercise_id = :exerciseId") suspend fun forExercise(exerciseId: String): List<ProgressionStateEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: ProgressionStateEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<ProgressionStateEntity>)
}
@Dao interface MetricsDao {
    @Query("SELECT * FROM body_metrics WHERE date >= :from ORDER BY date ASC") fun observeBodyMetrics(from: String): Flow<List<BodyMetricEntity>>
    @Query("SELECT * FROM body_metrics WHERE date BETWEEN :from AND :to ORDER BY date ASC") suspend fun bodyMetricsBetween(from: String, to: String): List<BodyMetricEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBodyMetric(e: BodyMetricEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCardio(e: CardioLogEntity)
    @Query("SELECT * FROM cardio_logs WHERE date BETWEEN :from AND :to ORDER BY date ASC") suspend fun cardioBetween(from: String, to: String): List<CardioLogEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertNutrition(e: NutritionLogEntity)
    @Query("SELECT * FROM nutrition_logs WHERE date BETWEEN :from AND :to ORDER BY date ASC") suspend fun nutritionBetween(from: String, to: String): List<NutritionLogEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertReadiness(e: ReadinessEntity)
    @Query("SELECT * FROM readiness WHERE date = :date ORDER BY logged_at DESC LIMIT 1") suspend fun readinessFor(date: String): ReadinessEntity?
    @Query("SELECT * FROM readiness WHERE date BETWEEN :from AND :to ORDER BY date ASC, logged_at DESC") suspend fun readinessBetween(from: String, to: String): List<ReadinessEntity>
    @Query("SELECT * FROM readiness WHERE date >= :from ORDER BY date ASC, logged_at DESC") fun observeReadiness(from: String): Flow<List<ReadinessEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertWeekly(e: WeeklyCheckinEntity)
    @Query("SELECT * FROM weekly_checkins WHERE week_start = :weekStart") suspend fun weeklyFor(weekStart: String): WeeklyCheckinEntity?
    @Query("SELECT * FROM weekly_checkins ORDER BY week_start DESC LIMIT :count") suspend fun latestWeekly(count: Int): List<WeeklyCheckinEntity>
    @Query("SELECT * FROM checkin_questions") fun observeQuestions(): Flow<List<CheckinQuestionEntity>>
    @Query("SELECT * FROM checkin_questions WHERE answered = 0 AND (active_until IS NULL OR active_until >= :onDate)") suspend fun activeQuestions(onDate: String): List<CheckinQuestionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertQuestion(e: CheckinQuestionEntity)
}
@Dao interface CoachDao {
    @Query("SELECT * FROM chat_conversations WHERE status IN ('open','awaiting_tool_results') AND is_default = 1 ORDER BY started_at DESC LIMIT 1") suspend fun open(): ChatConversationEntity?   // a refused conversation (is_default = 0, §6.7) is never the default again
    @Query("SELECT * FROM chat_conversations WHERE id = :id") suspend fun conversation(id: String): ChatConversationEntity?
    @Query("SELECT * FROM chat_conversations ORDER BY started_at DESC") suspend fun list(): List<ChatConversationEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertConversation(e: ChatConversationEntity)
    @Update suspend fun updateConversation(e: ChatConversationEntity)
    @Query("SELECT * FROM chat_messages WHERE conversation_id = :id ORDER BY seq ASC") fun observeMessages(id: String): Flow<List<ChatMessageEntity>>
    @Query("SELECT * FROM chat_messages WHERE conversation_id = :id ORDER BY seq ASC") suspend fun messages(id: String): List<ChatMessageEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMessage(e: ChatMessageEntity)
    @Update suspend fun updateMessage(e: ChatMessageEntity)
    @Query("SELECT * FROM proposals WHERE status = 'pending' ORDER BY created_at ASC") fun observePending(): Flow<List<ProposalEntity>>
    @Query("SELECT * FROM proposals WHERE conversation_id = :id ORDER BY created_at ASC") suspend fun proposals(id: String): List<ProposalEntity>
    @Query("SELECT * FROM proposals WHERE id = :id") suspend fun proposal(id: String): ProposalEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertProposal(e: ProposalEntity)
    @Update suspend fun updateProposal(e: ProposalEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSpend(e: SpendRecordEntity)
    @Query("SELECT * FROM spend_records WHERE at >= :from ORDER BY at ASC") suspend fun spendSince(from: Long): List<SpendRecordEntity>
}
