package com.ouor.localchat.storage.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ouor.localchat.storage.db.entities.AttachmentEntity
import kotlinx.coroutines.flow.Flow

/** Attachment rows (written by attach/tools, read by chat). Files are AttachmentFiles' job. */
@Dao
interface AttachmentDao {

    @Upsert
    suspend fun upsert(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE id IN (:ids)")
    fun observe(ids: List<String>): Flow<List<AttachmentEntity>>

    @Query("SELECT id FROM attachments")
    suspend fun allIds(): List<String>

    /**
     * GC (C-04, K-09, startup): deletes rows with no attachment_refs created before [cutoff].
     * The cutoff spares attachments still being ingested that no draft references yet.
     * Returns the deleted ids so AttachmentFiles can remove the files.
     */
    @Query("SELECT id FROM attachments WHERE createdAt < :cutoff AND id NOT IN (SELECT attachmentId FROM attachment_refs)")
    suspend fun orphans(cutoff: Long): List<String>

    @Query("DELETE FROM attachments WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}
