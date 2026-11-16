package com.ouor.localchat.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.ouor.localchat.storage.db.entities.AttachmentEntity
import com.ouor.localchat.storage.db.entities.AttachmentRefEntity
import com.ouor.localchat.storage.db.entities.DraftEntity
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.MessageEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import com.ouor.localchat.storage.db.entities.SessionEntity
import com.ouor.localchat.storage.db.entities.SummaryEntity

/**
 * backup.BackupStore (track D): reads are suspend; writes are blocking because restore runs them
 * inside one `runInTransaction` while it reads the zip (architecture 9.4).
 */
@Dao
interface BackupDao {

    @Query("SELECT id FROM sessions ORDER BY updatedAt DESC")
    suspend fun sessionIds(): List<String>

    @Query("SELECT id FROM sessions WHERE id IN (:ids)")
    suspend fun existingSessionIds(ids: List<String>): List<String>

    @Query(
        "SELECT DISTINCT a.* FROM attachments a JOIN attachment_refs r ON r.attachmentId = a.id " +
            "WHERE (r.ownerType = 'MESSAGE' AND r.ownerId IN (SELECT id FROM messages WHERE sessionId IN (:sessionIds))) " +
            "OR (r.ownerType = 'DRAFT' AND r.ownerId IN (:sessionIds))",
    )
    suspend fun attachmentsFor(sessionIds: List<String>): List<AttachmentEntity>

    @Query("SELECT * FROM endpoints ORDER BY sortOrder, createdAt")
    suspend fun endpoints(): List<EndpointEntity>

    @Query("SELECT * FROM models ORDER BY rowid")
    suspend fun models(): List<ModelEntity>

    // Blocking writes (inside runInTransaction).

    @Query("DELETE FROM attachment_refs")
    fun deleteAllRefs()

    @Query("DELETE FROM drafts")
    fun deleteAllDrafts()

    /** Messages and summaries go by CASCADE. */
    @Query("DELETE FROM sessions")
    fun deleteAllSessions()

    /** Models go by CASCADE. */
    @Query("DELETE FROM endpoints")
    fun deleteAllEndpoints()

    @Insert
    fun insertEndpoints(rows: List<EndpointEntity>)

    @Insert
    fun insertModels(rows: List<ModelEntity>)

    @Upsert
    fun upsertAttachments(rows: List<AttachmentEntity>)

    @Query("DELETE FROM attachment_refs WHERE ownerType = 'MESSAGE' AND ownerId IN (SELECT id FROM messages WHERE sessionId = :sessionId)")
    fun deleteMessageRefsOf(sessionId: String)

    @Query("DELETE FROM attachment_refs WHERE ownerType = 'DRAFT' AND ownerId = :key")
    fun deleteDraftRefs(key: String)

    @Query("DELETE FROM drafts WHERE `key` = :key")
    fun deleteDraft(key: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    fun deleteSession(id: String)

    @Insert
    fun insertSession(row: SessionEntity)

    @Insert
    fun insertMessages(rows: List<MessageEntity>)

    @Insert
    fun insertSummaries(rows: List<SummaryEntity>)

    @Upsert
    fun upsertDraft(row: DraftEntity)

    @Upsert
    fun upsertRefs(rows: List<AttachmentRefEntity>)
}
