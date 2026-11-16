package com.ouor.localchat.backup

import com.ouor.localchat.backup.deepseek.DeepSeekImporter
import com.ouor.localchat.backup.format.BackupFormat
import com.ouor.localchat.backup.format.Manifest
import com.ouor.localchat.backup.format.ProvidersFile
import com.ouor.localchat.backup.format.SecretsEnvelope
import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.backup.format.migrate
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.Attachment
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** What a backup holds besides sessions, which are streamed to [ImportSink.session] one at a time. */
internal data class BackupContents(
    val kind: RestorePlan.Kind,
    val settings: AppSettings? = null,
    val providers: ProvidersFile? = null,
    val secrets: SecretsEnvelope? = null,
    /** Final metadata; for DeepSeek hasOriginal reflects which images were really in the zip. */
    val attachments: List<Attachment>,
    val sessionIds: List<String>,
)

internal interface ImportSink {
    fun session(file: SessionFile) {}

    /** [data] is the attachment original; read it or leave it, the importer checks sha256 either way. */
    fun file(attachment: Attachment, data: InputStream) {}
}

/**
 * One streaming pass over a backup or DeepSeek zip (architecture 9.3, 9.6). Memory holds one entry
 * at a time. Every K-07 check happens here or in [Validator]; failures throw [BackupException].
 * Commons Compress because DeepSeek zips STORE entries with data descriptors, which
 * java.util.zip.ZipInputStream rejects.
 */
internal object ZipImporter {
    /** A session JSON larger than this is treated as broken rather than risking an OOM. */
    private const val MAX_JSON_BYTES = 128L * 1024 * 1024

    fun read(input: InputStream, sink: ImportSink = object : ImportSink {}): BackupContents {
        val zip = ZipArchiveInputStream(input, "UTF-8", true, true)
        // Not a zip at all fails on the first header; later failures mean a truncated or corrupt file.
        val first = try {
            zip.nextEntry
        } catch (e: IOException) {
            throw BackupException(BackupError.UNRECOGNIZED, e)
        }
        if (first == null || first.name != BackupFormat.MANIFEST) throw BackupException(BackupError.UNRECOGNIZED)
        return try {
            val manifest = readJson(zip, first) as? JsonObject ?: throw BackupException(BackupError.UNRECOGNIZED)
            when {
                "format" in manifest -> readLocal(zip, manifest, sink)
                DeepSeekImporter.VERSION_KEY in manifest -> DeepSeekImporter.read(zip, manifest, sink)
                else -> throw BackupException(BackupError.UNRECOGNIZED)
            }
        } catch (e: IOException) {
            throw BackupException(BackupError.INCOMPLETE, e)
        }
    }

