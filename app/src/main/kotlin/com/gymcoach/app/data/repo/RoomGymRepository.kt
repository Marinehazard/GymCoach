package com.gymcoach.app.data.repo

import com.gymcoach.app.data.db.GymDao
import com.gymcoach.app.data.db.GymEntity
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.repo.GymRepository
import com.gymcoach.core.repo.UnreadableDocsRepository
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RoomGymRepository(
    private val dao: GymDao,
    private val json: Json,
    private val unreadable: UnreadableDocsRepository,
) : GymRepository {
    private fun readable(list: List<GymEntity>): List<GymSet> = list.mapNotNull { e ->
        val doc = e.toDoc(json)
        doc.asUnreadableDoc()?.let { unreadable.report(it) }
        doc.valueOrNull()
    }

    override fun observeAll(includeArchived: Boolean): Flow<List<GymSet>> = dao.observeAll(includeArchived).map { readable(it) }

    override suspend fun getAll(includeArchived: Boolean): List<GymSet> = withContext(Dispatchers.IO) { readable(dao.getAll(includeArchived)) }

    override suspend fun get(id: String): GymSet? = withContext(Dispatchers.IO) { dao.get(id)?.let { readable(listOf(it)).firstOrNull() } }

    override suspend fun upsert(gym: GymSet) = withContext(Dispatchers.IO) { dao.upsert(gym.toEntity(json)) }

    override suspend fun setDefault(id: String) = withContext(Dispatchers.IO) { dao.setDefault(id) }

    override suspend fun touchLastUsed(id: String, at: Instant) = withContext(Dispatchers.IO) { dao.touch(id, at.toEpochMilli()) }

    override suspend fun setArchived(id: String, archived: Boolean) = withContext(Dispatchers.IO) { dao.setArchived(id, archived) }
}
