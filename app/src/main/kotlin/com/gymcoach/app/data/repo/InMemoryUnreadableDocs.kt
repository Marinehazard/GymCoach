package com.gymcoach.app.data.repo

import com.gymcoach.core.repo.UnreadableDoc
import com.gymcoach.core.repo.UnreadableDocsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-lifetime list of stored documents newer than this build (§7.2). Repositories report rows
 * they had to drop; MainActivity observes the list and shows the "data from a newer app version"
 * banner. Nothing is persisted: the rows themselves stay untouched in the database.
 */
class InMemoryUnreadableDocs : UnreadableDocsRepository {
    private val docs = MutableStateFlow<List<UnreadableDoc>>(emptyList())

    override fun observe(): Flow<List<UnreadableDoc>> = docs.asStateFlow()

    /** Idempotent per (type, id); a later report with another version replaces the earlier one. */
    override fun report(doc: UnreadableDoc) {
        docs.update { current ->
            val existing = current.firstOrNull { it.type == doc.type && it.id == doc.id }
            when {
                existing == doc -> current
                existing != null -> current.map { if (it.type == doc.type && it.id == doc.id) doc else it }
                else -> current + doc
            }
        }
    }
}
