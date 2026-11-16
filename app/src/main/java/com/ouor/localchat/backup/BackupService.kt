package com.ouor.localchat.backup

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Backup, restore and DeepSeek import (public surface, architecture 9, 10.2). */
@Singleton
class BackupService @Inject constructor() {

    /** K-01 range summary shown before export. */
    suspend fun summary(scope: BackupScope): BackupSummary = TODO("track D")

    /** Writes the ZIP to [uri]; [password] non-null includes encrypted keys (K-03) and is zeroed after use. */
    fun export(scope: BackupScope, uri: Uri, password: CharArray?): Flow<Progress> = TODO("track D")

    /** Validates without touching data. Throws [BackupException]; WRONG_PASSWORD can be retried or skipped with null. */
    suspend fun inspect(uri: Uri, password: CharArray?): RestorePlan = TODO("track D")

    /** Applies [plan]; a failure leaves existing data unchanged (K-04). */
    fun restore(plan: RestorePlan, choice: RestoreChoice): Flow<Progress> = TODO("track D")
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
