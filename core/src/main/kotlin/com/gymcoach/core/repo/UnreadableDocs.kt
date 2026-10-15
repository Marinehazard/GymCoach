// core/src/main/kotlin/com/gymcoach/core/repo/UnreadableDocs.kt
package com.gymcoach.core.repo

import kotlinx.coroutines.flow.Flow

/** A stored JSON document whose schema_version is newer than this build understands (§7.2). */
data class UnreadableDoc(val type: String, val version: Int, val id: String)

interface UnreadableDocsRepository {
    fun observe(): Flow<List<UnreadableDoc>>
    fun report(doc: UnreadableDoc)        // called by :app mappers; idempotent per (type, id)
}
