package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

object Hover : Tool {
    override val name = "hover"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_hover
    override val description = "Move the pointer over an element in your tab (opens hover menus and tooltips). Take a snapshot afterwards."
    override val parameters = params {
        targetParam()
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = args.optString("target").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val target = args.string("target")
        return onMain {
            val tab = ctx.tabFor(args)
            val at = locate(tab, target)
            ctx.highlight(tab, at, R.string.browser_highlight_hover)
            PageScript.callJson(tab, "hover", targetArgs(target))
            ToolOutcome("Hovered ${at.info.label()}.${dialogSuffix(tab)}", summary = at.info.label())
        }
    }
}
