package com.ouor.localchat.chat.run

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.Control
import com.ouor.localchat.chat.AttachmentPayload
import com.ouor.localchat.chat.AttachmentPort
import com.ouor.localchat.chat.BrowserPort
import com.ouor.localchat.chat.ChatStore
import com.ouor.localchat.chat.CompactionFailed
import com.ouor.localchat.chat.Compactor
import com.ouor.localchat.chat.LlmPort
import com.ouor.localchat.chat.ModelPort
import com.ouor.localchat.chat.ResolvedModel
import com.ouor.localchat.chat.TitleGenerator
import com.ouor.localchat.chat.ToolPort
import com.ouor.localchat.chat.context.AssemblyInput
import com.ouor.localchat.chat.context.ContextAssembler
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.chat.context.TokenEstimate
import com.ouor.localchat.core.ApplicationScope
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.MessageTree
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.llm.LlmError
import com.ouor.localchat.llm.LlmEvent
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.tools.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** The agent loop of one reply (architecture 5.3). Started by ChatEngine through RunRegistry. */
@Singleton
class TurnRunner @Inject constructor(
    private val store: ChatStore,
    private val llm: LlmPort,
    private val models: ModelPort,
    private val toolPort: ToolPort,
    private val attachments: AttachmentPort,
    private val browserPort: BrowserPort,
    private val browser: BrowserEngine,
    private val files: AttachmentFiles,
    private val settings: SettingsStore,
    private val compactor: Compactor,
    private val titles: TitleGenerator,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    enum class Mode { NEW, CONTINUE }

    /** Runs until the reply is DONE, ERROR or cancelled; the reply is always saved at the end. */
    suspend fun run(handle: RunRegistry.RunHandle, mode: Mode) {
        val turn = Turn(handle, mode)
        try {
            turn.loop()
        } finally {
            withContext(NonCancellable) { turn.finish() }
        }
    }

    private inner class Turn(val handle: RunRegistry.RunHandle, val mode: Mode) {
        val sessionId = handle.state.sessionId
        var node: MessageNode = handle.state.live
        var body = node.body as MessageBody.Assistant
        /** Dispatched call without a stored result yet ("Interrupted" on stop). */
        var dispatching: String? = null
        var lastSave = 0L
        val payloads = HashMap<String, AttachmentPayload>()

        fun publish(b: MessageBody.Assistant) {
            body = b
            node = node.copy(body = b)
            handle.update { it.copy(live = node) }
        }

        fun phase(phase: RunPhase) = handle.update { it.copy(phase = phase) }

        suspend fun save() {
            store.saveNode(node)
            lastSave = clock.now()
        }

        suspend fun finish() {
            publish(StreamCollector.close(body, dispatching, clock.now()))
            save()
        }

        suspend fun loop() {
            val prefs = settings.settings.first()
            val model = body.model?.let { models.resolve(it) }
            if (model == null) {
                publish(body.copy(status = ReplyStatus.ERROR, error = ChatError(ChatErrorKind.BAD_REQUEST, message = "Model unavailable")))
                return
            }
            val session = store.session(sessionId) ?: return
            val path = MessageTree.path(session.rootSelectedId, store.nodes(sessionId)).takeWhile { it.id != node.id }
            val tools = if (mode == Mode.CONTINUE) emptyList() else toolPort.exposed(model.caps, body.options.search, body.options.browser)
            val system = body.systemContext.takeIf { mode == Mode.CONTINUE }
                ?: SystemPrompt.build(clock.now(), ZoneId.systemDefault(), prefs.region, prefs.userInstructions, tools.isNotEmpty())
            val existingSources = body.blocks.filterIsInstance<ToolResultBlock>().flatMap { it.sources }
            val executor = ToolExecutor(sessionId, browser, files, browserPort, handle, tools, existingSources) { id, state ->
                publish(body.copy(blocks = body.blocks.map { if (it is ToolCallBlock && it.callId == id) it.copy(state = state) else it }))
            }
            val maxSteps = prefs.maxSteps
            var step = if (mode == Mode.CONTINUE) body.blocks.maxOfOrNull { it.step } ?: 0 else body.steps
            var compacted = false
            var askedAt = -1
            publish(body.copy(status = ReplyStatus.STREAMING, systemContext = system, finishReason = null, error = null))
            handle.update { it.copy(maxSteps = maxSteps) }

            while (true) {
                // A-02 step limit.
                if (mode == Mode.NEW && step > 0 && step % maxSteps == 0 && askedAt != step) {
                    askedAt = step
                    save()
                    val answer = handle.pause(Pause.StepLimit(step)) as Resolution.StepLimitAnswer
                    if (!answer.continueRun) {
                        publish(body.copy(status = ReplyStatus.DONE))
                        return
                    }
                }
                handle.update { it.copy(phase = RunPhase.Requesting, step = step) }

                var request = assemble(path, model, tools, system)
                val window = model.caps.contextWindow.value
                if (!compacted && window != null && estimate(request, path) > window * 0.8) {
                    compacted = true
                    phase(RunPhase.Compacting)
                    try {
                        compactor.compact(sessionId, path, model)
                    } catch (_: CompactionFailed) {
                        // Too-long context will surface as ContextOverflow; nothing else to do here.
                    }
                    request = assemble(path, model, tools, system)
                }

                var terminal: LlmEvent? = null
                llm.stream(model.target, request).collect { event ->
                    if (event is LlmEvent.Retrying) phase(RunPhase.Retrying(event.attempt, event.max, event.delayMs))
                    else if (handle.state.phase != RunPhase.Streaming) phase(RunPhase.Streaming)
                    if (event is LlmEvent.Finished || event is LlmEvent.Failed) terminal = event
                    val overflowRetry = event is LlmEvent.Failed && event.error is LlmError.ContextOverflow && !compacted
                    if (!overflowRetry) publish(StreamCollector.reduce(body, event, step, clock.now()))
                    if (clock.now() - lastSave >= SAVE_INTERVAL_MS) save()
                }

                when (val t = terminal) {
                    null -> {
                        publish(body.copy(status = ReplyStatus.ERROR, error = ChatError(ChatErrorKind.PROTOCOL, message = "Stream ended without a result")))
                        return
                    }
                    is LlmEvent.Failed -> {
                        if (t.error is LlmError.ContextOverflow && !compacted) {
                            // A-06 (b): compact once and retry the same step.
                            compacted = true
                            phase(RunPhase.Compacting)
                            val summary = try {
                                compactor.compact(sessionId, path, model)
                            } catch (_: CompactionFailed) {
                                null
                            }
                            if (summary != null) {
                                publish(body.copy(blocks = body.blocks.filter { it.step != step }))
                                continue
                            }
                            publish(StreamCollector.reduce(body, t, step, clock.now()))
                        }
                        publish(body.copy(status = ReplyStatus.ERROR))
                        return
                    }
                    is LlmEvent.Finished -> {
                        publish(body.copy(steps = step + 1))
                        val answered = body.blocks.filterIsInstance<ToolResultBlock>().map { it.callId }.toSet()
                        val calls = body.blocks.filterIsInstance<ToolCallBlock>().filter { it.step == step && it.callId !in answered }
                        if (body.refusal || calls.isEmpty()) {
                            publish(body.copy(status = ReplyStatus.DONE))
                            if (mode == Mode.NEW && path.count { it.body is MessageBody.User } == 1) launchTitle(path, model)
                            return
                        }
                        save()
                        for (call in calls) runTool(executor, call)
                        step++
                    }
                    else -> error("not terminal")
                }
            }
        }

        suspend fun runTool(executor: ToolExecutor, call: ToolCallBlock) {
            dispatching = call.callId
            val result = executor.execute(call)
            var block = result.block
            result.handoff?.let { handoff ->
                // B-05: hand the tab to the user until they give it back.
                browserPort.setControl(handoff.tabId, Control.USER)
                save()
                handle.pause(Pause.Handoff(handoff.tabId, handoff.reason))
                browserPort.setControl(handoff.tabId, Control.AI)
                block = block.copy(content = SystemPrompt.HANDOFF_DONE, isError = false)
            }
            val now = clock.now()
            publish(
                body.copy(
                    blocks = body.blocks.map {
                        if (it is ToolCallBlock && it.callId == call.callId) it.copy(state = result.state, endedAt = now) else it
                    } + block,
                ),
            )
            dispatching = null
            save()
        }

        suspend fun assemble(path: List<MessageNode>, model: ResolvedModel, tools: List<Tool>, system: String) =
            ContextAssembler.build(
                AssemblyInput(
                    system = system,
                    path = path,
                    current = body,
                    summaries = store.summaries(sessionId),
                    attachments = loadPayloads(path),
                    model = model,
                    options = body.options,
                    tools = tools,
                    continueMode = mode == Mode.CONTINUE,
                ),
            )

        suspend fun estimate(request: com.ouor.localchat.llm.wire.ChatRequest, path: List<MessageNode>): Int {
            val summaryAt = ContextAssembler.applicableSummary(store.summaries(sessionId), path)?.createdAt
            return TokenEstimate.estimate(request, path + node, summaryAt)
        }

        suspend fun loadPayloads(path: List<MessageNode>): Map<String, AttachmentPayload> {
            val ids = path.flatMap { (it.body as? MessageBody.User)?.attachmentIds.orEmpty() } +
                (path.map { it.body } + body).filterIsInstance<MessageBody.Assistant>()
                    .flatMap { b -> b.blocks.filterIsInstance<ToolResultBlock>().mapNotNull { it.imageForModelId } }
            for (id in ids) if (id !in payloads) attachments.payload(id)?.let { payloads[id] = it }
            return payloads
        }

        fun launchTitle(path: List<MessageNode>, model: ResolvedModel) {
            val question = (path.last().body as? MessageBody.User)?.text ?: return
            val answer = body.blocks.filterIsInstance<TextBlock>().joinToString("") { it.text }
            scope.launch {
                try {
                    titles.generate(sessionId, model, question, answer)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // C-02: the FIRST_PROMPT title stays.
                }
            }
        }
    }

    private companion object {
        /** Architecture 8.1: streaming writes at most once a second, plus at step boundaries and the end. */
        const val SAVE_INTERVAL_MS = 1000L
    }
}
