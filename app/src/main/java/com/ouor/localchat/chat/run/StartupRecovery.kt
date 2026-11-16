package com.ouor.localchat.chat.run

import com.ouor.localchat.chat.ChatStore
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.MessageBody
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Architecture 5.6: after a process death, replies left PENDING/STREAMING become STOPPED (M-01),
 * every tool call without a result gets the "Interrupted" result, and unreferenced attachment
 * rows are collected. Runs once from App.onCreate; ChatEngine waits for it before starting runs.
 */
@Singleton
class StartupRecovery @Inject constructor(
    private val store: ChatStore,
    private val clock: Clock,
) {
    private val done = CompletableDeferred<Unit>()

    /** Returns once [run] has finished (also when it failed), so no new reply can be caught by it. */
    suspend fun awaitDone() = done.await()

    suspend fun run() {
        try {
            val now = clock.now()
            for (node in store.unfinishedAssistants()) {
                val body = node.body as? MessageBody.Assistant ?: continue
                store.saveNode(node.copy(body = StreamCollector.close(body, inFlightCallId = null, now = now, unknownOutcome = true)))
            }
            // Nothing from the previous process is still being ingested.
            store.collectAttachmentGarbage(cutoff = now)
        } finally {
            done.complete(Unit)
        }
    }
}
