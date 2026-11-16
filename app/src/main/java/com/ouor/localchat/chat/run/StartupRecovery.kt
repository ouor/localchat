package com.ouor.localchat.chat.run

import com.ouor.localchat.chat.ChatStore
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.MessageBody
import kotlinx.coroutines.CancellationException
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
            // One bad row must not stop recovery, or the app would crash on every start (AN 3).
            for (node in guarded { store.unfinishedAssistants() }.orEmpty()) {
                val body = node.body as? MessageBody.Assistant ?: continue
                guarded { store.saveNode(node.copy(body = StreamCollector.close(body, inFlightCallId = null, now = now, unknownOutcome = true))) }
            }
            // Nothing from the previous process is still being ingested.
            guarded { store.collectAttachmentGarbage(cutoff = now) }
        } finally {
            done.complete(Unit)
        }
    }

    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("StartupRecovery: ${e.message}") // shortcut: stderr only, move to a logger when the app has one
        null
    }
}
