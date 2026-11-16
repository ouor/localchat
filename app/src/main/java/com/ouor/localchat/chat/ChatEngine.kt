package com.ouor.localchat.chat

import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunState
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelRef
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts, stops and steers replies (public surface, architecture 5, 10.2).
 * Starting calls suspend until the nodes are saved and the run is registered; the run itself
 * continues in the application scope, independent of the caller. One run per session.
 */
@Singleton
class ChatEngine @Inject constructor() {

    /** Active runs by session id. */
    val runs: StateFlow<Map<String, RunState>> get() = TODO("track B")

    /** Sends [draft]; creates the session when [sessionId] is null. Returns the session id. */
    suspend fun send(sessionId: String?, draft: Draft, options: GenOptions, model: ModelRef): String = TODO("track B")

    /** M-04: new sibling reply under the same user message. */
    suspend fun regenerate(assistantId: String, model: ModelRef): Unit = TODO("track B")

    /** M-05: new sibling question plus a new reply. */
    suspend fun editAndResend(userId: String, text: String, attachmentIds: List<String>, model: ModelRef): Unit =
        TODO("track B")

    /** M-03: continues the same reply (only when MessageTree.canContinue). */
    suspend fun continueReply(assistantId: String): Unit = TODO("track B")

    /** M-02: cancels the session's run; partial content is saved as STOPPED. */
    fun stop(sessionId: String): Unit = TODO("track B")

    /** Cancels every run and waits until each has saved (used before restore). */
    suspend fun stopAll(): Unit = TODO("track B")

    /** Answers the current pause of the session's run. */
    fun resolve(sessionId: String, resolution: Resolution): Unit = TODO("track B")

    /** A-07 "summarize now". */
    suspend fun compactNow(sessionId: String): Unit = TODO("track B")

    /** Names of the tools a request would expose (same rule as the loop, P-05). */
    fun exposurePreview(caps: ModelCapabilities, search: Boolean, browser: Boolean): List<String> = TODO("track B")
}
