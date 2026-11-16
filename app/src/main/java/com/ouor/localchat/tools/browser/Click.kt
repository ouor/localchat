package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.ApprovalRequest
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

/**
 * Native trusted tap at the element centre when elementFromPoint lands on it, JS click() otherwise
 * (architecture 7.4). POST submits and risky labels ask the user first (B-S2).
 */
object Click : Tool {
    override val name = "click"
    override val groups = setOf(ToolGroup.BROWSER)
    override val approval = ApprovalMode.CONDITIONAL
    override val labelRes = R.string.tool_click
    override val description =
        "Click an element in your tab. Submitting forms or pressing buttons like pay, order, delete or send " +
            "asks the user for approval first; if they deny, do not retry."
    override val parameters = params {
        targetParam()
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = args.optString("target").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val target = args.string("target")
        return onMain {
            val tab = ctx.tabFor(args)
            var at = locate(tab, target)
            if (at.disabled) throw ToolError("${at.info.label()} is disabled.")
            IrreversibleAction.reasonForClick(at.info)?.let { reason ->
                if (!ctx.requireApproval(ApprovalRequest(name, at.info.label(), tab.url, reason = reason))) return@onMain ToolOutcome.DENIED
                at = locate(tab, target) // the page may have moved while the user decided
            }
            ctx.highlight(tab, at, R.string.browser_highlight_click)
            val native = at.hit && tab.tap(at.cx, at.cy, at.innerWidth)
            if (!native) tolerateDialog(tab) { PageScript.callJson(tab, "click", targetArgs(target)) }
            ToolOutcome("Clicked ${at.info.label()}. ${settle(tab)}", summary = at.info.label())
        }
    }
}
