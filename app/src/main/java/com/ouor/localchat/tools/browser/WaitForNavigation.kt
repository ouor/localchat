package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optIntIn
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

object WaitForNavigation : Tool {
    override val name = "wait_for_navigation"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_wait_for_navigation
    override val description = "Wait until the page in your tab finished loading. Default timeout 10 s, max 30 s."
    override val parameters = params {
        int("timeoutMs", "Timeout in ms (default 10000, max 30000).")
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = ""

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val timeout = (args.optIntIn("timeoutMs", 100..30_000) ?: 10_000).toLong()
        return onMain {
            val tab = ctx.tabFor(args)
            if (!tab.awaitLoaded(timeout)) throw ToolError("Still loading after ${timeout}ms. ${pageLine(tab)}")
            ToolOutcome("Loaded. ${pageLine(tab)}${dialogSuffix(tab)}", summary = tab.title)
        }
    }
}
