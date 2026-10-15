package com.gymcoach.app.data.repo

import com.gymcoach.app.data.db.OverrideEntity
import com.gymcoach.app.data.db.PlanDao
import com.gymcoach.app.data.db.PlanSnapshotEntity
import com.gymcoach.core.model.Mesocycle
import com.gymcoach.core.model.NutritionState
import com.gymcoach.core.model.PlanOverride
import com.gymcoach.core.repo.PlanRepository
import com.gymcoach.core.repo.UnreadableDocsRepository
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RoomPlanRepository(
    private val dao: PlanDao,
    private val json: Json,
    private val unreadable: UnreadableDocsRepository,
) : PlanRepository {
    private fun snapshot(e: PlanSnapshotEntity?): Mesocycle? {
        if (e == null) return null
        val doc = e.toDoc(json)
        doc.asUnreadableDoc()?.let { unreadable.report(it) }
        return doc.valueOrNull()
    }

    private fun readableOverrides(list: List<OverrideEntity>): List<PlanOverride> = list.mapNotNull { e ->
        val doc = e.toDoc(json)
        doc.asUnreadableDoc()?.let { unreadable.report(it) }
        doc.valueOrNull()
    }

    override fun observeCurrent(): Flow<Mesocycle?> = dao.observeCurrent().map { snapshot(it) }

    override suspend fun getCurrent(): Mesocycle? = withContext(Dispatchers.IO) { snapshot(dao.current()) }

    /** Marks the new snapshot current in one transaction (PlanDao.saveCurrent); older snapshots stay. */
    override suspend fun saveSnapshot(mesocycle: Mesocycle, computedAt: Instant) = withContext(Dispatchers.IO) {
        dao.saveCurrent(mesocycle.toSnapshotEntity(computedAt, isCurrent = true, json = json))
    }

    override suspend fun snapshotByInputsHash(hash: String): Mesocycle? = withContext(Dispatchers.IO) { snapshot(dao.byHash(hash)) }

    override fun observeOverrides(): Flow<List<PlanOverride>> = dao.observeOverrides().map { readableOverrides(it) }

    override suspend fun overrides(): List<PlanOverride> = withContext(Dispatchers.IO) { readableOverrides(dao.overrides()) }

    /** Append-only (ABORT on a duplicate id). */
    override suspend fun addOverride(override: PlanOverride) = withContext(Dispatchers.IO) { dao.insertOverride(override.toEntity(json)) }

    override suspend fun revokeOverride(id: String, at: Instant) = withContext(Dispatchers.IO) { dao.revoke(id, at.toEpochMilli()) }

    override suspend fun nutritionState(): NutritionState? = withContext(Dispatchers.IO) { dao.nutritionState()?.toModel(json) }

    override suspend fun saveNutritionState(state: NutritionState) = withContext(Dispatchers.IO) { dao.upsertNutritionState(state.toEntity(json)) }
}
