package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Targets
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject
import com.ouor.localchat.tools.optString

/** browser-layers 3-5: closes a tab; says where the next call without tab goes. */
object CloseTab : Tool {
    override val name = "close_tab"
    override val groups = setOf(ToolGroup.BROWSER)
    override val facts = ToolFacts(TargetRule.NONE, browser = true, changesPage = false)
    override val labelRes = R.string.tool_close_tab
    override val description = "Close a tab. Close tabs you opened once you no longer need them; leave the others open unless asked."
    override val parameters = params { string("tab", "Number of the tab to close.", required = true) }

    override fun argSummary(args: JsonObject) = args.optString("tab").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = args.string("tab").trim()
        val id = raw.removePrefix("tab").trim().toIntOrNull() ?: throw ToolError("tab must be a tab number such as 3.")
        val b = ctx.browser
        if (b.tabs.value.none { it.id == id }) throw ToolError("Tab $id is not open." + Targets.listed(b.tabs.value))
        onMain { b.close(id) }
        ctx.chat.opened -= id
        val left = b.tabs.value.filter { it.id != id }
        val next = Targets.defaultTab(left, b.front.value?.takeIf { it.tabId != id }, ctx.chat)
        val where = next?.let { n ->
            " Next calls without tab go to tab $n" + (if (n == b.shown.value) " (on the user's screen)." else ".")
        } ?: ""
        val count = if (left.size == 1) "1 tab" else "${left.size} tabs"
        return ToolOutcome(lead = "Closed tab $id; $count open.$where", summary = "$id")
    }
}
