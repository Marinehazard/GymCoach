package com.gymcoach.app.data.repo

import com.gymcoach.app.data.db.ChatConversationEntity
import com.gymcoach.app.data.db.CoachDao
import com.gymcoach.app.data.db.ProposalEntity
import com.gymcoach.core.model.ChatConversation
import com.gymcoach.core.model.ChatMessage
import com.gymcoach.core.model.Proposal
import com.gymcoach.core.model.ProposalStatus
import com.gymcoach.core.model.SpendRecord
import com.gymcoach.core.repo.CoachRepository
import com.gymcoach.core.repo.UnreadableDocsRepository
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RoomCoachRepository(
    private val dao: CoachDao,
    private val json: Json,
    private val unreadable: UnreadableDocsRepository,
) : CoachRepository {

    private fun conversations(list: List<ChatConversationEntity>): List<ChatConversation> = list.mapNotNull { e ->
        val doc = e.toDoc()
        doc.asUnreadableDoc()?.let { unreadable.report(it) }
        doc.valueOrNull()
    }

    private fun readableProposals(list: List<ProposalEntity>): List<Proposal> = list.mapNotNull { e ->
        val doc = e.toDoc(json)
        doc.asUnreadableDoc()?.let { unreadable.report(it) }
        doc.valueOrNull()
    }

    /** The default open conversation (CoachDao.open filters is_default = 1, §6.7). */
    override suspend fun openConversation(): ChatConversation? = withContext(Dispatchers.IO) {
        dao.open()?.let { conversations(listOf(it)).firstOrNull() }
    }

    override suspend fun conversation(id: String): ChatConversation? = withContext(Dispatchers.IO) {
        dao.conversation(id)?.let { conversations(listOf(it)).firstOrNull() }
    }

    override suspend fun listConversations(): List<ChatConversation> = withContext(Dispatchers.IO) { conversations(dao.list()) }

    override suspend fun createConversation(conversation: ChatConversation) = withContext(Dispatchers.IO) {
        dao.insertConversation(conversation.toEntity())
    }

    override suspend fun updateConversation(conversation: ChatConversation) = withContext(Dispatchers.IO) {
        dao.updateConversation(conversation.toEntity())
    }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { list -> list.map { it.toModel(json) } }

    override suspend fun messages(conversationId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        dao.messages(conversationId).map { it.toModel(json) }
    }

    override suspend fun addMessage(message: ChatMessage) = withContext(Dispatchers.IO) { dao.insertMessage(message.toEntity(json)) }

    override suspend fun updateMessage(message: ChatMessage) = withContext(Dispatchers.IO) { dao.updateMessage(message.toEntity(json)) }

    override fun observePendingProposals(): Flow<List<Proposal>> = dao.observePending().map { readableProposals(it) }

    override suspend fun proposals(conversationId: String): List<Proposal> = withContext(Dispatchers.IO) { readableProposals(dao.proposals(conversationId)) }

    override suspend fun proposal(id: String): Proposal? = withContext(Dispatchers.IO) {
        dao.proposal(id)?.let { readableProposals(listOf(it)).firstOrNull() }
    }

    override suspend fun addProposal(proposal: Proposal) = withContext(Dispatchers.IO) { dao.insertProposal(proposal.toEntity(json)) }

    override suspend fun updateProposal(proposal: Proposal) = withContext(Dispatchers.IO) { dao.updateProposal(proposal.toEntity(json)) }

    /**
     * Updates only the decision columns of the stored row (status, decided_at, result_summary,
     * override_id); every other column — including schema_version — is left exactly as stored. A null
     * [overrideId] keeps a previously recorded override id (e.g. finalize after apply).
     */
    override suspend fun decideProposal(id: String, status: ProposalStatus, decidedAt: Instant, resultSummary: String, overrideId: String?) {
        withContext(Dispatchers.IO) {
            val row = dao.proposal(id) ?: return@withContext
            dao.updateProposal(
                row.copy(
                    status = EnumNames.name(ProposalStatus.serializer(), status),
                    decidedAt = decidedAt.toEpochMilli(),
                    resultSummary = resultSummary,
                    overrideId = overrideId ?: row.overrideId,
                )
            )
        }
    }

    override suspend fun addSpend(record: SpendRecord) = withContext(Dispatchers.IO) { dao.insertSpend(record.toEntity(json)) }

    override suspend fun spendSince(from: Instant): List<SpendRecord> = withContext(Dispatchers.IO) {
        dao.spendSince(from.toEpochMilli()).map { it.toModel(json) }
    }
}
