package com.ouor.localchat.chat.run

import android.util.Log
import com.ouor.localchat.browser.Asleep
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.BrowserRefusal
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Handled
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.TabClosed
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.chat.AttachmentPort
import com.ouor.localchat.chat.BrowserPort
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.core.model.ToolStatus
import com.ouor.localchat.policy.CallFacts
import com.ouor.localchat.policy.Policies
import com.ouor.localchat.policy.PolicyCall
import com.ouor.localchat.policy.Verdict
import com.ouor.localchat.tools.ChatTabs
import com.ouor.localchat.tools.ReadCursor
import com.ouor.localchat.tools.Targets
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.ToolResultFormat
import com.ouor.localchat.tools.UserStep
import com.ouor.localchat.tools.browser.AskUser
import com.ouor.localchat.tools.browser.Read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Provider

/**
 * Runs one turn's tool calls in the order of browser-layers 4-1 and is the tools' [ToolContext]: the hidden tools are
 * unknown, the target tab is resolved ([Targets]), locked and woken, the policies' `before` may refuse or ask, a new tab
 * is opened only after that, the tool runs, every outcome (an exception too) goes through `after`, and the result is
 * stored as [ToolResultFormat] cuts it. One instance per turn so citation numbers stay stable within the message.
 */
class ToolExecutor(
    override val sessionId: String,
    /** Lazy: only browser tools touch the engine (JVM tests have none). */
    private val engine: Provider<BrowserEngine>,
    private val attachments: AttachmentPort,
    private val browserPort: BrowserPort,
    private val policies: Policies,
    private val handle: RunRegistry.RunHandle,
    tools: List<Tool>,
    existingSources: List<Source>,
    override val contextWindow: Int? = null,
    override val vision: Boolean = false,
    /** This chat's tabs memory; it outlives the turn. */
    override val chat: ChatTabs,
    /** The read tool link prefetch runs, exposed or not (the catalog's; JVM tests pass a fake). */
    private val readTool: Tool = Read,
    /** Shows a call's state change (AWAITING_APPROVAL ↔ RUNNING) in the reply. */
    private val onState: (callKey: String, ToolCallState) -> Unit,
) : ToolContext {

    data class Result(val block: ToolResultBlock, val state: ToolCallState, val userStep: UserStep?)

    override val browser: BrowserEngine get() = engine.get()

    override val readCursors: MutableMap<Int, ReadCursor> get() = chat.readCursors

    /** Tools this turn may run; only ever narrowed ([narrow]). */
    var tools: List<Tool> = tools
        private set

    /** Names dropped by [narrow]: a call to one is answered [TOOL_OFF], not "Unknown tool". */
    private val turnedOff = HashSet<String>()

    /** Audit #10: keeps only the tools named in [keep] (a switch was turned off mid-run). */
    fun narrow(keep: Set<String>) {
        tools.filter { it.name !in keep }.mapTo(turnedOff) { it.name }
        tools = tools.filter { it.name in keep }
    }

    fun isOff(name: String) = name in turnedOff

    private val sources = existingSources.associateBy { it.url }.toMutableMap()

    private var callId: String? = null
    private var tabId: Int? = null

    override val target: BrowserTab? get() = tabId?.let(browserPort::tab)

    suspend fun execute(call: ToolCallBlock): Result {
        callId = call.callId
        tabId = null
        val tool = tools.firstOrNull { it.name == call.name && it.name !in policies.hiddenTools() }
            ?: return failed(call, if (call.name in turnedOff) TOOL_OFF else unknown(call.name))
        val args = try {
            Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            return failed(call, "Invalid arguments: not a JSON object")
        }
        handle.update { it.copy(phase = RunPhase.RunningTool(call.callId, call.name, null)) }
        return run(call, tool, args, call.key)
    }

    /** LinkPrefetcher (A-m10): a link in a question read through the same chain as a read call; null when it failed. */
    suspend fun prefetchRead(url: String): ToolOutcome? {
        val args = buildJsonObject { put("url", JsonPrimitive(url)) }
        val call = ToolCallBlock(Ids.new(), readTool.name, args.toString(), 0, ToolCallState.RUNNING, 0)
        callId = call.callId
        tabId = null
        val r = runChain(call, readTool, args, call.key)
        return r.takeUnless { it.isError }
    }

    /** ask_user's result once the user tapped Done: the page as they left it, through the chain like look. */
    suspend fun afterUserStep(call: ToolCallBlock, step: UserStep): ToolResultBlock {
        callId = call.callId
        tabId = null
        val args = buildJsonObject { put("tab", JsonPrimitive(step.tabId.toString())) }
        val done = object : Tool by AskUser {
            override suspend fun run(args: JsonObject, ctx: ToolContext) = AskUser.done(ctx)
        }
        return block(call, runChain(call, done, args, call.key)).block
    }

    private suspend fun run(call: ToolCallBlock, tool: Tool, args: JsonObject, key: String): Result =
        block(call, runChain(call, tool, args, key))

    /** 4-1 steps 2–8 for one call. */
    private suspend fun runChain(call: ToolCallBlock, tool: Tool, args: JsonObject, key: String): ToolOutcome {
        val facts = tool.facts
        val startedAt = now()
        val target = if (!facts.browser) Targets.Target.None else Targets.resolve(facts.target, args, browserPort.tabs(), browserPort.front(), chat)
        var tab: BrowserTab? = null
        var held = false
        var woke: Asleep? = null
        var opened = false
        val info: () -> TabInfo? = { tabId?.let { id -> browserPort.tabs().firstOrNull { it.id == id } } }
        fun policyCall() = PolicyCall(sessionId, tool.name, facts, args, startedAt, info(), ::describe, ::captureElement)
        if (target is Targets.Target.Error) return after(policyCall(), ToolOutcome(lead = target.message, isError = true), startedAt, opened)
        try {
            if (target is Targets.Target.Tab) {
                tabId = target.id
                tab = onMain { browserPort.tab(target.id) }
                tab.lock.lock()
                held = true
                woke = tab.asleep
                if (tab.wake()) tab.awaitReady(now())
            }
            // 4. before, in order; a confirmation waits without the lock so other chats are not held up (A-m9).
            when (val v = policies.before(policyCall())) {
                Verdict.Allow -> Unit
                is Verdict.Refuse -> return ToolOutcome(lead = v.message, isError = true, refused = true)
                is Verdict.Confirm -> {
                    if (held) {
                        tab?.lock?.unlock()
                        held = false
                    }
                    val allowed = try {
                        ask(key, v)
                    } finally {
                        tab?.let { it.lock.lock(); held = true }
                    }
                    if (!allowed) return ToolOutcome.DENIED
                }
            }
            // A new tab only once the call may go on: a refused call opens nothing.
            if (target is Targets.Target.NewTab) {
                val id = browserPort.open()
                tabId = id
                opened = true
                chat.opened += id
                tab = onMain { browserPort.tab(id) }
                tab.lock.lock()
                held = true
            }
            tab?.let { t ->
                browserPort.setActivity(t.id, ToolActivity(sessionId, tool.name))
                handle.update { s -> (s.phase as? RunPhase.RunningTool)?.takeIf { it.callId == callId }?.let { s.copy(phase = it.copy(tabId = t.id)) } ?: s }
            }
            val outcome = outcomeOf(call) { tool.run(args, this) }
            return after(policyCall(), withFacts(outcome, tab, startedAt, opened, woke), startedAt, opened)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // TabClosed while locking or waking, and anything else before the tool ran.
            return after(policyCall(), outcomeOfError(call, e), startedAt, opened)
        } finally {
            tab?.let { t ->
                browserPort.setActivity(t.id, null)
                if (held) t.lock.unlock()
                chat.used(t.id, System.currentTimeMillis())
            }
        }
    }

    /** 4-1 step 6: `after` folds over every outcome that ran, failures included. */
    private fun after(call: PolicyCall, outcome: ToolOutcome, startedAt: Long, opened: Boolean): ToolOutcome {
        val id = tabId
        val tab = id?.let { runCatching { browserPort.tab(it) }.getOrNull() }
        val events = tab?.eventsSince(startedAt).orEmpty()
        val popups = events.mapNotNull { (it.event as? BrowserEvent.Popup)?.newTabId }
        val facts = CallFacts(
            tabAfter = id?.let { i -> browserPort.tabs().firstOrNull { it.id == i } },
            opened = listOfNotNull(id.takeIf { opened }) + popups,
            mainRequests = tab?.mainRequestsSince(startedAt).orEmpty(),
            cookieHeader = browserPort::cookieHeader,
            events = events,
        )
        return policies.after(call, facts, outcome)
    }

    /** 3-3: facts the app adds to the lead: a new tab, a woken tab, the user's hand in the same tab meanwhile. */
    private fun withFacts(o: ToolOutcome, tab: BrowserTab?, startedAt: Long, opened: Boolean, woke: Asleep?): ToolOutcome {
        tab ?: return o
        val before = listOfNotNull(
            "Opened tab ${tab.id}.".takeIf { opened },
            woke?.let { "(tab ${tab.id} was asleep (${if (it == Asleep.CRASHED) "crash" else "memory"}) and was reloaded; its scroll position and typed input are gone)" },
        )
        val after = "(the user also used this tab meanwhile)".takeIf { tab.lastUserInputAt >= startedAt }
        val lead = (before + o.lead + listOfNotNull(after)).filter { it.isNotEmpty() }.joinToString(" ")
        return o.copy(lead = lead)
    }

    private suspend fun outcomeOf(call: ToolCallBlock, run: suspend () -> ToolOutcome): ToolOutcome = try {
        run()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        outcomeOfError(call, e)
    }

    /** S1: an exception is a failed outcome with a factual lead, so it goes through the chain too. */
    private fun outcomeOfError(call: ToolCallBlock, e: Exception): ToolOutcome = when (e) {
        is ToolError -> e.page ?: ToolOutcome(lead = e.message ?: TOOL_FAILED, isError = true)
        is BrowserRefusal -> ToolOutcome(lead = e.message ?: TOOL_FAILED, isError = true)
        // May carry page text (alert(), option labels, a thrown cookie): inside the boundary.
        is PageError -> ToolOutcome(
            page = e.message ?: TOOL_FAILED, isError = true,
            note = if (e.message == PageScript.NOT_RUN_AGAIN) "Call look() to see where it is now." else "",
        )
        is TabClosed -> ToolOutcome(lead = TAB_CLOSED, isError = true)
        else -> {
            // FB3 eng m5: an unplanned exception's text is not for the model or the user; it goes to the log only.
            runCatching { Log.w("localchat", "tool ${call.name} failed", e) } // JVM tests have no android.util.Log
            ToolOutcome(lead = TOOL_FAILED, isError = true)
        }
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

    private fun failed(call: ToolCallBlock, lead: String) =
        Result(ToolResultBlock(call.callId, call.step, page = "", isError = true, lead = lead), ToolCallState.FAILED, null)

    private fun unknown(name: String): String {
        val now = tools.map { it.name }.filter { it !in policies.hiddenTools() }
        return "Unknown tool: $name. " + if (now.isEmpty()) "No tools can be called now." else "Tools you can call now: ${now.joinToString(", ")}."
    }

    private suspend fun ask(key: String, v: Verdict.Confirm): Boolean {
        onState(key, ToolCallState.AWAITING_APPROVAL)
        val answer = handle.pause(Pause.Approval(key, v.request)) as Resolution.ApprovalAnswer
        onState(key, ToolCallState.RUNNING)
        return answer.allow
    }

    /** PolicyCall.describe: the ref on the call's tab, without scrolling; null when gone. */
    private suspend fun describe(ref: String): Located? {
        val id = tabId ?: return null
        return runCatching {
            onMain { PageScript.call<Located>(browserPort.tab(id), "locate", buildJsonObject { put("target", JsonPrimitive(ref)); put("scroll", JsonPrimitive(false)) }) }
        }.getOrNull()
    }

    private suspend fun captureElement(ref: String) = describe(ref)?.let { at ->
        tabId?.let { id -> onMain { browserPort.tab(id).captureElement(at.rect, at.innerWidth) } }
    }

    // ToolContext

    override fun status(s: ToolStatus?, engine: String?) {
        handle.update { st ->
            (st.phase as? RunPhase.RunningTool)?.takeIf { it.callId == callId }?.let { st.copy(phase = it.copy(status = s, engine = engine)) } ?: st
        }
    }

    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String =
        attachments.saveArtifact(name, mime, bytes)

    override fun numberSource(url: String, title: String, snippet: String?): Int =
        sources.getOrPut(url) { Source(sources.size + 1, url, title, snippet) }.n

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private fun now() = browserPort.now()

    companion object {
        const val TOOL_OFF = "The user turned this tool off."
        const val TOOL_FAILED = "Tool failed unexpectedly (an app error). Do not repeat the same call; continue another way."
        const val TAB_CLOSED = "The tab closed during the call (the page crashed or the user closed it)."
    }
}
