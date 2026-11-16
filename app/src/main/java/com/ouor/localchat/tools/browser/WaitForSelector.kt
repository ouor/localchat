package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.QueryResult
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optIntIn
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Polls agent.js `query` every 100 ms (no page-to-app channel, architecture 7.2). */
object WaitForSelector : Tool {
    override val name = "wait_for_selector"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_wait_for_selector
    override val description = "Wait until an element is visible (default), attached, or hidden/gone in your tab. Default timeout 10 s, max 30 s."
    override val parameters = params {
        targetParam()
        string("state", "visible (default), attached or hidden.", enum = STATES)
        int("timeoutMs", "Timeout in ms (default 10000, max 30000).")
        tabIdParam()
    }

    private val STATES get() = listOf("visible", "attached", "hidden")

    override fun argSummary(args: JsonObject) = args.optString("target").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val target = args.string("target")
        val state = args.optString("state")?.lowercase() ?: "visible"
        if (state !in STATES) throw ToolError("state must be visible, attached or hidden.")
        val timeout = (args.optIntIn("timeoutMs", 100..30_000) ?: 10_000).toLong()
        return onMain {
            val tab = ctx.tabFor(args)
            val deadline = System.currentTimeMillis() + timeout
            while (!met(tab, target, state)) {
                if (System.currentTimeMillis() > deadline) {
                    throw ToolError("Timed out after ${timeout}ms waiting for $target to be $state." + dialogSuffix(tab))
                }
                delay(PageScript.POLL_MS)
            }
            ToolOutcome("$target is $state.${dialogSuffix(tab)}")
        }
    }

    private suspend fun met(tab: BrowserTab, target: String, state: String): Boolean {
        if (tab.loading.value) return false
        val q = try {
            PageScript.call<QueryResult>(tab, "query", targetArgs(target))
        } catch (e: PageError) {
            if (e.message.orEmpty().startsWith(PageScript.NOT_RUN)) return false // between documents: "not yet"
            throw e
        }
        if (q.stale && state != "hidden") throw ToolError("stale ref $target: take a new snapshot")
        return when (state) {
            "visible" -> q.visible
            "attached" -> q.attached
            else -> !q.visible
        }
    }
}
