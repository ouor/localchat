package com.ouor.localchat.agent

import com.ouor.localchat.browser.Asleep
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.BrowserRefusal
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.TabClosed
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.browser.ToolStatus
import com.ouor.localchat.browser.script.CssRect
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.policy.ApprovalRequest
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
import java.util.UUID

/** What the runner asks of the app around it: these differ per app. */
interface RunnerHost {
    /** A policy wants the user's yes ([Verdict.Confirm]); true = go on. The call's tab is unlocked while this waits. */
    suspend fun confirm(callId: String, request: ApprovalRequest): Boolean

    /**
     * The running call: what it waits for ([status], [engine]) and its tab. [BrowserToolRunner.run] reports once with
     * all three null when the call starts (after the unknown-tool and argument checks).
     */
    fun status(callId: String, status: ToolStatus?, engine: String?, tabId: Int?)

    /** Stores a tool artifact (a page picture) and returns its id. */
    suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String

    /** Citation number for [url], stable within the current message. */
    fun numberSource(url: String, title: String, snippet: String?): Int
}

/**
 * Runs tool calls in the order of browser-layers 4-1 and is the tools' [ToolContext]: the hidden tools are unknown, the
 * target tab is resolved ([Targets]), locked and woken, the policies' `before` may refuse or ask, a new tab is opened
 * only after that, the tool runs, every outcome (an exception too) goes through `after`. One instance per turn: the
 * host numbers sources per message. Calls run one at a time.
 */
