package com.ouor.localchat.chat.run

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Control
import com.ouor.localchat.chat.BrowserPort
import com.ouor.localchat.chat.ToolRejected
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.ApprovalRequest
import com.ouor.localchat.tools.HandoffRequest
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
    override val browser: BrowserEngine,
    override val files: AttachmentFiles,
    private val browserPort: BrowserPort,
    private val handle: RunRegistry.RunHandle,
    private val tools: List<Tool>,
    existingSources: List<Source>,
    /** Shows a call's state change (AWAITING_APPROVAL ↔ RUNNING) in the reply. */
    private val onState: (callId: String, ToolCallState) -> Unit,
) : ToolContext {

    data class Result(val block: ToolResultBlock, val state: ToolCallState, val handoff: HandoffRequest?)

    private val sources = existingSources.associateBy { it.url }.toMutableMap()
    private var callId: String? = null

    suspend fun execute(call: ToolCallBlock): Result {
        callId = call.callId
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
                content = postProcess(tool, outcome.text),
                isError = outcome.isError,
                summary = outcome.summary,
                sources = outcome.sources,
                artifactIds = outcome.artifactIds,
                imageForModelId = outcome.imageForModelId,
            )
            done(call, block, if (outcome.isError) ToolCallState.FAILED else ToolCallState.DONE, outcome.handoff)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolRejected) {
            error(call, e.message ?: "Rejected")
        } catch (e: Exception) {
            error(call, "Tool failed: ${e.message ?: e::class.simpleName}")
        }
    }

    // ToolContext

    override suspend fun aiTab(tabIdArg: Int?): BrowserTab {
        val tab = browserPort.aiTab(sessionId, tabIdArg)
        val control = browserPort.control(tab.id)
        if (control.first() == Control.USER) {
            // B-09 ③: wait while the user holds the tab; released when control returns to AI.
            val before = handle.state.phase
            handle.update { it.copy(phase = RunPhase.Paused(Pause.UserControl(tab.id))) }
            try {
                control.first { it == Control.AI }
            } finally {
                handle.update { it.copy(phase = before) }
            }
        }
        return tab
    }

    override suspend fun requireApproval(request: ApprovalRequest): Boolean {
        val id = callId ?: Ids.new()
        onState(id, ToolCallState.AWAITING_APPROVAL)
        val answer = handle.pause(Pause.Approval(id, request)) as Resolution.ApprovalAnswer
        onState(id, ToolCallState.RUNNING)
        return answer.allow
    }

    override fun numberSource(url: String, title: String, snippet: String?): Int =
        sources.getOrPut(url) { Source(sources.size + 1, url, title, snippet) }.n

    private fun postProcess(tool: Tool, text: String): String {
        // TODO(track C): mask cookie values with browser.safety.CookieMasker (B-S4) once it exists.
        val capped = if (text.length > MAX_RESULT) text.take(MAX_RESULT) + "\n…[truncated]" else text
        return if (tool.name in PAGE_CONTENT) "<untrusted_page_content>\n$capped\n</untrusted_page_content>" else capped
    }

    private fun error(call: ToolCallBlock, message: String) =
        done(call, ToolResultBlock(call.callId, call.step, message, isError = true), ToolCallState.FAILED)

    private fun done(call: ToolCallBlock, block: ToolResultBlock, state: ToolCallState, handoff: HandoffRequest? = null) =
        Result(block, state, handoff)

    private fun JsonObject.tabId(): Int? = this["tabId"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

    private companion object {
        /** 50 KB result cap (architecture 6.4). */
        const val MAX_RESULT = 50_000

        /** B-S5: results that carry web page text. */
        val PAGE_CONTENT = setOf("snapshot", "page_text", "console_logs", "execute", "web_search", "get_tabs")
    }
}
