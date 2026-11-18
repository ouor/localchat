package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.script.PageScript
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

/**
 * browser-layers 3-5: a native tap at the element centre when elementFromPoint lands on it (after the user's finger is
 * up and a fling has stopped), a script click() otherwise. The ref is the DOM element itself, so the same ref is the
 * same element. The result is the page after the click.
 */
object Click : Tool {
    override val name = "click"
    override val groups = setOf(ToolGroup.BROWSER)
    override val facts = ToolFacts(TargetRule.REF, browser = true, changesPage = true)
    override val labelRes = R.string.tool_click
    override val description =
        "Click an element by its ref from the latest list: links, buttons, checkboxes, radio buttons, tabs, menus, options. " +
            "Returns the page after the click."
    override val parameters = params { refParam() }

    override fun argSummary(args: JsonObject) = args.optString("ref").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val ref = args.string("ref")
        return onMain {
            val tab = ctx.tab()
            val first = ctx.locate(tab, ref)
            if (first.disabled) throw ToolError("$ref is disabled.")
            val label = first.info.label()
            ctx.highlight(tab, first, R.string.browser_highlight_click)
            // The page may have moved while the outline showed.
            val at = ctx.locate(tab, ref, scroll = false)
            var scripted = !at.hit
            val acted = ctx.act(tab) {
                if (at.hit && tap(tab, at.cx, at.cy, at.innerWidth)) return@act
                scripted = true
                PageScript.callJson(tab, "click", refArgs(ref), retry = false)
            }
            val covered = if (scripted) " (it was covered or still moving, so a script click was used)" else ""
            ctx.actionResult(tab, "Clicked $ref$covered", acted, label, top = "$ref: $label")
        }
    }

    /** A native tap; false when it could not be made (the script clicks instead). MOVING waits for the fling once. */
    private suspend fun tap(tab: BrowserTab, x: Float, y: Float, w: Float): Boolean = when (tab.tap(x, y, w)) {
        BrowserTab.Tap.DONE -> true
        BrowserTab.Tap.FAILED -> false
        BrowserTab.Tap.MOVING -> false
    }
}
