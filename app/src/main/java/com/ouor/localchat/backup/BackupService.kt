package com.ouor.localchat.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.core.Clock
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Backup, restore and DeepSeek import (public surface, architecture 9, 10.2). */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    store: BackupStore,
    files: AttachmentFiles,
    clock: Clock,
    private val settings: SettingsStore,
    private val chat: ChatEngine,
) {
    private val jobs = BackupJobs(store, files, clock, appVersion(context))

    /** Keys decrypted by [inspect], held only until [restore] of the same plan. */
    @Volatile
    private var pending: Pair<RestorePlan, Inspection>? = null

    /** K-01 range summary shown before export. */
    suspend fun summary(scope: BackupScope): BackupSummary = withContext(Dispatchers.IO) { jobs.summary(scope) }

    /** Writes the ZIP to [uri]; [password] non-null includes encrypted keys (K-03) and is zeroed after use. */
    fun export(scope: BackupScope, uri: Uri, password: CharArray?): Flow<Progress> = callbackFlow {
        val job = launch(Dispatchers.IO) {
            try {
                val current = settings.settings.first()
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open $uri")
                out.use { jobs.export(scope, it, password, current) { p -> trySend(p) } }
                close()
            } catch (e: Throwable) {
                // Cancelled or failed: a half-written file is worse than none (architecture 9.1).
                withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                close(e.takeUnless { it is CancellationException })
            } finally {
                password?.fill('\u0000')
            }
        }
        awaitClose { job.cancel() }
    }

    /** Validates without touching data. Throws [BackupException]; WRONG_PASSWORD can be retried or skipped with null. */
    suspend fun inspect(uri: Uri, password: CharArray?): RestorePlan = withContext(Dispatchers.Default) {
        val inspection = try {
            jobs.inspect({ open(uri) }, password)
        } catch (e: IOException) {
            throw BackupException(BackupError.INCOMPLETE, e)
        } finally {
            password?.fill('\u0000')
        }
        val c = inspection.contents
        RestorePlan(
            uri = uri,
            kind = c.kind,
            sessions = c.sessionIds.size,
            attachments = c.attachments.size,
            containsSecrets = c.secrets != null,
            secretsUnlocked = inspection.keys != null,
            conflicts = inspection.conflicts,
            turnsOffBuiltInSensitive = c.turnsOffBuiltInSensitive,
        ).also { pending = it to inspection }
    }

    /** Applies [plan]; a failure leaves existing data unchanged (K-04). */
    fun restore(plan: RestorePlan, choice: RestoreChoice): Flow<Progress> = callbackFlow {
        val inspection = pending?.takeIf { it.first == plan }?.second ?: throw IllegalStateException("inspect the file first")
        val job = launch(Dispatchers.IO) {
            try {
                chat.stopAll()
                val total = size(plan.uri)
                var read = 0L
                // R by full name: ArchitectureTest reads a root-package import as the app layer.
                val restored = jobs.restore({ Counting(open(plan.uri)) { n -> read += n; trySend(Progress(read, total)) } }, inspection, choice, context.getString(com.ouor.localchat.R.string.backup_copy_suffix))
                pending = null
                // Step 3: after the tables committed. A failure here leaves chats restored, settings old.
                restored?.let { s -> settings.update { s } }
                close()
            } catch (e: Throwable) {
                close(e.takeUnless { it is CancellationException })
            }
        }
        awaitClose { job.cancel() }
    }

    private fun open(uri: Uri): InputStream = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")

    private fun size(uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L

    private class Counting(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) onRead(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onRead(it.toLong()) }
    }

    private companion object {
        fun appVersion(context: Context): String {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return "${info.versionName} (${info.longVersionCode})"
        }
    }
}

sealed interface BackupScope {
    data object Full : BackupScope
    data class Session(val sessionId: String) : BackupScope
}

data class BackupSummary(val sessions: Int, val attachments: Int, val attachmentBytes: Long)

data class Progress(val doneBytes: Long, val totalBytes: Long)

data class RestorePlan(
    val uri: Uri,
    val kind: Kind,
    val sessions: Int,
    val attachments: Int,
    val containsSecrets: Boolean,
    /** false when keys are present but no (correct) password was given: restore without keys. */
    val secretsUnlocked: Boolean,
    /** Session ids that already exist (session backups, K-05). */
    val conflicts: List<String>,
    /** FB2: the backup's settings, applied by a full restore, have the built-in sensitive-site list off. */
    val turnsOffBuiltInSensitive: Boolean = false,
) {
    enum class Kind { FULL, SESSION, DEEPSEEK }
}

enum class RestoreChoice {
    /** FULL: replace all data. */
    REPLACE_ALL,

    /** SESSION conflict: overwrite the existing session. */
    OVERWRITE,

    /** SESSION conflict: add as a copy with new ids. */
    COPY,

    /** No conflict (SESSION, DEEPSEEK): add. */
    ADD,
}

/** K-07 validation codes (architecture 9.3). ui maps each to its text. */
enum class BackupError {
    UNRECOGNIZED,
    NEWER_VERSION,
    BAD_VERSION,
    INCOMPLETE,
    BROKEN_TREE,
    BROKEN_ATTACHMENT,
    WRONG_PASSWORD,
}

class BackupException(val error: BackupError, cause: Throwable? = null) : Exception(error.name, cause)
