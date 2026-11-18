package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.tools.ApprovalMode
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
 * (architecture 7.4). POST submits, risky labels and frames ask the user first (B-S2); the target is located
 * again right before the tap and must still be the approved one (SE 7). The result is the page after the click.
 */
object Click : Tool {
    override val name = "click"
    override val groups = setOf(ToolGroup.BROWSER)
    override val approval = ApprovalMode.CONDITIONAL
    override val labelRes = R.string.tool_click
    override val description =
        "Click an element by its ref from the latest element list: links, buttons, checkboxes, radio buttons, tabs, menus. " +
            "Pay, order, delete, send and similar buttons, buttons that post a form, and links that vote, follow, subscribe, " +
            "log out and the like ask the user first; if they deny, " +
            "do not retry. Returns the page after the click."
    override val parameters = params { refParam() }

    override fun argSummary(args: JsonObject) = args.optString("ref").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val ref = args.string("ref")
        return onMain {
            val tab = ctx.workTab()
            var at = ctx.locate(tab, ref)
            if (at.disabled) throw ToolError("$ref is disabled.")
            val reason = IrreversibleAction.reasonForClick(at.info)
            val label = at.info.label()
            if (!ctx.guardActivate(name, tab, reason, label, at)) return@onMain ToolOutcome.DENIED
            if (reason != null) at = ctx.locate(tab, ref) // scroll back to it after the approval wait
            ctx.highlight(tab, at, R.string.browser_highlight_click)
            // SE 7: the page may have moved or swapped the element while the user decided or the outline showed.
            at = ctx.locate(tab, ref, scroll = false)
            if (at.info.label() != label || IrreversibleAction.reasonForClick(at.info) != reason) {
                val lead = "$ref changed before the click, so it was not clicked. Use a ref from the element list below."
                throw ToolError(lead, ctx.pageResult(tab, Readiness.READY, now()).copy(isError = true, lead = lead))
            }
            val target = at
            val acted = ctx.act(tab) {
                if (!(target.hit && tab.tap(target.cx, target.cy, target.innerWidth))) PageScript.callJson(tab, "click", refArgs(ref), retry = false)
            }
            val covered = if (target.hit) "" else " (it was covered by another element, so a script click was used)"
            ctx.actionResult(tab, "Clicked $ref$covered", acted, label, top = "$ref: $label")
        }
    }
}
