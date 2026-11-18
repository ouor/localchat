package com.ouor.localchat.storage.db

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Summary
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.storage.db.entities.AttachmentEntity
import com.ouor.localchat.storage.db.entities.DraftEntity
import com.ouor.localchat.storage.db.entities.MessageEntity
import com.ouor.localchat.storage.db.entities.SessionEntity
import com.ouor.localchat.storage.db.entities.SummaryEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

// Entities never leave storage.db: everything above sees core models only.

private val stringList = ListSerializer(String.serializer())

fun SessionEntity.toModel() = Session(
    id = id,
    title = title,
    titleSource = TitleSource.valueOf(titleSource),
    createdAt = createdAt,
    updatedAt = updatedAt,
    rootSelectedId = rootSelectedId,
    lastModel = if (lastEndpointId != null && lastModelId != null) {
        ModelRef(lastEndpointId, lastModelId, lastModelName ?: lastModelId)
    } else {
        null
    },
    aiTabUrls = AppJson.decodeFromString(stringList, aiTabUrlsJson),
)

fun Session.toEntity() = SessionEntity(
    id = id,
    title = title,
    titleSource = titleSource.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    rootSelectedId = rootSelectedId,
    lastEndpointId = lastModel?.endpointId,
    lastModelId = lastModel?.modelId,
    lastModelName = lastModel?.displayName,
    aiTabUrlsJson = AppJson.encodeToString(stringList, aiTabUrls),
)

fun MessageEntity.toModel() = MessageNode(
    id = id,
    sessionId = sessionId,
    parentId = parentId,
    selectedChildId = selectedChildId,
    role = Role.valueOf(role),
    createdAt = createdAt,
    body = AppJson.decodeFromString(MessageBody.serializer(), bodyJson),
)

fun MessageNode.toEntity() = MessageEntity(
    id = id,
    sessionId = sessionId,
    parentId = parentId,
    selectedChildId = selectedChildId,
    role = role.name,
    createdAt = createdAt,
    status = (body as? MessageBody.Assistant)?.status?.name,
    bodyJson = AppJson.encodeToString(MessageBody.serializer(), body),
)

/** Every attachment a message keeps alive: user files and tool artifacts (page pictures, approval previews, PDFs). */
fun MessageNode.attachmentIds(): List<String> = when (val b = body) {
    is MessageBody.User -> b.attachmentIds
    is MessageBody.Assistant -> b.blocks.filterIsInstance<ToolResultBlock>()
        .flatMap { it.artifactIds + listOfNotNull(it.imageForModelId) }
}.distinct()

fun SummaryEntity.toModel() = Summary(id, sessionId, coversUpToId, text, createdAt)

fun Summary.toEntity() = SummaryEntity(id, sessionId, coversUpToId, text, createdAt)

fun DraftEntity.toModel() = Draft(key, text, AppJson.decodeFromString(stringList, attachmentIdsJson), updatedAt)

fun Draft.toEntity() = DraftEntity(key, text, AppJson.encodeToString(stringList, attachmentIds), updatedAt)

fun AttachmentEntity.toModel() = Attachment(
    id = id,
    name = name,
    mime = mime,
    size = size,
    kind = AttachmentKind.valueOf(kind),
    state = AppJson.decodeFromString(PrepState.serializer(), stateJson),
    hasOriginal = hasOriginal,
    sha256 = sha256,
    width = width,
    height = height,
    createdAt = createdAt,
)

fun Attachment.toEntity() = AttachmentEntity(
    id = id,
    name = name,
    mime = mime,
    size = size,
    kind = kind.name,
    stateJson = AppJson.encodeToString(PrepState.serializer(), state),
    hasOriginal = hasOriginal,
    sha256 = sha256,
    width = width,
    height = height,
    createdAt = createdAt,
)

/** Escapes LIKE wildcards for ChatDao.observeSessions (ESCAPE '\'). */
fun likeEscape(query: String): String =
    query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
