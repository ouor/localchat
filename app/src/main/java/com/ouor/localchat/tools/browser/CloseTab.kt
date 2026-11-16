package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optInt
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

object CloseTab : Tool {
    override val name = "close_tab"
    override val groups = setOf(ToolGroup.SEARCH, ToolGroup.BROWSER)
    override val labelRes = R.string.tool_close_tab
    override val description = "Close one of your tabs (default: your current tab). The user's tabs cannot be closed."
    override val parameters = params { tabIdParam() }

    override fun argSummary(args: JsonObject) = args.optInt("tabId")?.let { "tab $it" }.orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val tabId = args.optInt("tabId")
        return onMain {
            val tab = ctx.browser.ownedAiTab(ctx.sessionId, tabId)
            ctx.browser.close(tab.id)
            ToolOutcome("Closed tab ${tab.id}.")
        }
    }
}
