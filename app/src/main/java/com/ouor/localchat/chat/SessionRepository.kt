package com.ouor.localchat.chat

import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Summary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Sessions and message trees (public surface, architecture 8, 10.2). Returns core models only. */
@Singleton
class SessionRepository @Inject constructor() {

    /** Newest first; [query] filters titles by substring (C-08), empty = all. */
    fun observeSessions(query: String = ""): Flow<List<Session>> = TODO("track B")

    /** null once deleted. Needed for the title and Session.rootSelectedId (visible path). */
    fun observeSession(sessionId: String): Flow<Session?> = TODO("track B")

    /** All nodes of every branch; compute the visible path with core.MessageTree.path. */
    fun observeMessages(sessionId: String): Flow<List<MessageNode>> = TODO("track B")

    fun observeSummaries(sessionId: String): Flow<List<Summary>> = TODO("track B")

    /** C-03; also sets TitleSource.USER. */
    suspend fun rename(sessionId: String, title: String): Unit = TODO("track B")

    /** C-04: deletes the session and its unreferenced attachments. */
    suspend fun delete(sessionId: String): Unit = TODO("track B")

    /** K-09: every session, draft and chat attachment. Providers, keys and settings stay. */
    suspend fun deleteAll(): Unit = TODO("track B")

    /** M-06: select version [messageId]; ignored when it is not on the visible path. */
    suspend fun selectVersion(messageId: String): Unit = TODO("track B")

    /** E-05 banner state. */
    val saveHealth: StateFlow<SaveHealth> get() = TODO("track B")

    suspend fun retryPendingWrites(): Unit = TODO("track B")
}

enum class SaveHealth { OK, FAILING }
