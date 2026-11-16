package com.ouor.localchat.storage.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ouor.localchat.storage.db.entities.AttachmentRefEntity
import com.ouor.localchat.storage.db.entities.DraftEntity
import com.ouor.localchat.storage.db.entities.MessageEntity
import com.ouor.localchat.storage.db.entities.SessionEntity
import com.ouor.localchat.storage.db.entities.SummaryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Sessions, message trees, summaries and drafts. Message and draft writes update
 * attachment_refs in the same transaction (architecture 8.1).
 */
@Dao
abstract class ChatDao {

    // Sessions

    @Query("SELECT * FROM sessions WHERE title LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY updatedAt DESC")
    abstract fun observeSessions(query: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract fun observeSession(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract suspend fun session(id: String): SessionEntity?

    @Upsert
    abstract suspend fun upsertSession(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, titleSource = :source, updatedAt = :now WHERE id = :id")
    abstract suspend fun setTitle(id: String, title: String, source: String, now: Long)

    @Query("UPDATE sessions SET rootSelectedId = :rootSelectedId WHERE id = :id")
    abstract suspend fun setRootSelected(id: String, rootSelectedId: String?)

    @Query("DELETE FROM sessions WHERE id = :id")
    protected abstract suspend fun deleteSessionRow(id: String)

    @Query("DELETE FROM sessions")
    protected abstract suspend fun deleteAllSessionRows()

    // Messages

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt")
    abstract fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt")
    abstract suspend fun messages(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun message(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE status IN ('PENDING', 'STREAMING')")
    abstract suspend fun unfinished(): List<MessageEntity>

    @Query("UPDATE messages SET selectedChildId = :childId WHERE id = :id")
    abstract suspend fun setSelectedChild(id: String, childId: String?)

    @Upsert
    protected abstract suspend fun upsertMessageRows(messages: List<MessageEntity>)

    /** Saves messages and replaces their attachment refs. */
    @Transaction
    open suspend fun upsertMessages(messages: List<MessageEntity>, refs: Map<String, List<String>>) {
        upsertMessageRows(messages)
        for ((messageId, ids) in refs) replaceRefs(AttachmentRefEntity.MESSAGE, messageId, ids)
    }

    // Summaries

    @Query("SELECT * FROM summaries WHERE sessionId = :sessionId ORDER BY createdAt")
    abstract fun observeSummaries(sessionId: String): Flow<List<SummaryEntity>>

    @Query("SELECT * FROM summaries WHERE sessionId = :sessionId ORDER BY createdAt")
    abstract suspend fun summaries(sessionId: String): List<SummaryEntity>

    @Upsert
    abstract suspend fun upsertSummary(summary: SummaryEntity)

    // Drafts

    @Query("SELECT * FROM drafts WHERE `key` = :key")
    abstract fun observeDraft(key: String): Flow<DraftEntity?>

    @Query("SELECT * FROM drafts WHERE `key` = :key")
    abstract suspend fun draft(key: String): DraftEntity?

    @Upsert
    protected abstract suspend fun upsertDraftRow(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE `key` = :key")
    protected abstract suspend fun deleteDraftRow(key: String)

    @Transaction
    open suspend fun upsertDraft(draft: DraftEntity, attachmentIds: List<String>) {
        upsertDraftRow(draft)
        replaceRefs(AttachmentRefEntity.DRAFT, draft.key, attachmentIds)
    }

    @Transaction
    open suspend fun deleteDraft(key: String) {
        deleteDraftRow(key)
        deleteRefs(AttachmentRefEntity.DRAFT, key)
    }

    // Attachment refs

    @Upsert
    protected abstract suspend fun upsertRefs(refs: List<AttachmentRefEntity>)

    @Query("DELETE FROM attachment_refs WHERE ownerType = :ownerType AND ownerId = :ownerId")
    protected abstract suspend fun deleteRefs(ownerType: String, ownerId: String)

    @Query("DELETE FROM attachment_refs WHERE ownerType = 'MESSAGE' AND ownerId IN (SELECT id FROM messages WHERE sessionId = :sessionId)")
    protected abstract suspend fun deleteMessageRefsOfSession(sessionId: String)

    @Query("DELETE FROM attachment_refs")
    protected abstract suspend fun deleteAllRefs()

    @Query("DELETE FROM drafts")
    protected abstract suspend fun deleteAllDraftRows()

    protected suspend fun replaceRefs(ownerType: String, ownerId: String, attachmentIds: List<String>) {
        deleteRefs(ownerType, ownerId)
        upsertRefs(attachmentIds.distinct().map { AttachmentRefEntity(it, ownerType, ownerId) })
    }

    /** C-04: the session, its messages (cascade), summaries (cascade), draft and refs. */
    @Transaction
    open suspend fun deleteSession(id: String) {
        deleteMessageRefsOfSession(id)
        deleteDraft(id)
        deleteSessionRow(id)
    }

    /** K-09: every session, message, summary, draft and ref. */
    @Transaction
    open suspend fun deleteEverything() {
        deleteAllRefs()
        deleteAllDraftRows()
        deleteAllSessionRows()
    }
}
