package com.ouor.localchat.backup.format

import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.Endpoint
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Summary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** ZIP layout of a localchat backup (architecture 9.1). */
object BackupFormat {
    const val FORMAT = "localchat-backup"

    /** Bump only for a removed, renamed or re-meant field (9.2), and add a migration + golden file. */
    const val CURRENT = 1

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val PROVIDERS = "providers.json"
    const val SECRETS = "secrets.enc"
    const val ATTACHMENTS = "attachments.json"
    const val SESSIONS_DIR = "sessions/"
    const val FILES_DIR = "attachments/"

    fun sessionPath(id: String) = "$SESSIONS_DIR$id.json"

    /** The name part is for people unzipping by hand; restore only uses the id. */
    fun filePath(a: Attachment) = "$FILES_DIR${a.id}/${safeName(a.name)}"

    /** `sessions/{id}.json` -> id, `attachments/{id}/...` -> id. */
    fun sessionIdOf(path: String): String? = Regex("""sessions/([^/]+)\.json""").matchEntire(path)?.groupValues?.get(1)

    fun fileIdOf(path: String): String? = Regex("""attachments/([^/]+)/[^/]+""").matchEntire(path)?.groupValues?.get(1)

    private fun safeName(name: String): String =
        name.replace(Regex("""[/\\:*?"<>|\u0000-\u001f]"""), "_").take(120).ifBlank { "file" }
}

@Serializable
data class Manifest(
    val format: String = BackupFormat.FORMAT,
    val formatVersion: Int = BackupFormat.CURRENT,
    val appVersion: String,
    val scope: String,
    /** ISO-8601 UTC. */
    val createdAt: String,
    val containsSecrets: Boolean,
    val counts: Counts,
) {
    @Serializable
    data class Counts(val sessions: Int, val attachments: Int, val attachmentBytes: Long)

    companion object {
        const val SCOPE_FULL = "full"
        const val SCOPE_SESSION = "session"
    }
}

/** sessions/{id}.json: the domain serialization as is. */
@Serializable
data class SessionFile(
    val session: Session,
    val messages: List<MessageNode>,
    val summaries: List<Summary> = emptyList(),
    val draft: Draft? = null,
)

/** providers.json (full backups only). Keys are never here. */
@Serializable
data class ProvidersFile(
    val endpoints: List<Endpoint>,
    val models: List<ModelRecord>,
)

/** One `models` row (architecture 8.1) as stored; capabilities are resolved again after restore. */
@Serializable
data class ModelRecord(
    val endpointId: String,
    val modelId: String,
    /** The server's /models entry, kept verbatim. */
    val server: JsonElement? = null,
    val inServerList: Boolean,
    val manual: Boolean,
    val displayNameUser: String? = null,
    val hidden: Boolean = false,
    val overrides: ModelOverrides? = null,
    val lastUsedAt: Long? = null,
)

/** secrets.enc (SEC-4). [ciphertext] decrypts to JSON {endpointId: apiKey}. */
@Serializable
data class SecretsEnvelope(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val cipher: String,
    val iv: String,
    val aad: String,
    val ciphertext: String,
)
