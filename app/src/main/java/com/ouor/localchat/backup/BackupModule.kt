package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.ProvidersFile
import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.Attachment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    // TODO(track B, Phase 2): replace with the Room-backed BackupStore (track-d.md lists the DAO calls).
    @Provides
    fun backupStore(): BackupStore = object : BackupStore {
        override suspend fun sessionIds(): List<String> = TODO("track B")
        override suspend fun sessionFile(sessionId: String): SessionFile? = TODO("track B")
        override suspend fun attachmentsFor(sessionIds: List<String>): List<Attachment> = TODO("track B")
        override suspend fun providers(): ProvidersFile = TODO("track A/B")
        override suspend fun secrets(): Map<String, ApiKey> = TODO("track A")
        override suspend fun existingSessionIds(ids: Collection<String>): Set<String> = TODO("track B")
        override fun replaceAll(providers: ProvidersFile, attachments: List<Attachment>, sessions: ((SessionFile) -> Unit) -> Unit) = TODO("track B")
        override fun addSessions(attachments: List<Attachment>, sessions: ((SessionFile) -> Unit) -> Unit) = TODO("track B")
        override suspend fun replaceSecrets(keys: Map<String, ApiKey>) = TODO("track A")
    }
}
