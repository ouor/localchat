package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.BackupFormat
import com.ouor.localchat.backup.format.Manifest
import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.storage.AttachmentFiles
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/** Result of the validating first pass (K-04 step 1). [keys] is non-null only when the password opened secrets.enc. */
internal data class Inspection(val contents: BackupContents, val conflicts: List<String>, val keys: Map<String, ApiKey>?)

/**
 * Export, inspect and restore on plain streams so the whole pipeline runs in JVM tests;
 * BackupService adds Uris, Flows and the chat/settings side effects.
 */
internal class BackupJobs(
    private val store: BackupStore,
    private val files: AttachmentFiles,
    private val clock: Clock,
    private val appVersion: String,
) {
    suspend fun summary(scope: BackupScope): BackupSummary {
        val ids = sessionIds(scope)
        val atts = store.attachmentsFor(ids)
        return BackupSummary(ids.size, atts.size, atts.sumOf { it.size })
    }

    /** Writes the whole zip to [out]. [settings] is written for full backups only. */
    suspend fun export(scope: BackupScope, out: OutputStream, password: CharArray?, settings: AppSettings, onProgress: (Progress) -> Unit) {
        val ids = sessionIds(scope)
        // A row whose original is gone (never ingested, or disk cleanup) is exported as name-only.
        val attachments = store.attachmentsFor(ids).map { if (it.hasOriginal && !files.original(it.id).isFile) it.copy(hasOriginal = false) else it }
        val allowed = attachments.map { it.id }.toSet()
        val full = scope is BackupScope.Full
        val secrets = password?.let {
            val keys = store.secrets().mapValues { (_, k) -> k.value }
            val plain = AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), keys).encodeToByteArray()
            try {
                SecretsCipher.encrypt(plain, password)
            } finally {
                plain.fill(0)
            }
        }
        val totalFiles = attachments.filter { it.hasOriginal }.sumOf { it.size }
        var done = 0L
        var json = 0L
        val manifest = Manifest(
            appVersion = appVersion,
            scope = if (full) Manifest.SCOPE_FULL else Manifest.SCOPE_SESSION,
            createdAt = Instant.ofEpochMilli(clock.now()).toString(),
            containsSecrets = secrets != null,
            counts = Manifest.Counts(ids.size, attachments.size, totalFiles),
        )
        ZipExporter(out) { n ->
            done += n
            onProgress(Progress(done, totalFiles + json))
        }.use { zip ->
            fun write(path: String, text: String) {
                json += text.length
                zip.json(path, text)
            }
            write(BackupFormat.MANIFEST, AppJson.encodeToString(Manifest.serializer(), manifest))
            if (full) {
                write(BackupFormat.SETTINGS, AppJson.encodeToString(AppSettings.serializer(), settings))
                write(BackupFormat.PROVIDERS, AppJson.encodeToString(com.ouor.localchat.backup.format.ProvidersFile.serializer(), store.providers()))
            }
            secrets?.let { write(BackupFormat.SECRETS, AppJson.encodeToString(com.ouor.localchat.backup.format.SecretsEnvelope.serializer(), it)) }
            write(BackupFormat.ATTACHMENTS, AppJson.encodeToString(ListSerializer(Attachment.serializer()), attachments))
            for (id in ids) {
                // Deleted after the snapshot: fail rather than write a backup that cannot be restored.
                val file = store.sessionFile(id) ?: throw IllegalStateException("session deleted during export")
                write(BackupFormat.sessionPath(id), AppJson.encodeToString(SessionFile.serializer(), SessionRemap.keepAttachments(file, allowed)))
            }
            attachments.filter { it.hasOriginal }.forEach { zip.file(BackupFormat.filePath(it), files.original(it.id)) }
        }
    }

    /**
     * Validates everything and copies originals into filesDir/attachments under their ids, without
     * touching any table (K-04 step 1). Copies made by a failed pass are deleted again.
     */
    suspend fun inspect(open: () -> InputStream, password: CharArray?): Inspection {
        val created = mutableListOf<String>()
        val contents = try {
            open().use { input ->
                ZipImporter.read(input, object : ImportSink {
                    override fun file(attachment: Attachment, data: InputStream) {
                        val target = files.original(attachment.id)
                        if (target.exists()) return
                        created += attachment.id
                        target.outputStream().use { data.copyTo(it) }
                    }
                })
            }
        } catch (e: Exception) {
            created.forEach(files::delete)
            throw e
        }
        val keys = contents.secrets?.takeIf { password != null }?.let { env ->
            val plain = SecretsCipher.decrypt(env, password!!)
            try {
                ZipImporter.decode(MapSerializer(String.serializer(), String.serializer()), AppJson.parseToJsonElement(plain.decodeToString()))
                    .mapValues { ApiKey(it.value) }
            } finally {
                plain.fill(0)
            }
        }
        val conflicts = if (contents.kind == RestorePlan.Kind.SESSION) store.existingSessionIds(contents.sessionIds).toList() else emptyList()
        return Inspection(contents, conflicts, keys)
    }

    /**
     * K-04 step 2 / K-05 / K-08: second pass inside one store transaction. Returns the settings to
     * apply for a full restore (step 3 is the caller's).
     */
    suspend fun restore(open: () -> InputStream, inspection: Inspection, choice: RestoreChoice, copySuffix: String): AppSettings? {
        val c = inspection.contents
        fun secondPass(insert: (SessionFile) -> Unit, map: (SessionFile) -> SessionFile = { it }) {
            open().use { ZipImporter.read(it, object : ImportSink { override fun session(file: SessionFile) = insert(map(file)) }) }
        }
        when (c.kind) {
            RestorePlan.Kind.FULL -> {
                require(choice == RestoreChoice.REPLACE_ALL) { "full backups only replace all" }
                val keys = inspection.keys.orEmpty()
                val providers = c.providers!!.let { p -> p.copy(endpoints = p.endpoints.map { it.copy(hasKey = it.id in keys) }) }
                store.replaceAll(providers, c.attachments) { insert -> secondPass(insert) }
                store.replaceSecrets(keys)
                return c.settings
            }
            RestorePlan.Kind.SESSION -> {
                val map: (SessionFile) -> SessionFile = when (choice) {
                    RestoreChoice.COPY -> { f -> SessionRemap.asCopy(f, copySuffix) }
                    RestoreChoice.OVERWRITE -> { f -> f }
                    RestoreChoice.ADD -> { f -> require(f.session.id !in inspection.conflicts) { "conflict needs OVERWRITE or COPY" }; f }
                    RestoreChoice.REPLACE_ALL -> throw IllegalArgumentException("session backups never replace all")
                }
                store.addSessions(c.attachments) { insert -> secondPass(insert, map) }
            }
            RestorePlan.Kind.DEEPSEEK -> {
                require(choice == RestoreChoice.ADD) { "DeepSeek imports only add" }
                store.addSessions(c.attachments) { insert -> secondPass(insert) }
            }
        }
        return null
    }

    private suspend fun sessionIds(scope: BackupScope): List<String> = when (scope) {
        BackupScope.Full -> store.sessionIds()
        is BackupScope.Session -> listOf(scope.sessionId)
    }
}