class BrowserToolRunner(
    override val sessionId: String,
    /** Lazy: only browser tools touch the engine (JVM tests have none). */
    private val engine: () -> BrowserEngine,
    private val port: BrowserPort,
    private val policies: Policies,
    private val host: RunnerHost,
    tools: List<Tool>,
    /** This chat's tabs memory; it outlives the turn. */
    override val chat: ChatTabs,
    override val contextWindow: Int? = null,
    override val vision: Boolean = false,
    /** The read tool [prefetchRead] runs, exposed or not. */
    private val readTool: Tool = Read,
    /** An unplanned exception: its text is not for the model or the user, only for the app's log (FB3 eng m5). */
    private val onUnexpected: (String, Throwable) -> Unit = { _, _ -> },
) : ToolContext {

    override val browser: BrowserEngine get() = engine()

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

    private var callId: String? = null
    private var tabId: Int? = null

    override val target: BrowserTab? get() = tabId?.let(port::tab)

    /** One call: name and JSON arguments in, outcome out. An exception becomes an outcome that still goes through `after`. */
    suspend fun run(callId: String, name: String, argumentsJson: String): ToolOutcome {
        this.callId = callId
        tabId = null
        val tool = tools.firstOrNull { it.name == name && it.name !in policies.hiddenTools() }
            ?: return ToolOutcome(lead = if (name in turnedOff) TOOL_OFF else unknown(name), isError = true)
        val args = try {
            Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            return ToolOutcome(lead = "Invalid arguments: not a JSON object", isError = true)
        }
        host.status(callId, null, null, null)
        return runChain(tool, args)
    }

    /** LinkPrefetcher (A-m10): a link in a question read through the same chain as a read call; null when it failed. */
    suspend fun prefetchRead(url: String): ToolOutcome? {
        callId = UUID.randomUUID().toString()
        tabId = null
        return runChain(readTool, buildJsonObject { put("url", JsonPrimitive(url)) }).takeUnless { it.isError }
    }

    /** ask_user's result once the user tapped Done: the page as they left it, through the chain like look. */
    suspend fun afterUserStep(callId: String, step: UserStep): ToolOutcome {
        this.callId = callId
        tabId = null
        val done = object : Tool by AskUser {
            override suspend fun run(args: JsonObject, ctx: ToolContext) = AskUser.done(ctx)
        }
        return runChain(done, buildJsonObject { put("tab", JsonPrimitive(step.tabId.toString())) })
    }

    /** 4-1 steps 2–8 for one call. */
    private suspend fun runChain(tool: Tool, args: JsonObject): ToolOutcome {
        val facts = tool.facts
        val startedAt = now()
        val target = if (!facts.browser) Targets.Target.None else Targets.resolve(facts.target, args, port.tabs(), port.front(), chat)
        var tab: BrowserTab? = null
        var held = false
        var woke: Asleep? = null
        var opened = false
        val info: () -> TabInfo? = { tabId?.let { id -> port.tabs().firstOrNull { it.id == id } } }
        fun policyCall() = PolicyCall(sessionId, tool.name, facts, args, startedAt, info(), ::describe, ::captureElement)
        if (target is Targets.Target.Error) return after(policyCall(), ToolOutcome(lead = target.message, isError = true), startedAt, opened)
        try {
            if (target is Targets.Target.Tab) {
                tabId = target.id
                tab = onMain { port.tab(target.id) }
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
                        host.confirm(callId!!, v.request)
                    } finally {
                        tab?.let { it.lock.lock(); held = true }
                    }
                    if (!allowed) return ToolOutcome.DENIED
                }
            }
            // A new tab only once the call may go on: a refused call opens nothing.
            if (target is Targets.Target.NewTab) {
                val id = port.open()
                tabId = id
                opened = true
                chat.opened += id
                tab = onMain { port.tab(id) }
                tab.lock.lock()
                held = true
            }
            tab?.let { t ->
                port.setActivity(t.id, ToolActivity(sessionId, tool.name))
                host.status(callId!!, null, null, t.id)
            }
            val outcome = outcomeOf(tool) { tool.run(args, this) }
            return after(policyCall(), withFacts(outcome, tab, startedAt, opened, woke), startedAt, opened)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // TabClosed while locking or waking, and anything else before the tool ran.
            return after(policyCall(), outcomeOfError(tool, e), startedAt, opened)
        } finally {
            tab?.let { t ->
                port.setActivity(t.id, null)
                if (held) t.lock.unlock()
                chat.used(t.id, System.currentTimeMillis())
            }
        }
    }

    /** 4-1 step 6: `after` folds over every outcome that ran, failures included. */
    private fun after(call: PolicyCall, outcome: ToolOutcome, startedAt: Long, opened: Boolean): ToolOutcome {
        val id = tabId
        val tab = id?.let { runCatching { port.tab(it) }.getOrNull() }
        val events = tab?.eventsSince(startedAt).orEmpty()
        val popups = events.mapNotNull { (it.event as? BrowserEvent.Popup)?.newTabId }
        val facts = CallFacts(
            tabAfter = id?.let { i -> port.tabs().firstOrNull { it.id == i } },
            opened = listOfNotNull(id.takeIf { opened }) + popups,
            mainRequests = tab?.mainRequestsSince(startedAt).orEmpty(),
            cookieHeader = port::cookieHeader,
            events = events,
        )
        return policies.after(call, facts, outcome)
    }

    /** 3-3: facts the runner adds to the lead: a new tab, a woken tab, the user's hand in the same tab meanwhile. */
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

    private suspend fun outcomeOf(tool: Tool, run: suspend () -> ToolOutcome): ToolOutcome = try {
        run()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        outcomeOfError(tool, e)
    }

    /** S1: an exception is a failed outcome with a factual lead, so it goes through the chain too. */
    private fun outcomeOfError(tool: Tool, e: Exception): ToolOutcome = when (e) {
        is ToolError -> e.page ?: ToolOutcome(lead = e.message ?: TOOL_FAILED, isError = true)
        is BrowserRefusal -> ToolOutcome(lead = e.message ?: TOOL_FAILED, isError = true)
        // May carry page text (alert(), option labels, a thrown cookie): inside the boundary.
        is PageError -> ToolOutcome(
            page = e.message ?: TOOL_FAILED, isError = true,
            note = if (e.message == PageScript.NOT_RUN_AGAIN) "Call look() to see where it is now." else "",
        )
        is TabClosed -> ToolOutcome(lead = TAB_CLOSED, isError = true)
        else -> {
            onUnexpected("tool ${tool.name} failed", e)
            ToolOutcome(lead = TOOL_FAILED, isError = true)
        }
    }

    private fun unknown(name: String): String {
        val now = tools.map { it.name }.filter { it !in policies.hiddenTools() }
        return "Unknown tool: $name. " + if (now.isEmpty()) "No tools can be called now." else "Tools you can call now: ${now.joinToString(", ")}."
    }

    /** PolicyCall.describe: the ref on the call's tab, without scrolling; null when gone. */
    private suspend fun describe(ref: String): Located? {
        val id = tabId ?: return null
        return runCatching {
            onMain { PageScript.call<Located>(port.tab(id), "locate", buildJsonObject { put("target", JsonPrimitive(ref)); put("scroll", JsonPrimitive(false)) }) }
        }.getOrNull()
    }

    private suspend fun captureElement(ref: String) = describe(ref)?.let { at ->
        // QA B6: the form around it (or a band of the screen), so the sheet shows what is ordered, not only the button.
        val area = at.formRect ?: CssRect(0f, at.rect.top - PREVIEW_BAND, at.innerWidth, at.rect.height + 2 * PREVIEW_BAND)
        tabId?.let { id -> onMain { port.tab(id).captureElement(area, at.innerWidth) } }
    }

    // ToolContext

    override fun status(s: ToolStatus?, engine: String?) {
        callId?.let { host.status(it, s, engine, tabId) }
    }

    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String = host.saveArtifact(name, mime, bytes)

    override fun numberSource(url: String, title: String, snippet: String?): Int = host.numberSource(url, title, snippet)

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    private fun now() = port.now()

    companion object {
        const val TOOL_OFF = "The user turned this tool off."
        /** CSS px above and below the element when it has no form (QA B6). */
        private const val PREVIEW_BAND = 160f
        const val TOOL_FAILED = "Tool failed unexpectedly (an app error). Do not repeat the same call; continue another way."
        const val TAB_CLOSED = "The tab closed during the call (the page crashed or the user closed it)."
    }
}