    private fun readLocal(zip: ZipArchiveInputStream, manifestJson: JsonObject, sink: ImportSink): BackupContents {
        if (manifestJson["format"]?.jsonPrimitive?.contentOrNull != BackupFormat.FORMAT) throw BackupException(BackupError.UNRECOGNIZED)
        val version = manifestJson["formatVersion"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
        when {
            version == null || version < 1 -> throw BackupException(BackupError.BAD_VERSION)
            version > BackupFormat.CURRENT -> throw BackupException(BackupError.NEWER_VERSION)
        }
        val manifest = decode(Manifest.serializer(), migrate(version, manifestJson) { manifest(it) })
        val kind = if (manifest.scope == Manifest.SCOPE_SESSION) RestorePlan.Kind.SESSION else RestorePlan.Kind.FULL

        var settings: AppSettings? = null
        var providers: ProvidersFile? = null
        var secrets: SecretsEnvelope? = null
        var attachments: Map<String, Attachment>? = null
        val sessionIds = mutableListOf<String>()
        val referenced = HashSet<String>()
        val filesSeen = HashSet<String>()

        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            if (!zip.canReadEntryData(entry)) throw BackupException(BackupError.INCOMPLETE)
            val path = entry.name
            when {
                path == BackupFormat.SETTINGS -> settings = decode(AppSettings.serializer(), migrate(version, obj(zip, entry)) { settings(it) })
                path == BackupFormat.PROVIDERS -> providers = decode(ProvidersFile.serializer(), migrate(version, obj(zip, entry)) { providers(it) })
                path == BackupFormat.SECRETS -> secrets = decode(SecretsEnvelope.serializer(), obj(zip, entry))
                path == BackupFormat.ATTACHMENTS -> {
                    val arr = readJson(zip, entry) as? JsonArray ?: throw BackupException(BackupError.INCOMPLETE)
                    val list = decode(kotlinx.serialization.builtins.ListSerializer(Attachment.serializer()), migrate(version, arr) { attachments(it) })
                    attachments = list.associateBy { it.id }
                    if (attachments.size != list.size) throw BackupException(BackupError.INCOMPLETE)
                }
                BackupFormat.sessionIdOf(path) != null -> {
                    val file = decode(SessionFile.serializer(), migrate(version, obj(zip, entry)) { session(it) })
                    if (file.session.id != BackupFormat.sessionIdOf(path) || file.session.id in sessionIds) throw BackupException(BackupError.INCOMPLETE)
                    Validator.checkTree(file)
                    referenced += Validator.attachmentIds(file)
                    sessionIds += file.session.id
                    sink.session(file)
                }
                BackupFormat.fileIdOf(path) != null -> {
                    val meta = attachments?.get(BackupFormat.fileIdOf(path)) ?: throw BackupException(BackupError.INCOMPLETE)
                    if (!filesSeen.add(meta.id)) throw BackupException(BackupError.INCOMPLETE)
                    checkedFile(zip, meta, sink)
                }
                // Entries added by a later version are ignored, like unknown fields (K-06).
            }
        }

        val metas = attachments.orEmpty()
        val complete = sessionIds.size == manifest.counts.sessions &&
            metas.size == manifest.counts.attachments &&
            metas.keys.containsAll(referenced) &&
            metas.values.filter { it.hasOriginal }.all { it.id in filesSeen } &&
            (!manifest.containsSecrets || secrets != null) &&
            (kind == RestorePlan.Kind.SESSION || providers != null)
        if (!complete) throw BackupException(BackupError.INCOMPLETE)
        return BackupContents(kind, settings, providers, secrets, metas.values.toList(), sessionIds)
    }

    /** Hands the entry to [sink], drains the rest and compares sha256 with the metadata. Returns the sha256. */
    fun checkedFile(zip: InputStream, meta: Attachment, sink: ImportSink): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val data = DigestInputStream(NonClosing(zip), digest)
        sink.file(meta, data)
        data.readAllBytesTo()
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (meta.sha256 != null && !meta.sha256.equals(actual, ignoreCase = true)) throw BackupException(BackupError.BROKEN_ATTACHMENT)
        return actual
    }

    fun readJson(zip: InputStream, entry: ZipArchiveEntry): JsonElement {
        if (entry.size > MAX_JSON_BYTES) throw BackupException(BackupError.INCOMPLETE)
        val bytes = zip.readAtMost(MAX_JSON_BYTES) ?: throw BackupException(BackupError.INCOMPLETE)
        return try {
            AppJson.parseToJsonElement(bytes.decodeToString())
        } catch (e: SerializationException) {
            throw BackupException(BackupError.INCOMPLETE, e)
        }
    }

    private fun obj(zip: InputStream, entry: ZipArchiveEntry): JsonObject =
        readJson(zip, entry) as? JsonObject ?: throw BackupException(BackupError.INCOMPLETE)

    fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T = try {
        AppJson.decodeFromJsonElement(serializer, element)
    } catch (e: SerializationException) {
        throw BackupException(BackupError.INCOMPLETE, e)
    } catch (e: IllegalArgumentException) {
        throw BackupException(BackupError.INCOMPLETE, e)
    }

    /** null when the stream holds more than [max] bytes. (readNBytes needs API 33.) */
    private fun InputStream.readAtMost(max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
    }

    private fun InputStream.readAllBytesTo() {
        val buf = ByteArray(64 * 1024)
        while (read(buf) >= 0) Unit
    }

    /** Sinks may close what they get; the zip stream must stay open for the next entry. */
    private class NonClosing(input: InputStream) : java.io.FilterInputStream(input) {
        override fun close() = Unit
    }
}
