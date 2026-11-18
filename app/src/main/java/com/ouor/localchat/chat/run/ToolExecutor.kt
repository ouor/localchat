package com.ouor.localchat.chat.run

import android.os.SystemClock
import android.util.Log
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.BrowserRefusal
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.TabClosed
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
import com.ouor.localchat.tools.browser.RequestHandoff
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
 * turn so citation numbers stay stable within the message (S-04).
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
    /** read's continuations of this chat; they outlive the turn (G-m9). */
    override val readCursors: MutableMap<String, ReadCursor>,
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

    /** The current call's work tab and start (uptime): its main-frame requests since then are masked too. */
    private var workTabRef: BrowserTab? = null
    private var callStart = 0L

    suspend fun execute(call: ToolCallBlock): Result {
        callKey = call.key
        callId = call.callId
        lastTabId = null
        workTabRef = null
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
            finish(call, tool.run(args, this))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolError) {
            // App-written text: sent as is; a stale ref also shows the current page.
            e.page?.let { finish(call, it) } ?: error(call, e.message ?: TOOL_FAILED)
        } catch (e: BrowserRefusal) {
            error(call, e.message ?: TOOL_FAILED)
        } catch (e: PageError) {
            // May carry page text (alert(), option labels, a thrown cookie): SE 4.
            val hint = if (e.message == PageScript.NOT_RUN_AGAIN && "browse" in exposed) "\nCall browse() to see where it is now." else ""
            error(call, seal(e.message ?: TOOL_FAILED, lastTabId) + hint)
        } catch (_: TabClosed) {
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

    /** A tool's outcome as the stored result: app lines outside, page text sealed (B-S5); withheld on a sensitive page (SE 2). */
    private fun finish(call: ToolCallBlock, outcome: ToolOutcome): Result {
        // SE 2: the tab may have redirected to a sensitive page after it was acquired.
        val sensitive = lastTabId?.let(browserPort::tabUrl)?.let(browserPort::isSensitive) == true
        workTabRef?.let { visited += it.mainRequestsSince(callStart) }
        val block = ToolResultBlock(
            callId = call.callId,
            step = call.step,
            content = if (sensitive) SENSITIVE else content(outcome, lastTabId),
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
        return done(call, block, state, outcome.handoff)
    }

    private fun content(o: ToolOutcome, tabId: Int?): String =
        listOf(o.lead, if (o.text.isEmpty()) "" else seal(o.text, tabId), o.note).filter { it.isNotEmpty() }.joinToString("\n")

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
        checkApproved(id)
        lastTabId = id
        // The panel peeks at the work tab while a tool uses it (B-02).
        handle.update { s -> (s.phase as? RunPhase.RunningTool)?.takeIf { it.callId == callId }?.let { s.copy(phase = it.copy(tabId = id)) } ?: s }
        return withContext(Dispatchers.Main.immediate) { browserPort.tab(id) }.also {
            // Before the tool acts on it: its requests from here on are this call's.
            if (workTabRef == null) callStart = SystemClock.uptimeMillis()
            workTabRef = it
        }
    }

    override fun checkApprovedPage(tab: BrowserTab) = checkApproved(tab.id)

    override fun markVisited(urls: Collection<String>) {
        visited += urls
    }

    private fun checkApproved(id: Int) {
        approvedPage?.let { (tabId, url) ->
            if (tabId != id || browserPort.tabUrl(id) != url) throw ToolError(PAGE_CHANGED)
        }
    }

    override suspend fun <T> hiddenTab(url: String, block: suspend (BrowserTab) -> T): T {
        var id: Int? = null
        try {
            // The id is stored inside NonCancellable: a cancel on the way out of withContext then still finds it in finally.
            withContext(NonCancellable) { id = browserPort.openHiddenTab(sessionId, url) } // Too many tabs throws (AN 1).
            val tabId = checkNotNull(id)
            visited += url
            val tab = withContext(Dispatchers.Main.immediate) { browserPort.tab(tabId) }
            val result = try {
                withContext(Dispatchers.Main.immediate) { block(tab) }
            } finally {
                // B-S4: every URL of the redirect chain is masked, not only the first and the last.
                visited += tab.mainRequestsSince(0)
            }
            // A redirect may have landed on, or passed through, a sensitive page (SE 2).
            browserPort.tabUrl(tabId)?.let(visited::add)
            visited.firstOrNull(browserPort::isSensitive)?.let { throw ToolError(sensitiveMessage(SensitiveUrls.hostOf(it).orEmpty())) }
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

    /**
     * B-05: [handoff]'s result once the user gave [tabId] back (tools-redesign 3-7). The executor checks a closed tab
     * and a sensitive site; [RequestHandoff.returned] lists the page.
     */
    suspend fun afterHandoff(handoff: ToolResultBlock, tabId: Int): ToolResultBlock {
        val url = browserPort.tabUrl(tabId) ?: return handoff.copy(content = HANDOFF_CLOSED, isError = false)
        if (browserPort.isSensitive(url)) {
            val host = SensitiveUrls.hostOf(url)
            return handoff.copy(content = "The user gave the tab back. It is on a sensitive site ($host), so its content is withheld.", isError = false)
        }
        visited.clear()
        visited += url
        val outcome = try {
            withContext(Dispatchers.Main.immediate) { RequestHandoff.returned(this@ToolExecutor, browserPort.tab(tabId)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: TabClosed) {
            return handoff.copy(content = HANDOFF_CLOSED, isError = false)
        } catch (e: PageError) {
            ToolOutcome("Its page could not be listed: ${e.message}", lead = RequestHandoff.GIVEN_BACK)
        } catch (e: Exception) {
            runCatching { Log.w("localchat", "handoff return failed", e) }
            ToolOutcome("", lead = RequestHandoff.GIVEN_BACK + hint("browse", " Its page could not be listed; call browse() to see it."))
        }
        return handoff.copy(content = content(outcome, tabId), isError = false, imageForModelId = outcome.imageForModelId)
    }

    private fun hint(tool: String, text: String) = if (tool in exposed) text else ""

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
        const val PAGE_CHANGED = "The page changed after approval, so the code was not run; ask again."
        const val REPEAT_NOTE = "[App note: this exact call failed twice. Change the approach, or answer with what you have.]"
    }
}
