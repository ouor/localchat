package com.ouor.localchat.chat.run

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Control
import com.ouor.localchat.chat.BrowserPort
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.chat.context.Untrusted
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
import com.ouor.localchat.tools.ToolError
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

    /** Tab the current call acted on, for cookie masking and the sensitive-URL recheck. */
    private var lastTabId: Int? = null

    /** Tab and URL an ALWAYS approval was given for; the tool must act on exactly that page. */
    private var approvedPage: Pair<Int, String?>? = null

    suspend fun execute(call: ToolCallBlock): Result {
        callKey = call.key
        lastTabId = null
        val tool = tools.firstOrNull { it.name == call.name }
            ?: return error(call, "Unknown tool: ${call.name}")
        val args = try {
            Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            return error(call, "Invalid arguments: not a JSON object")
        }
        handle.update { it.copy(phase = RunPhase.RunningTool(call.callId, call.name, args.tabId())) }
        return try {
            if (tool.approval == ApprovalMode.ALWAYS) {
                // SE 9: resolve the tab first so the sheet names where the code runs, then pin that page.
                val tabId = browserPort.aiTabId(sessionId, args.tabId())
                val url = browserPort.tabUrl(tabId)
                val code = args["code"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                if (!ask(ApprovalInfo(tool.name, tabId, url, targetText = null, code = code))) {
                    return done(call, ToolResultBlock(call.callId, call.step, SystemPrompt.DENIED, isError = true), ToolCallState.DENIED)
                }
                approvedPage = tabId to url
            }
            val outcome = tool.run(args, this)
            // SE 2: the tab may have redirected to a sensitive page after it was acquired.
            val sensitive = lastTabId?.let(browserPort::tabUrl)?.let(browserPort::isSensitive) == true
            val block = ToolResultBlock(
                callId = call.callId,
                step = call.step,
                content = if (sensitive) SENSITIVE else seal(outcome.text, lastTabId),
                isError = outcome.isError || sensitive,
                summary = outcome.summary.takeUnless { sensitive },
                sources = outcome.sources,
                artifactIds = if (sensitive) emptyList() else outcome.artifactIds,
                imageForModelId = outcome.imageForModelId.takeUnless { sensitive },
            )
            val state = when {
                outcome.denied -> ToolCallState.DENIED
                outcome.isError -> ToolCallState.FAILED
                else -> ToolCallState.DONE
            }
            done(call, block, state, outcome.handoff)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolError) {
            // App-written text: sent as is.
            error(call, e.message ?: "Tool failed")
        } catch (e: Exception) {
            // PageError and anything else may carry page text (alert(), option labels, a thrown cookie): SE 4.
            error(call, seal(e.message ?: "Tool failed: ${e::class.simpleName}", lastTabId))
        } finally {
            approvedPage = null
            if (lastTabId != null) browserPort.clearAiActivity(sessionId)
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
        approvedPage?.let { (tabId, url) ->
            if (tabId != id || browserPort.tabUrl(id) != url) throw ToolError("The page changed after approval; ask again.")
        }
        lastTabId = id
        return browserPort.tab(id)
    }

    override suspend fun requireApproval(request: ApprovalRequest): Boolean = ask(
        ApprovalInfo(request.toolName, lastTabId, request.url, request.targetText, request.code, request.screenshotId, request.reason),
    )

    private suspend fun ask(info: ApprovalInfo): Boolean {
        val id = callKey ?: Ids.new()
        onState(id, ToolCallState.AWAITING_APPROVAL)
        val answer = handle.pause(Pause.Approval(id, info)) as Resolution.ApprovalAnswer
        onState(id, ToolCallState.RUNNING)
        return answer.allow
    }

    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String =
        attachments.saveArtifact(name, mime, bytes)

    override fun numberSource(url: String, title: String, snippet: String?): Int =
        sources.getOrPut(url) { Source(sources.size + 1, url, title, snippet) }.n

    /**
     * Architecture 6.4 post-processing, in this order: cookie mask by [tabId]'s URL, 50 KB cap, boundary.
     * Every tool returns page-derived text (B-S5); LinkPrefetcher goes through here too (AN 12).
     */
    fun seal(text: String, tabId: Int?): String {
        val url = tabId?.let(browserPort::tabUrl)
        val masked = url?.let { browserPort.maskCookies(text, it) } ?: text
        val capped = if (masked.length > MAX_RESULT) masked.take(MAX_RESULT) + "\n…[truncated]" else masked
        return Untrusted.wrap(capped)
    }

    private fun error(call: ToolCallBlock, message: String) =
        done(call, ToolResultBlock(call.callId, call.step, message, isError = true), ToolCallState.FAILED)

    private fun done(call: ToolCallBlock, block: ToolResultBlock, state: ToolCallState, handoff: HandoffRequest? = null) =
        Result(block, state, handoff)

    private fun JsonObject.tabId(): Int? = this["tabId"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

    private companion object {
        /** 50 KB result cap (architecture 6.4). */
        const val MAX_RESULT = 50_000
        const val SENSITIVE = "The tab is now on a sensitive page (banking, mail, accounts); its content was withheld."
    }
}
