package com.ouor.localchat.storage.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Plain data classes on purpose (architecture 8.4): no @Parcelize, no interface inheritance.

@Entity(
    tableName = "sessions",
    indices = [Index("updatedAt"), Index("title")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val titleSource: String,
    val createdAt: Long,
    val updatedAt: Long,
    val rootSelectedId: String?,
    val lastEndpointId: String?,
    val lastModelId: String?,
    val lastModelName: String?,
    val aiTabUrlsJson: String,
)

/** [bodyJson] = MessageBody in AppJson. Only tree-walking values are columns. */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sessionId", "createdAt"), Index("sessionId", "parentId"), Index("status")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val parentId: String?,
    val selectedChildId: String?,
    val role: String,
    val createdAt: Long,
    /** ReplyStatus for assistant messages, null for user messages. */
    val status: String?,
    val bodyJson: String,
)

@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val kind: String,
    /** PrepState in AppJson. */
    val stateJson: String,
    val hasOriginal: Boolean,
    val sha256: String?,
    val width: Int?,
    val height: Int?,
    val createdAt: Long,
)

/** Who references an attachment. An attachment with no rows here is garbage (C-04, K-09). */
@Entity(
    tableName = "attachment_refs",
    primaryKeys = ["attachmentId", "ownerType", "ownerId"],
    indices = [Index("attachmentId"), Index("ownerType", "ownerId")],
)
data class AttachmentRefEntity(
    val attachmentId: String,
    val ownerType: String,
    val ownerId: String,
) {
    companion object {
        const val MESSAGE = "MESSAGE"
        const val DRAFT = "DRAFT"
    }
}

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val key: String,
    val text: String,
    val attachmentIdsJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "summaries",
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sessionId")],
)
data class SummaryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val coversUpToId: String,
    val text: String,
    val createdAt: Long,
)
