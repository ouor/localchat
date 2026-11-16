package com.ouor.localchat.chat.run

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Control
import com.ouor.localchat.chat.BrowserPort
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.chat.AttachmentPort
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.ApprovalRequest
import com.ouor.localchat.tools.HandoffRequest
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs one turn's tool calls (architecture 6.4) and is the tools' [ToolContext]. One instance per
 * turn so citation numbers stay stable within the message (S-04).
 */
class ToolExecutor(
    override val sessionId: String,
    /** Lazy: only browser tools touch the engine (JVM tests have none). */
    private val engine: Provider<BrowserEngine>,
    private val attachments: AttachmentPort,
    private val browserPort: BrowserPort,
    private val handle: RunRegistry.RunHandle,
    private val tools: List<Tool>,
    existingSources: List<Source>,
    /** Shows a call's state change (AWAITING_APPROVAL ↔ RUNNING) in the reply. */
    private val onState: (callKey: String, ToolCallState) -> Unit,
) : ToolContext {

    data class Result(val block: ToolResultBlock, val state: ToolCallState, val handoff: HandoffRequest?)

    override val browser: BrowserEngine get() = engine.get()

    private val sources = existingSources.associateBy { it.url }.toMutableMap()
    private var callKey: String? = null

    /** Tab the current call acted on, for cookie masking. */
    private var lastTab: BrowserTab? = null

    suspend fun execute(call: ToolCallBlock): Result {
        callKey = call.key
        lastTab = null
        val tool = tools.firstOrNull { it.name == call.name }
            ?: return error(call, "Unknown tool: ${call.name}")
        val args = try {
            Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            return error(call, "Invalid arguments: not a JSON object")
        }
        handle.update { it.copy(phase = RunPhase.RunningTool(call.callId, call.name, args.tabId())) }
        if (tool.approval == ApprovalMode.ALWAYS) {
            val code = args["code"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            if (!requireApproval(ApprovalRequest(tool.name, targetText = null, url = null, code = code))) {
                return done(call, ToolResultBlock(call.callId, call.step, SystemPrompt.DENIED, isError = true), ToolCallState.DENIED)
            }
        }
        return try {
            val outcome = tool.run(args, this)
            val block = ToolResultBlock(
                callId = call.callId,
                step = call.step,
                content = postProcess(outcome.text),
                isError = outcome.isError,
                summary = outcome.summary,
                sources = outcome.sources,
                artifactIds = outcome.artifactIds,
                imageForModelId = outcome.imageForModelId,
            )
            val state = when {
                outcome.denied -> ToolCallState.DENIED
                outcome.isError -> ToolCallState.FAILED
                else -> ToolCallState.DONE
            }
            done(call, block, state, outcome.handoff)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ToolError, PageError and anything unexpected: the model sees the message and can adapt.
            error(call, e.message ?: "Tool failed: ${e::class.simpleName}")
        } finally {
            if (lastTab != null) browserPort.clearAiActivity(sessionId)
        }
    }

    // ToolContext

    override suspend fun aiTab(tabIdArg: Int?): BrowserTab {
        var id = browserPort.aiTabId(sessionId, tabIdArg)
        val control = browserPort.control(id)
        if (control.first() == Control.USER) {
            // B-09 ③: wait while the user holds the tab; released when control returns to AI.
            val before = handle.state.phase
            handle.update { it.copy(phase = RunPhase.Paused(Pause.UserControl(id))) }
            try {
                control.first { it == Control.AI }
            } finally {
                handle.update { it.copy(phase = before) }
            }
            // The user may have navigated anywhere meanwhile: check again.
            id = browserPort.aiTabId(sessionId, id)
        }
        return browserPort.tab(id).also { lastTab = it }
    }

    override suspend fun requireApproval(request: ApprovalRequest): Boolean {
        val id = callKey ?: Ids.new()
        onState(id, ToolCallState.AWAITING_APPROVAL)
        val answer = handle.pause(Pause.Approval(id, request)) as Resolution.ApprovalAnswer
        onState(id, ToolCallState.RUNNING)
        return answer.allow
    }

    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String =
        attachments.saveArtifact(name, mime, bytes)

    override fun numberSource(url: String, title: String, snippet: String?): Int =
        sources.getOrPut(url) { Source(sources.size + 1, url, title, snippet) }.n

    /** Architecture 6.4 post-processing, in this order. Every tool returns page-derived text (B-S5). */
    private fun postProcess(text: String): String {
        val masked = lastTab?.let { tab -> browserPort.maskCookies(text, tab.url) } ?: text
        val capped = if (masked.length > MAX_RESULT) masked.take(MAX_RESULT) + "\n…[truncated]" else masked
        return "<untrusted_page_content>\n$capped\n</untrusted_page_content>"
    }

    private fun error(call: ToolCallBlock, message: String) =
        done(call, ToolResultBlock(call.callId, call.step, message, isError = true), ToolCallState.FAILED)

    private fun done(call: ToolCallBlock, block: ToolResultBlock, state: ToolCallState, handoff: HandoffRequest? = null) =
        Result(block, state, handoff)

    private fun JsonObject.tabId(): Int? = this["tabId"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

    private companion object {
        /** 50 KB result cap (architecture 6.4). */
        const val MAX_RESULT = 50_000
    }
}
