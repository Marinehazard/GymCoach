package com.gymcoach.app.data.repo

import com.gymcoach.app.data.db.ProgressionDao
import com.gymcoach.app.data.db.ProgressionStateEntity
import com.gymcoach.core.model.ProgressionKey
import com.gymcoach.core.model.ProgressionState
import com.gymcoach.core.repo.ProgressionRepository
import com.gymcoach.core.repo.UnreadableDocsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RoomProgressionRepository(
    private val dao: ProgressionDao,
    private val json: Json,
    private val unreadable: UnreadableDocsRepository,
) : ProgressionRepository {
    private fun readable(list: List<ProgressionStateEntity>): List<ProgressionState> = list.mapNotNull { e ->
        val doc = e.toDoc(json)
        doc.asUnreadableDoc()?.let { unreadable.report(it) }
        doc.valueOrNull()
    }

    override suspend fun get(key: ProgressionKey): ProgressionState? = withContext(Dispatchers.IO) {
        dao.get(key.id)?.let { readable(listOf(it)).firstOrNull() }
    }

    override suspend fun getAll(): List<ProgressionState> = withContext(Dispatchers.IO) { readable(dao.getAll()) }

    override suspend fun getForExercise(exerciseId: String): List<ProgressionState> = withContext(Dispatchers.IO) {
        readable(dao.forExercise(exerciseId))
    }

    override suspend fun save(state: ProgressionState) = withContext(Dispatchers.IO) { dao.upsert(state.toEntity(json)) }

    override suspend fun saveAll(states: List<ProgressionState>) {
        if (states.isEmpty()) return
        withContext(Dispatchers.IO) { dao.upsertAll(states.map { it.toEntity(json) }) }
    }
}
