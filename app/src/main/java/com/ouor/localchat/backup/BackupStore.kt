package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.ProvidersFile
import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.Attachment

/**
 * The database side of backup and restore: the one seam to track B's Room tables and track A's
 * SecretStore, implemented next to them (see track-d.md "track B"). Writes are blocking on purpose:
 * they run inside one Room transaction while the zip is read, so they must not suspend.
 */
interface BackupStore {
    /** Session ids, newest first (the K-01 snapshot). */
    suspend fun sessionIds(): List<String>

    /** Session + all messages + summaries + draft; null once deleted. */
    suspend fun sessionFile(sessionId: String): SessionFile?

    /** Attachment rows referenced (attachment_refs) by these sessions' messages and drafts. */
    suspend fun attachmentsFor(sessionIds: List<String>): List<Attachment>

    suspend fun providers(): ProvidersFile

    /** Plaintext keys by endpoint id. */
    suspend fun secrets(): Map<String, ApiKey>

    suspend fun existingSessionIds(ids: Collection<String>): Set<String>

    /**
     * K-04 step 2 in ONE transaction: delete every session, message, summary, draft, endpoint and model,
     * insert [providers] and upsert [attachments], then call [sessions] which feeds each session to the
     * insert callback (it reads the zip again). Any exception rolls everything back.
     */
    fun replaceAll(providers: ProvidersFile, attachments: List<Attachment>, sessions: ((SessionFile) -> Unit) -> Unit)

    /**
     * K-05 / K-08 in ONE transaction: upsert [attachments]; for each session from [sessions], delete the
     * session with that id first (overwrite, CASCADE) and insert it with its messages, summaries, draft
     * and attachment_refs. Nothing else changes.
     */
    fun addSessions(attachments: List<Attachment>, sessions: ((SessionFile) -> Unit) -> Unit)

    /** K-04 step 3: replaces every stored key. */
    suspend fun replaceSecrets(keys: Map<String, ApiKey>)
}
