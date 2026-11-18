package com.ouor.localchat.chat.run

import android.util.Log
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.chat.AttachmentPort
import com.ouor.localchat.chat.BrowserPort
import com.ouor.localchat.chat.context.Untrusted
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.core.model.ToolStatus
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.ApprovalRequest
import com.ouor.localchat.tools.HandoffRequest
import com.ouor.localchat.tools.ReadCursor
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.browser.pageResult
import com.ouor.localchat.tools.browser.ready
import com.ouor.localchat.tools.browser.sensitiveMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Provider

/**
 * Runs one turn's tool calls (architecture 6.4) and is the tools' [ToolContext]. One instance per
 * turn so citation numbers and read continuations stay stable within the message (S-04).
 */
class ToolExecutor(
    override val sessionId: String,
    /** Lazy: only browser tools touch the engine (JVM tests have none). */
    private val engine: Provider<BrowserEngine>,
    private val attachments: AttachmentPort,
    private val browserPort: BrowserPort,
    private val handle: RunRegistry.RunHandle,
    tools: List<Tool>,
    existingSources: List<Source>,
    override val contextWindow: Int? = null,
    override val vision: Boolean = false,
    /** Shows a call's state change (AWAITING_APPROVAL ↔ RUNNING) in the reply. */
    private val onState: (callKey: String, ToolCallState) -> Unit,
) : ToolContext {

    data class Result(val block: ToolResultBlock, val state: ToolCallState, val handoff: HandoffRequest?)

    override val browser: BrowserEngine get() = engine.get()

    /** Tools this turn may run; only ever narrowed ([narrow]). */
    var tools: List<Tool> = tools
        private set

    override val exposed: Set<String> get() = tools.mapTo(HashSet()) { it.name }

    /** Names dropped by [narrow]: a call to one is answered [TOOL_OFF], not "Unknown tool". */
    private val turnedOff = HashSet<String>()

    /** Audit #10: keeps only the tools named in [keep] (a switch was turned off mid-run). */
    fun narrow(keep: Set<String>) {
        tools.filter { it.name !in keep }.mapTo(turnedOff) { it.name }
        tools = tools.filter { it.name in keep }
    }

    fun isOff(name: String) = name in turnedOff

    private val sources = existingSources.associateBy { it.url }.toMutableMap()
    override val readCursors = HashMap<String, ReadCursor>()

    /** Failed calls of this turn by tool + normalized arguments (tools-redesign 5-4). */
    private val failures = HashMap<String, Int>()

    private var callKey: String? = null
    private var callId: String? = null

    /** Work tab the current call acted on, for cookie masking and the sensitive-URL recheck. */
    private var lastTabId: Int? = null

    /** The current call's tool (B-09 ② label) and every URL it was on (B-S4: all are masked, SE 19). */
    private var toolName: String? = null
    private val visited = LinkedHashSet<String>()

    /** Tab and URL an ALWAYS approval was given for; the tool must act on exactly that page. */
    private var approvedPage: Pair<Int, String?>? = null

    suspend fun execute(call: ToolCallBlock): Result {
        callKey = call.key
        callId = call.callId
        lastTabId = null
        toolName = call.name
        visited.clear()
        val tool = tools.firstOrNull { it.name == call.name }
            ?: return error(call, if (call.name in turnedOff) TOOL_OFF else unknown(call.name))
        val args = try {
            Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            return error(call, "Invalid arguments: not a JSON object")
        }
        handle.update { it.copy(phase = RunPhase.RunningTool(call.callId, call.name, null)) }
        val result = try {
            if (tool.approval == ApprovalMode.ALWAYS) {
                // SE 9: the sheet names where the code runs, then that page is pinned.
                val tabId = browserPort.workTabId(sessionId) ?: throw ToolError(BrowserEngine.NO_TAB)
                val url = browserPort.tabUrl(tabId)
                if (url != null && browserPort.isSensitive(url)) throw ToolError(sensitiveMessage(SensitiveUrls.hostOf(url).orEmpty()))
                val code = args["code"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                if (!ask(ApprovalInfo(tool.name, tabId, url, targetText = null, code = code))) {
                    return done(call, ToolResultBlock(call.callId, call.step, ToolOutcome.DENIED_TEXT, isError = true), ToolCallState.DENIED)
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
            error(call, e.message ?: TOOL_FAILED)
        } catch (e: PageError) {
            // May carry page text (alert(), option labels, a thrown cookie): SE 4.
            val hint = if (e.message == PageScript.NOT_RUN_AGAIN && "browse" in exposed) " Call browse() to see where it is now." else ""
            error(call, seal((e.message ?: TOOL_FAILED) + hint, lastTabId))
        } catch (_: NoSuchElementException) {
            error(call, TAB_CLOSED + if ("browse" in exposed) " Call browse to open it again." else "")
        } catch (e: Exception) {
            // FB3 eng m5: an unplanned exception's text (a path, an internal state, a class name R8 renames) is not
            // for the model or the user; it goes to the log only.
            runCatching { Log.w("localchat", "tool ${call.name} failed", e) } // JVM tests have no android.util.Log
            error(call, TOOL_FAILED)
        } finally {
            approvedPage = null
            if (lastTabId != null) browserPort.clearAiActivity(sessionId)
        }
        return repeated(call, args, result)
    }

    /** tools-redesign 5-4: the second failure of the same call this turn gets an app note, outside the page boundary. */
    private fun repeated(call: ToolCallBlock, args: JsonObject, r: Result): Result {
        if (r.state != ToolCallState.FAILED || !r.block.isError) return r
        val key = call.name + normalized(args)
        val n = (failures[key] ?: 0) + 1
        failures[key] = n
        return if (n == 2) r.copy(block = r.block.copy(content = r.block.content + "\n\n" + REPEAT_NOTE)) else r
    }

    private fun normalized(e: JsonElement): String = when (e) {
        is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> "$k:" + normalized(v) }
        is JsonPrimitive -> if (e.isString) e.content.trim() else e.toString()
        else -> e.toString()
    }

    private fun unknown(name: String): String {
        val now = tools.map { it.name }
        return "Unknown tool: $name. " + if (now.isEmpty()) "No tools can be called now." else "Tools you can call now: ${now.joinToString(", ")}."
    }

    // ToolContext

    override suspend fun workTab(navigating: Boolean): BrowserTab {
        // F-02: the engine checks, waits while the user holds the tab (B-09 ③) and marks AI activity (B-09 ②).
        var before: RunPhase? = null
        val id = browserPort.acquireAiTab(
            sessionId, toolName.orEmpty(), navigating,
            onUserControl = { tab ->
                before = handle.state.phase
                handle.update { it.copy(phase = RunPhase.Paused(Pause.UserControl(tab))) }
            },
            onResumed = { before?.let { phase -> handle.update { it.copy(phase = phase) } } },
        )
        browserPort.tabUrl(id)?.let(visited::add)
        approvedPage?.let { (tabId, url) ->
            if (tabId != id || browserPort.tabUrl(id) != url) throw ToolError("The page changed after approval; ask again.")
        }
        lastTabId = id
        // The panel peeks at the work tab while a tool uses it (B-02).
        handle.update { s -> (s.phase as? RunPhase.RunningTool)?.takeIf { it.callId == callId }?.let { s.copy(phase = it.copy(tabId = id)) } ?: s }
        return withContext(Dispatchers.Main.immediate) { browserPort.tab(id) }
    }

    override suspend fun <T> hiddenTab(url: String, block: suspend (BrowserTab) -> T): T {
        var id: Int? = null
        try {
            // The id is stored inside NonCancellable: a cancel on the way out of withContext then still finds it in finally.
            withContext(NonCancellable) { id = browserPort.openHiddenTab(sessionId, url) } // Too many tabs throws (AN 1).
            val tabId = checkNotNull(id)
            visited += url
            val result = withContext(Dispatchers.Main.immediate) { block(browserPort.tab(tabId)) }
            // A redirect may have landed on a sensitive page (SE 2).
            val landed = browserPort.tabUrl(tabId)
            landed?.let(visited::add)
            if (landed != null && browserPort.isSensitive(landed)) throw ToolError(sensitiveMessage(SensitiveUrls.hostOf(landed).orEmpty()))
            return result
        } finally {
            id?.let { withContext(NonCancellable) { browserPort.close(it) } }
        }
    }

    override fun status(s: ToolStatus?, engine: String?) {
        handle.update { st ->
            (st.phase as? RunPhase.RunningTool)?.takeIf { it.callId == callId }?.let { st.copy(phase = it.copy(status = s, engine = engine)) } ?: st
        }
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

    /** B-05: the result of request_handoff once the user gave [tabId] back (tools-redesign 3-7). */
    suspend fun afterHandoff(tabId: Int): String {
        val url = browserPort.tabUrl(tabId) ?: return HANDOFF_CLOSED
        if (browserPort.isSensitive(url)) {
            return "The user gave the tab back. It is on a sensitive site (${SensitiveUrls.hostOf(url)}), so its content is withheld."
        }
        return try {
            val page = withContext(Dispatchers.Main.immediate) {
                val tab = browserPort.tab(tabId)
                val since = com.ouor.localchat.tools.browser.now()
                pageResult(tab, ready(tab, since), since)
            }
            visited.clear()
            visited += url
            seal("The user gave the tab back. They may or may not have finished; check the page below.\n$page", tabId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoSuchElementException) {
            HANDOFF_CLOSED
        } catch (e: PageError) {
            seal("The user gave the tab back. Its page could not be listed: ${e.message}", tabId)
        }
    }

    /**
     * Architecture 6.4 post-processing, in this order: cookie mask of every URL the call was on, 50 KB cap, boundary.
     * Every tool returns page-derived text (B-S5); LinkPrefetcher goes through here too (AN 12).
     */
    fun seal(text: String, tabId: Int?): String {
        // SE 19: a call ends on another site than it started on, and hidden tabs are closed by now; mask them all.
        val urls = (visited + listOfNotNull(tabId?.let(browserPort::tabUrl))).distinct()
        val masked = urls.fold(text) { t, url -> browserPort.maskCookies(t, url) }
        val capped = if (masked.length > MAX_RESULT) masked.take(MAX_RESULT) + "\n…[truncated]" else masked
        return Untrusted.wrap(capped)
    }

    private fun error(call: ToolCallBlock, message: String) =
        done(call, ToolResultBlock(call.callId, call.step, message, isError = true), ToolCallState.FAILED)

    private fun done(call: ToolCallBlock, block: ToolResultBlock, state: ToolCallState, handoff: HandoffRequest? = null) =
        Result(block, state, handoff)

    companion object {
        /** 50 KB result cap (architecture 6.4). */
        private const val MAX_RESULT = 50_000
        const val SENSITIVE = "The tab is now on a sensitive page (banking, mail, accounts); its content was withheld."
        const val TOOL_OFF = "The user turned this tool off."
        const val TOOL_FAILED = "Tool failed unexpectedly (an app error). Do not repeat the same call; continue another way."
        const val TAB_CLOSED = "The tab closed during the call (the page crashed or the user closed it)."
        const val HANDOFF_CLOSED = "The user gave the tab back but closed it."
        const val REPEAT_NOTE = "[App note: this exact call failed twice. Change the approach, or answer with what you have.]"
    }
}
