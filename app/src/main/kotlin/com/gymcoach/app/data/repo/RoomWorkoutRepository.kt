package com.gymcoach.app.data.repo

import com.gymcoach.app.data.db.SessionEntity
import com.gymcoach.app.data.db.WorkoutDao
import com.gymcoach.core.model.Adjustment
import com.gymcoach.core.model.SessionRecord
import com.gymcoach.core.model.SetLog
import com.gymcoach.core.repo.UnreadableDocsRepository
import com.gymcoach.core.repo.WorkoutRepository
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Sessions, set logs and adjustments. Set logs are SACRED: they are inserted, never updated; a
 * correction goes through [supersedeSet] (WorkoutDao.supersede: insert the replacement + set
 * superseded_by_id on the old row in one transaction — the only column ever UPDATEd).
 */
class RoomWorkoutRepository(
    private val dao: WorkoutDao,
    private val json: Json,
    private val unreadable: UnreadableDocsRepository,
) : WorkoutRepository {
    private fun session(e: SessionEntity?): SessionRecord? {
        if (e == null) return null
        val doc = e.toDoc(json)
        doc.asUnreadableDoc()?.let { unreadable.report(it) }
        return doc.valueOrNull()
    }

    override suspend fun startSession(record: SessionRecord) = withContext(Dispatchers.IO) { dao.insertSession(record.toEntity(json)) }

    override suspend fun updateSession(record: SessionRecord) = withContext(Dispatchers.IO) { dao.updateSession(record.toEntity(json)) }

    override suspend fun getSession(id: String): SessionRecord? = withContext(Dispatchers.IO) { session(dao.session(id)) }

    override fun observeOpenSession(): Flow<SessionRecord?> = dao.observeOpen().map { session(it) }

    override suspend fun sessionsBetween(from: LocalDate, to: LocalDate): List<SessionRecord> = withContext(Dispatchers.IO) {
        dao.sessionsBetween(from.toString(), to.toString()).mapNotNull { session(it) }
    }

    override fun observeSets(sessionId: String): Flow<List<SetLog>> = dao.observeSets(sessionId).map { list -> list.map { it.toModel() } }

    override suspend fun setsForSession(sessionId: String, includeSuperseded: Boolean): List<SetLog> = withContext(Dispatchers.IO) {
        dao.setsForSession(sessionId, includeSuperseded).map { it.toModel() }
    }

    override suspend fun setsForSessions(sessionIds: List<String>): List<SetLog> = withContext(Dispatchers.IO) {
        if (sessionIds.isEmpty()) {
            emptyList()
        } else {
            // SQLite caps bound variables (999 on old versions); chunk to stay far below it.
            sessionIds.distinct().chunked(500).flatMap { ids -> dao.setsForSessions(ids) }
                .sortedBy { it.loggedAt }
                .map { it.toModel() }
        }
    }

    /**
     * Non-superseded sets of the newest [limitSessions] sessions that contain [exerciseId], in
     * chronological order (oldest first). WorkoutDao.recentSetsForExercise limits ROWS, so a generous
     * row window is read and cut to whole sessions here.
     */
    override suspend fun setsForExercise(exerciseId: String, limitSessions: Int): List<SetLog> = withContext(Dispatchers.IO) {
        if (limitSessions <= 0) {
            emptyList()
        } else {
            val rows = dao.recentSetsForExercise(exerciseId, limitSessions * ROWS_PER_SESSION_WINDOW)   // newest first
            val keepSessions = rows.map { it.sessionId }.distinct().take(limitSessions).toSet()
            rows.filter { it.sessionId in keepSessions }.sortedBy { it.loggedAt }.map { it.toModel() }
        }
    }

    override suspend fun lastLoggedAtByExercise(): Map<String, Instant> = withContext(Dispatchers.IO) {
        dao.lastLoggedAtByExercise().associate { it.exerciseId to Instant.ofEpochMilli(it.lastLoggedAt) }
    }

    override suspend fun addSet(set: SetLog) = withContext(Dispatchers.IO) { dao.insertSet(set.toEntity()) }

    /** One transaction; the old row's values are never touched (only superseded_by_id is set). */
    override suspend fun supersedeSet(oldId: String, replacement: SetLog) {
        withContext(Dispatchers.IO) {
            val row = replacement.copy(supersedesId = replacement.supersedesId ?: oldId, supersededById = null)
            dao.supersede(oldId, row.toEntity())
        }
    }

    override suspend fun recordAdjustment(adjustment: Adjustment) = withContext(Dispatchers.IO) { dao.insertAdjustment(adjustment.toEntity(json)) }

    override suspend fun undoAdjustment(id: String, at: Instant) = withContext(Dispatchers.IO) { dao.undoAdjustment(id, at.toEpochMilli()) }

    override fun observeAdjustments(limit: Int): Flow<List<Adjustment>> =
        dao.observeAdjustments(limit).map { list -> list.map { it.toModel(json) } }

    override suspend fun adjustmentsForSession(sessionId: String): List<Adjustment> = withContext(Dispatchers.IO) {
        dao.adjustmentsForSession(sessionId).map { it.toModel(json) }
    }

    private companion object {
        /** Upper bound of logged rows per session and exercise (sets × sides × corrections) used for the row window. */
        const val ROWS_PER_SESSION_WINDOW: Int = 40
    }
}
