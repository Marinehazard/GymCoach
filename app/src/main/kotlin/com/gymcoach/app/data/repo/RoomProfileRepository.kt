package com.gymcoach.app.data.repo

import com.gymcoach.app.data.db.ProfileDao
import com.gymcoach.core.model.Profile
import com.gymcoach.core.repo.ProfileRepository
import com.gymcoach.core.repo.UnreadableDocsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Single-row profile document (§7.1). `unreadable` is unused today (Profile has no schema_version) but kept for the AppContainer constructor (§1.5). */
class RoomProfileRepository(
    private val dao: ProfileDao,
    private val json: Json,
    @Suppress("unused") private val unreadable: UnreadableDocsRepository,
) : ProfileRepository {
    override fun observe(): Flow<Profile?> = dao.observe().map { it?.toModel(json) }

    override suspend fun get(): Profile? = withContext(Dispatchers.IO) { dao.get()?.toModel(json) }

    override suspend fun save(profile: Profile) = withContext(Dispatchers.IO) { dao.upsert(profile.toEntity(json)) }
}
