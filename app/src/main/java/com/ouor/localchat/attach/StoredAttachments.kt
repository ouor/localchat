package com.ouor.localchat.attach

import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.db.dao.AttachmentDao
import com.ouor.localchat.storage.db.toEntity
import com.ouor.localchat.storage.db.toModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** What a request sends for one attachment: extracted [text] (documents) or a downscaled [imageDataUrl]. */
data class AttachmentPayload(val attachment: Attachment, val text: String? = null, val imageDataUrl: String? = null)

/**
 * Stored attachments outside user intake (AR F-04): request payloads and attachments the app makes
 * itself (tool artifacts, prefetched page text). Rows and files are written here, never in chat.
 */
@Singleton
class StoredAttachments @Inject constructor(
    private val dao: AttachmentDao,
    private val files: AttachmentFiles,
    private val ingest: AttachmentIngest,
    private val clock: Clock,
) {
    /** null for an unknown id. Derived files are made on demand (e.g. after a restore). */
    suspend fun payload(id: String): AttachmentPayload? {
        val a = dao.get(id)?.toModel() ?: return null
        if (!a.hasOriginal || a.state != PrepState.Ready) return AttachmentPayload(a)
        return withContext(Dispatchers.IO) {
            val derived = if (a.kind == AttachmentKind.IMAGE) files.requestImage(id) else files.text(id)
            if (!derived.isFile) ingest.prepareStored(a)
            when {
                !derived.isFile -> AttachmentPayload(a)
                a.kind == AttachmentKind.IMAGE ->
                    AttachmentPayload(a, imageDataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(derived.readBytes()))
                else -> AttachmentPayload(a, text = derived.readText())
            }
        }
    }

    /** B-08: a tool artifact as a READY ARTIFACT attachment; returns its id. */
    suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String =
        save(Attachment(Ids.new(), name, mime, bytes.size.toLong(), AttachmentKind.ARTIFACT, PrepState.Ready, hasOriginal = true, createdAt = clock.now()), bytes, text = null)

    /** S-03: page text as a READY DOCUMENT; the original and {id}.txt are the same text. */
    suspend fun saveText(name: String, text: String): String {
        val bytes = text.encodeToByteArray()
        return save(Attachment(Ids.new(), name, "text/plain", bytes.size.toLong(), AttachmentKind.DOCUMENT, PrepState.Ready, hasOriginal = true, createdAt = clock.now()), bytes, text)
    }

    private suspend fun save(a: Attachment, bytes: ByteArray, text: String?): String = withContext(Dispatchers.IO) {
        files.original(a.id).writeBytes(bytes)
        text?.let { files.text(a.id).writeText(it) }
        dao.upsert(a.toEntity())
        a.id
    }
}
