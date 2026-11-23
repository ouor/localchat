package com.ouor.localchat.chat.run

import android.util.Log
import com.ouor.localchat.agent.BrowserPort
import com.ouor.localchat.agent.BrowserToolRunner
import com.ouor.localchat.agent.RunnerHost
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.ToolStatus
import com.ouor.localchat.chat.AttachmentPort
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.policy.ApprovalRequest
import com.ouor.localchat.policy.Policies
import com.ouor.localchat.tools.ChatTabs
import com.ouor.localchat.tools.Source
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.ToolResultFormat
import com.ouor.localchat.tools.UserStep
import com.ouor.localchat.tools.browser.Read
import javax.inject.Provider

/**
 * The chat's side of [BrowserToolRunner]: a [ToolCallBlock] in, a [ToolResultBlock] and call state out, stored as
 * [ToolResultFormat] cuts it. Approvals pause the run, statuses go to its phase, artifacts to attachments, and sources
 * are numbered for the message. One instance per turn so citation numbers stay stable within the message.
 */
class ToolExecutor(
    sessionId: String,
    engine: Provider<BrowserEngine>,
    private val attachments: AttachmentPort,
    browserPort: BrowserPort,
    policies: Policies,
    private val handle: RunRegistry.RunHandle,
    tools: List<Tool>,
    existingSources: List<Source>,
    contextWindow: Int? = null,
    vision: Boolean = false,
    /** This chat's tabs memory; it outlives the turn. */
    chat: ChatTabs,
    /** The read tool link prefetch runs, exposed or not (the catalog's; JVM tests pass a fake). */
    readTool: Tool = Read,
    /** Shows a call's state change (AWAITING_APPROVAL ↔ RUNNING) in the reply. */
    private val onState: (callKey: String, ToolCallState) -> Unit,
) : RunnerHost {

    data class Result(val block: ToolResultBlock, val state: ToolCallState, val userStep: UserStep?)

    private val runner = BrowserToolRunner(
        sessionId, engine::get, browserPort, policies, this, tools, chat, contextWindow, vision, readTool,
    ) { message, e -> runCatching { Log.w("localchat", message, e) } } // JVM tests have no android.util.Log

    /** Tools this turn may run; only ever narrowed ([narrow]). */
    val tools: List<Tool> get() = runner.tools

    fun narrow(keep: Set<String>) = runner.narrow(keep)

    fun isOff(name: String) = runner.isOff(name)

    private val sources = existingSources.associateBy { it.url }.toMutableMap()

    /** The call in hand; the runner names it by its key. */
    private var current: ToolCallBlock? = null

    /** The runner has not yet reported the current call's start. */
    suspend fun execute(call: ToolCallBlock): Result {
        current = call
        return block(call, runner.run(call.key, call.name, call.argumentsJson))
    }

    /** LinkPrefetcher (A-m10): a link in a question read through the same chain as a read call; null when it failed. */
    suspend fun prefetchRead(url: String): ToolOutcome? = runner.prefetchRead(url)

    /** ask_user's result once the user tapped Done: the page as they left it, through the chain like look. */
    suspend fun afterUserStep(call: ToolCallBlock, step: UserStep): ToolResultBlock {
        current = call
        return block(call, runner.afterUserStep(call.key, step)).block
    }

    private fun block(call: ToolCallBlock, o: ToolOutcome): Result {
        val b = ToolResultBlock(
            callId = call.callId,
            step = call.step,
            page = ToolResultFormat.cap(o.page),
            isError = o.isError,
            summary = o.summary,
            sources = o.sources,
            artifactIds = o.artifactIds,
            imageForModelId = o.imageForModelId,
            lead = o.lead,
            note = o.note,
            refused = o.refused,
        )
        val state = when {
            o.denied -> ToolCallState.DENIED
            o.isError -> ToolCallState.FAILED
            else -> ToolCallState.DONE
        }
        return Result(b, state, o.userStep)
    }

    // RunnerHost

    override suspend fun confirm(callId: String, request: ApprovalRequest): Boolean {
        onState(callId, ToolCallState.AWAITING_APPROVAL)
        val answer = handle.pause(Pause.Approval(callId, request)) as Resolution.ApprovalAnswer
        onState(callId, ToolCallState.RUNNING)
        return answer.allow
    }

    override fun started(callId: String) {
        val call = current?.takeIf { it.key == callId } ?: return
        handle.update { it.copy(phase = RunPhase.RunningTool(call.callId, call.name, null)) }
    }

    override fun status(callId: String, status: ToolStatus?, engine: String?, tabId: Int?) {
        val call = current?.takeIf { it.key == callId } ?: return
        handle.update { s ->
            (s.phase as? RunPhase.RunningTool)?.takeIf { it.callId == call.callId }
                ?.let { s.copy(phase = it.copy(tabId = tabId, status = status, engine = engine)) } ?: s
        }
    }

    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String =
        attachments.saveArtifact(name, mime, bytes)

    override fun numberSource(url: String, title: String, snippet: String?): Int =
        sources.getOrPut(url) { Source(sources.size + 1, url, title, snippet) }.n
}
