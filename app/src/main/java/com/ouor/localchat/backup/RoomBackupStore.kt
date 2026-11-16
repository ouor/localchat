package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.ModelRecord
import com.ouor.localchat.backup.format.ProvidersFile
import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.Endpoint
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.PresetId
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.storage.db.AppDatabase
import com.ouor.localchat.storage.db.attachmentIds
import com.ouor.localchat.storage.db.dao.BackupDao
import com.ouor.localchat.storage.db.dao.ChatDao
import com.ouor.localchat.storage.db.entities.AttachmentRefEntity
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import com.ouor.localchat.storage.db.toEntity
import com.ouor.localchat.storage.db.toModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** [BackupStore] on Room and SecretStore (track-d.md 4.3 query table). */
class RoomBackupStore @Inject constructor(
    private val db: AppDatabase,
    private val dao: BackupDao,
    private val chat: ChatDao,
    private val secrets: SecretStore,
) : BackupStore {

    override suspend fun sessionIds(): List<String> = dao.sessionIds()

    override suspend fun sessionFile(sessionId: String): SessionFile? {
        val session = chat.session(sessionId)?.toModel() ?: return null
        return SessionFile(
            session = session,
            messages = chat.messages(sessionId).map { it.toModel() },
            summaries = chat.summaries(sessionId).map { it.toModel() },
            draft = chat.draft(sessionId)?.toModel(),
        )
    }

    override suspend fun attachmentsFor(sessionIds: List<String>): List<Attachment> =
        sessionIds.chunked(SQL_CHUNK).flatMap { dao.attachmentsFor(it) }.distinctBy { it.id }.map { it.toModel() }

    override suspend fun providers(): ProvidersFile {
        val withKey = secrets.endpointIds.first()
        return ProvidersFile(
            endpoints = dao.endpoints().map { it.toModel(it.id in withKey) },
            models = dao.models().map { it.toRecord() },
        )
    }

    override suspend fun secrets(): Map<String, ApiKey> = secrets.all()

    override suspend fun existingSessionIds(ids: Collection<String>): Set<String> =
        ids.toList().chunked(SQL_CHUNK).flatMap { dao.existingSessionIds(it) }.toSet()

    override fun replaceAll(providers: ProvidersFile, attachments: List<Attachment>, sessions: ((SessionFile) -> Unit) -> Unit) {
        db.runInTransaction {
            dao.deleteAllRefs()
            dao.deleteAllDrafts()
            dao.deleteAllSessions()
            dao.deleteAllEndpoints()
            dao.insertEndpoints(providers.endpoints.mapIndexed { i, e -> e.toEntity(i) })
            dao.insertModels(providers.models.map { it.toEntity() })
            dao.upsertAttachments(attachments.map { it.toEntity() })
            sessions(::insert)
        }
    }

    override fun addSessions(attachments: List<Attachment>, sessions: ((SessionFile) -> Unit) -> Unit) {
        db.runInTransaction {
            dao.upsertAttachments(attachments.map { it.toEntity() })
            sessions { file ->
                val id = file.session.id
                dao.deleteMessageRefsOf(id)
                dao.deleteDraftRefs(id)
                dao.deleteDraft(id)
                dao.deleteSession(id)
                insert(file)
            }
        }
    }

    override suspend fun replaceSecrets(keys: Map<String, ApiKey>) = secrets.replaceAll(keys)

    private fun insert(file: SessionFile) {
        dao.insertSession(file.session.toEntity())
        dao.insertMessages(file.messages.map { it.toEntity() })
        dao.insertSummaries(file.summaries.map { it.toEntity() })
        dao.upsertRefs(
            file.messages.flatMap { m -> m.attachmentIds().map { AttachmentRefEntity(it, AttachmentRefEntity.MESSAGE, m.id) } },
        )
        file.draft?.let { d ->
            dao.upsertDraft(d.toEntity())
            dao.upsertRefs(d.attachmentIds.distinct().map { AttachmentRefEntity(it, AttachmentRefEntity.DRAFT, d.key) })
        }
    }

    private companion object {
        /** SQLite host-parameter limit headroom. */
        const val SQL_CHUNK = 500
    }
}

private fun EndpointEntity.toModel(hasKey: Boolean) = Endpoint(
    id = id, name = name, baseUrl = baseUrl,
    preset = preset?.let { p -> PresetId.entries.firstOrNull { it.name == p } },
    enabled = enabled, maxRetries = maxRetries, allowHttp = allowHttp, hasKey = hasKey,
)

/** [index] keeps the backup's order; the original creation times are not in the format. */
private fun Endpoint.toEntity(index: Int) = EndpointEntity(
    id = id, name = name, baseUrl = baseUrl, preset = preset?.name, enabled = enabled,
    maxRetries = maxRetries, allowHttp = allowHttp, sortOrder = index, createdAt = index.toLong(),
)

private fun ModelEntity.toRecord(): ModelRecord {
    val overrides = capsUserJson?.let { runCatching { AppJson.decodeFromString(ModelOverrides.serializer(), it) }.getOrNull() }
    return ModelRecord(
        endpointId = endpointId,
        modelId = modelId,
        server = serverJson?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() },
        inServerList = inServerList,
        manual = manual,
        displayNameUser = overrides?.displayName,
        hidden = hidden,
        overrides = overrides,
        lastUsedAt = lastUsedAt,
    )
}

private fun ModelRecord.toEntity(): ModelEntity {
    // Older backups may carry the name only in displayNameUser.
    val o = (overrides ?: ModelOverrides()).let { if (it.displayName == null && displayNameUser != null) it.copy(displayName = displayNameUser) else it }
    return ModelEntity(
        endpointId = endpointId,
        modelId = modelId,
        serverJson = server?.toString(),
        inServerList = inServerList,
        manual = manual,
        hidden = hidden,
        capsUserJson = o.takeIf { it != ModelOverrides() }?.let { AppJson.encodeToString(ModelOverrides.serializer(), it) },
        lastUsedAt = lastUsedAt,
    )
}
