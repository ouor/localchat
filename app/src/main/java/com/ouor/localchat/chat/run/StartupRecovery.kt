package com.ouor.localchat.chat.run

import com.ouor.localchat.chat.ChatStore
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.MessageBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Architecture 5.6: after a process death, replies left PENDING/STREAMING become STOPPED (M-01),
 * every tool call without a result gets the "Interrupted" result, and unreferenced attachment
 * rows are collected. Runs once from App.onCreate.
 */
@Singleton
class StartupRecovery @Inject constructor(
    private val store: ChatStore,
    private val registry: RunRegistry,
    private val clock: Clock,
) {
    suspend fun run() {
        val now = clock.now()
        // shortcut: a reply sent in the first instant after launch (inserted, not yet registered) could be
        // stopped here; gate sending on recovery if that ever shows up.
        val active = registry.runs.value.values.map { it.assistantId }.toSet()
        for (node in store.unfinishedAssistants().filter { it.id !in active }) {
            val body = node.body as? MessageBody.Assistant ?: continue
            store.saveNode(node.copy(body = StreamCollector.close(body, inFlightCallId = null, now = now, unknownOutcome = true)))
        }
        // Nothing from the previous process is still being ingested.
        store.collectAttachmentGarbage(cutoff = now)
    }
}
