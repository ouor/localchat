package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

/** browser-layers 3-5: the toolbar's back, forward and reload, then the page's elements once it is ready. */
object Navigate : Tool {
    override val name = "navigate"
    override val groups = setOf(ToolGroup.BROWSER)
    override val facts = ToolFacts(TargetRule.PAGE, browser = true, changesPage = true)
    override val labelRes = R.string.tool_navigate
    override val description =
        "Go back, go forward or reload in a tab, like the browser's toolbar. Lists the page's elements once it is ready."
    private val TO = listOf("back", "forward", "reload")

    override val parameters = params {
        string("to", "back, forward or reload.", required = true, enum = TO)
        tabParam()
    }

    override fun argSummary(args: JsonObject) = args.optString("to").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val to = args.string("to").trim().lowercase()
        if (to !in TO) throw ToolError("to must be back, forward or reload.")
        return onMain {
            val tab = ctx.tab()
            when (to) {
                "back" -> if (!tab.canGoBack) throw ToolError("Tab ${tab.id} has no earlier page.")
                "forward" -> if (!tab.canGoForward) throw ToolError("Tab ${tab.id} has no later page.")
            }
            val acted = ctx.act(tab) {
                when (to) {
                    "back" -> tab.goBack()
                    "forward" -> tab.goForward()
                    else -> tab.reload()
                }
            }
            // 2-8: without the back/forward cache a history step loads the page again.
            val reloaded = to != "reload" && tab.mainRequestsSince(acted.since).isNotEmpty()
            val note = if (reloaded) "(the page was reloaded; items it had loaded may be gone)" else ""
            val word = when (to) { "back" -> "Went back"; "forward" -> "Went forward"; else -> "Reloaded" }
            ctx.actionResult(tab, word, acted, tab.title, note = note)
        }
    }
}
