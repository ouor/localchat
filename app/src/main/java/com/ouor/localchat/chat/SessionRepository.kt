package com.ouor.localchat.chat

import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.MessageTree
import com.ouor.localchat.core.Selection
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Summary
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.storage.db.AppDatabase
import com.ouor.localchat.storage.db.attachmentIds
import com.ouor.localchat.storage.db.dao.AttachmentDao
import com.ouor.localchat.storage.db.dao.ChatDao
import com.ouor.localchat.storage.db.likeEscape
import com.ouor.localchat.storage.db.toEntity
import com.ouor.localchat.storage.db.toModel
import androidx.room.withTransaction
import com.ouor.localchat.storage.AttachmentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Sessions and message trees (public surface, architecture 8, 10.2). Returns core models only. */
@Singleton
class SessionRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: ChatDao,
    private val attachments: AttachmentDao,
    private val files: AttachmentFiles,
    private val clock: Clock,
) : ChatStore {

    private val queue = WriteQueue()

    /** Newest first; [query] filters titles by substring (C-08), empty = all. */
    fun observeSessions(query: String = ""): Flow<List<Session>> =
        dao.observeSessions(likeEscape(query)).map { list -> list.map { it.toModel() } }

    /** null once deleted. Needed for the title and Session.rootSelectedId (visible path). */
    fun observeSession(sessionId: String): Flow<Session?> = dao.observeSession(sessionId).map { it?.toModel() }

    /** All nodes of every branch; compute the visible path with core.MessageTree.path. */
    fun observeMessages(sessionId: String): Flow<List<MessageNode>> =
        dao.observeMessages(sessionId).map { list -> list.map { it.toModel() } }

    fun observeSummaries(sessionId: String): Flow<List<Summary>> =
        dao.observeSummaries(sessionId).map { list -> list.map { it.toModel() } }

    /**
     * Attachment rows for a draft's or message's ids, in [ids] order; ids without a row (GC'd, still being
     * copied in) are left out, so the UI shows a placeholder for them.
     */
    fun observeAttachments(ids: List<String>): Flow<List<Attachment>> =
        if (ids.isEmpty()) flowOf(emptyList())
        else attachments.observe(ids.distinct()).map { rows ->
            val byId = rows.associateBy { it.id }
            ids.distinct().mapNotNull { byId[it]?.toModel() }
        }

    /** C-03; also sets TitleSource.USER. */
    suspend fun rename(sessionId: String, title: String) {
        dao.setTitle(sessionId, title.trim(), TitleSource.USER.name, clock.now())
    }

    /** C-04: deletes the session and its unreferenced attachments. */
    suspend fun delete(sessionId: String) {
        dao.deleteSession(sessionId)
        collectAttachmentGarbage(clock.now() - INGEST_GRACE_MS)
    }

    /** K-09: every session, draft and chat attachment. Providers, keys and settings stay. */
    suspend fun deleteAll() {
        dao.deleteEverything()
        collectAttachmentGarbage(Long.MAX_VALUE)
    }

    /** M-06: select version [messageId]; ignored when it is not on the visible path. */
    suspend fun selectVersion(messageId: String) {
        val node = dao.message(messageId)?.toModel() ?: return
        val session = session(node.sessionId) ?: return
        val selection = MessageTree.selectVersion(session.rootSelectedId, nodes(node.sessionId), messageId) ?: return
        applySelection(node.sessionId, selection)
    }

    /** E-05 banner state. */
    val saveHealth: StateFlow<SaveHealth> = queue.health

    suspend fun retryPendingWrites() = queue.retry()

    // ChatStore

    override suspend fun session(id: String): Session? = dao.session(id)?.toModel()

    override suspend fun nodes(sessionId: String): List<MessageNode> = dao.messages(sessionId).map { it.toModel() }

    override suspend fun node(id: String): MessageNode? = dao.message(id)?.toModel()

    override suspend fun summaries(sessionId: String): List<Summary> = dao.summaries(sessionId).map { it.toModel() }

    override suspend fun createSession(session: Session) = dao.upsertSession(session.toEntity())

    override suspend fun insertNodes(sessionId: String, nodes: List<MessageNode>, selection: Selection) {
        db.withTransaction {
            dao.upsertMessages(nodes.map { it.toEntity() }, nodes.associate { it.id to it.attachmentIds() })
            applySelection(sessionId, selection)
            dao.session(sessionId)?.let { dao.upsertSession(it.copy(updatedAt = clock.now())) }
        }
    }

    override suspend fun saveNode(node: MessageNode) {
        queue.write(node.id) {
            db.withTransaction {
                // A run's last save can land after its session was deleted: drop it instead of failing
                // the FK forever and leaving E-05 FAILING (AN 7).
                if (dao.session(node.sessionId) == null) return@withTransaction
                // The selection pointer belongs to selectVersion/insertNodes, not to the run's copy.
                val selected = dao.message(node.id)?.selectedChildId ?: node.selectedChildId
                dao.upsertMessages(listOf(node.copy(selectedChildId = selected).toEntity()), mapOf(node.id to node.attachmentIds()))
            }
        }
    }

    override suspend fun updateSession(id: String, transform: (Session) -> Session) {
        db.withTransaction { dao.session(id)?.let { dao.upsertSession(transform(it.toModel()).toEntity()) } }
    }

    override suspend fun addSummary(summary: Summary) = dao.upsertSummary(summary.toEntity())

    override suspend fun unfinishedAssistants(): List<MessageNode> = dao.unfinished().map { it.toModel() }

    override suspend fun deleteDraft(key: String) = dao.deleteDraft(key)

    override suspend fun collectAttachmentGarbage(cutoff: Long) {
        val ids = attachments.orphans(cutoff)
        withContext(Dispatchers.IO) { ids.forEach(files::delete) }
        ids.chunked(500).forEach { attachments.delete(it) }
        val rows = attachments.allIds().toSet()
        withContext(Dispatchers.IO) { files.deleteWithoutRow(rows, cutoff) }
    }

    private suspend fun applySelection(sessionId: String, selection: Selection) {
        if (selection.parentId == null) dao.setRootSelected(sessionId, selection.childId)
        else dao.setSelectedChild(selection.parentId, selection.childId)
    }

    private companion object {
        /** Spares attachments still being ingested that no draft references yet. */
        const val INGEST_GRACE_MS = 10 * 60_000L
    }
}

enum class SaveHealth { OK, FAILING }
