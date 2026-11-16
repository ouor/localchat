package com.ouor.localchat.chat

import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.chat.run.RunRegistry
import com.ouor.localchat.chat.run.RunState
import com.ouor.localchat.chat.run.StartupRecovery
import com.ouor.localchat.chat.run.TurnRunner
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.MessageTree
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.storage.SettingsStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** A start call was refused; [reason] tells ui why (spec 12.1 error codes). */
/** A-04: [labelRes] is the tool's string resource, [argSummary] e.g. the URL or query. */
data class ToolStep(val labelRes: Int, val argSummary: String)

class ChatRejected(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** The session already has a run (the send button is a stop button then). */
        RUN_ACTIVE,

        /** Endpoint or model is gone or disabled (P-08). */
        MODEL_UNAVAILABLE,

        /** Unknown message, or not allowed by MessageTree.canContinue / canRegenerate. */
        NOT_ALLOWED,

        /** Nothing to send. */
        EMPTY,
    }
}

/**
 * Starts, stops and steers replies (public surface, architecture 5, 10.2).
 * Starting calls suspend until the nodes are saved and the run is registered; the run itself
 * continues in the application scope, independent of the caller. One run per session.
 */
@Singleton
class ChatEngine @Inject constructor(
    private val store: ChatStore,
    private val registry: RunRegistry,
    private val runner: TurnRunner,
    private val recovery: StartupRecovery,
    private val models: ModelPort,
    private val tools: ToolPort,
    private val compactor: Compactor,
    private val settings: SettingsStore,
    private val clock: Clock,
) {

    /** Active runs by session id. */
    val runs: StateFlow<Map<String, RunState>> get() = registry.runs

    /** Sends [draft]; creates the session when [sessionId] is null. Returns the session id. */
    suspend fun send(sessionId: String?, draft: Draft, options: GenOptions, model: ModelRef): String = starting {
        if (draft.text.isBlank() && draft.attachmentIds.isEmpty()) throw ChatRejected(ChatRejected.Reason.EMPTY)
        if (sessionId != null) checkIdle(sessionId)
        requireModel(model)
        val now = clock.now()
        val session = sessionId?.let { store.session(it) } ?: Session(
            id = Ids.new(),
            title = TitleGenerator.firstPrompt(draft.text),
            titleSource = if (draft.text.isBlank()) TitleSource.NONE else TitleSource.FIRST_PROMPT,
            createdAt = now,
            updatedAt = now,
            rootSelectedId = null,
            lastModel = model,
        ).also { store.createSession(it) }
        val tail = MessageTree.path(session.rootSelectedId, store.nodes(session.id)).lastOrNull()
        val user = node(session.id, Role.USER, MessageBody.User(draft.text, draft.attachmentIds), now)
        val reply = pending(session.id, model, options, now + 1)
        insertAndStart(session.id, tail?.id, listOf(user, reply), model)
        store.deleteDraft(draft.key)
        session.id
    }

    /** M-04: new sibling reply under the same user message. */
    suspend fun regenerate(assistantId: String, model: ModelRef) = starting {
        val (old, body) = assistant(assistantId)
        if (!MessageTree.canRegenerate(body)) throw ChatRejected(ChatRejected.Reason.NOT_ALLOWED)
        checkIdle(old.sessionId)
        requireModel(model)
        insertAndStart(old.sessionId, old.parentId, listOf(pending(old.sessionId, model, body.options, clock.now())), model)
    }

    /** M-05: new sibling question plus a new reply. */
    suspend fun editAndResend(userId: String, text: String, attachmentIds: List<String>, model: ModelRef) = starting {
        val old = store.node(userId)?.takeIf { it.body is MessageBody.User }
            ?: throw ChatRejected(ChatRejected.Reason.NOT_ALLOWED)
        if (text.isBlank() && attachmentIds.isEmpty()) throw ChatRejected(ChatRejected.Reason.EMPTY)
        checkIdle(old.sessionId)
        requireModel(model)
        val options = store.nodes(old.sessionId).firstOrNull { it.parentId == userId && it.body is MessageBody.Assistant }
            ?.let { (it.body as MessageBody.Assistant).options } ?: settings.settings.first().lastOptions
        val now = clock.now()
        val user = node(old.sessionId, Role.USER, MessageBody.User(text, attachmentIds), now)
        insertAndStart(old.sessionId, old.parentId, listOf(user, pending(old.sessionId, model, options, now + 1)), model)
    }

    /** M-03: continues the same reply (only when MessageTree.canContinue). */
    suspend fun continueReply(assistantId: String) = starting {
        val (node, body) = assistant(assistantId)
        if (!MessageTree.canContinue(body)) throw ChatRejected(ChatRejected.Reason.NOT_ALLOWED)
        checkIdle(node.sessionId)
        body.model?.let { requireModel(it) } ?: throw ChatRejected(ChatRejected.Reason.MODEL_UNAVAILABLE)
        start(node, TurnRunner.Mode.CONTINUE)
    }

    private val startLock = Mutex()

    /**
     * Everything from the idle check to registry.start runs under one lock: two sends at once would both
     * pass the check and the loser would leave a PENDING branch selected (AN 5, SE 23).
     */
    private suspend fun <T> starting(block: suspend () -> T): T {
        recovery.awaitDone()
        return startLock.withLock { block() }
    }

    /** M-02: cancels the session's run; partial content is saved as STOPPED. */
    fun stop(sessionId: String) = registry.stop(sessionId)

    /** Cancels every run and waits until each has saved (used before restore). */
    suspend fun stopAll() = registry.stopAll()

    /** Answers the current pause of the session's run. */
    fun resolve(sessionId: String, resolution: Resolution) = registry.resolve(sessionId, resolution)

    /**
     * A-07 "summarize now" with the session's last model. Throws [ChatRejected] while a run is
     * active or the model is gone, [CompactionFailed] when the request fails.
     */
    suspend fun compactNow(sessionId: String) {
        checkIdle(sessionId)
        val session = store.session(sessionId) ?: throw ChatRejected(ChatRejected.Reason.NOT_ALLOWED)
        val model = session.lastModel?.let { models.resolve(it) } ?: throw ChatRejected(ChatRejected.Reason.MODEL_UNAVAILABLE)
        compactor.compact(sessionId, MessageTree.path(session.rootSelectedId, store.nodes(sessionId)), model)
    }

    /** Names of the tools a request would expose (same rule as the loop, P-05). */
    fun exposurePreview(caps: ModelCapabilities, search: Boolean, browser: Boolean): List<String> =
        tools.exposed(caps, search, browser).map { it.name }

    /** A-04 step row for a stored tool call: the tool's label and short argument summary; null for an unknown tool. */
    fun toolStep(block: ToolCallBlock): ToolStep? {
        val tool = tools.byName(block.name) ?: return null
        val args = runCatching { AppJson.parseToJsonElement(block.argumentsJson).jsonObject }.getOrNull()
        return ToolStep(tool.labelRes, args?.let { runCatching { tool.argSummary(it) }.getOrNull() }.orEmpty())
    }

    private suspend fun insertAndStart(sessionId: String, parentId: String?, chain: List<MessageNode>, model: ModelRef) {
        val (nodes, selection) = MessageTree.appendBranch(parentId, chain)
        store.insertNodes(sessionId, nodes, selection)
        store.updateSession(sessionId) { it.copy(lastModel = model) }
        start(nodes.last(), TurnRunner.Mode.NEW)
        models.markUsed(model)
    }

    private fun start(reply: MessageNode, mode: TurnRunner.Mode) {
        val initial = RunState(reply.sessionId, reply.id, RunPhase.Requesting, step = 0, maxSteps = 0, live = reply)
        if (!registry.start(initial) { runner.run(it, mode) }) throw ChatRejected(ChatRejected.Reason.RUN_ACTIVE)
    }

    private fun checkIdle(sessionId: String) {
        if (sessionId in registry.runs.value) throw ChatRejected(ChatRejected.Reason.RUN_ACTIVE)
    }

    private suspend fun requireModel(model: ModelRef) {
        models.resolve(model) ?: throw ChatRejected(ChatRejected.Reason.MODEL_UNAVAILABLE)
    }

    private suspend fun assistant(id: String): Pair<MessageNode, MessageBody.Assistant> {
        val node = store.node(id) ?: throw ChatRejected(ChatRejected.Reason.NOT_ALLOWED)
        val body = node.body as? MessageBody.Assistant ?: throw ChatRejected(ChatRejected.Reason.NOT_ALLOWED)
        return node to body
    }

    private fun node(sessionId: String, role: Role, body: MessageBody, at: Long) =
        MessageNode(Ids.new(), sessionId, null, null, role, at, body)

    private fun pending(sessionId: String, model: ModelRef, options: GenOptions, at: Long) =
        node(sessionId, Role.ASSISTANT, MessageBody.Assistant(emptyList(), ReplyStatus.PENDING, model = model, options = options), at)
}
