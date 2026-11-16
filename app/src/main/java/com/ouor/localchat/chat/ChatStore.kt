package com.ouor.localchat.chat

import com.ouor.localchat.core.Selection
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Summary

/**
 * Persistence the engine and loop need. Implemented by [SessionRepository] (Room);
 * JVM tests use an in-memory fake. Summaries live here too (no separate SummaryRepository).
 */
interface ChatStore {
    suspend fun session(id: String): Session?
    suspend fun nodes(sessionId: String): List<MessageNode>
    suspend fun node(id: String): MessageNode?
    suspend fun summaries(sessionId: String): List<Summary>

    /** Throws on failure: the caller (send) reports it. */
    suspend fun createSession(session: Session)

    /** Throws on failure. Inserts [nodes] and moves [selection]; bumps Session.updatedAt. */
    suspend fun insertNodes(sessionId: String, nodes: List<MessageNode>, selection: Selection)

    /** Never throws: retried, then queued (E-05 saveHealth). Latest write per node wins. */
    suspend fun saveNode(node: MessageNode)

    suspend fun updateSession(id: String, transform: (Session) -> Session)
    suspend fun addSummary(summary: Summary)

    /** PENDING/STREAMING replies left by a killed process. */
    suspend fun unfinishedAssistants(): List<MessageNode>
    suspend fun deleteDraft(key: String)

    /** Deletes attachment rows nothing references, created before [cutoff]. */
    suspend fun collectAttachmentGarbage(cutoff: Long)
}
