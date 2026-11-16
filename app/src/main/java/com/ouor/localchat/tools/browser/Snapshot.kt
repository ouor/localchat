package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.SnapshotResult
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

object Snapshot : Tool {
    override val name = "snapshot"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_snapshot
    override val description =
        "Accessibility snapshot of the page in your tab: roles, names, values and [ref=eN] for interactive " +
            "elements. Start here before clicking or typing, and take a new one after the page changes. " +
            "Much cheaper than a screenshot. Sensitive field values are always hidden."
    override val parameters = params { tabIdParam() }

    override fun argSummary(args: JsonObject) = ""

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome = onMain {
        val tab = ctx.tabFor(args)
        tab.awaitLoaded(LOAD_TIMEOUT)
        val r = PageScript.call<SnapshotResult>(tab, "snapshot")
        ToolOutcome(r.text + dialogSuffix(tab), summary = "${r.nodes} nodes")
    }
}
