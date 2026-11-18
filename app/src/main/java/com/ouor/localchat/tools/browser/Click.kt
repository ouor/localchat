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
 * up and a fling has stopped). Otherwise nothing is pressed and the result says what is at that point (QA B1). The ref is the DOM element itself, so the same ref is the
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
            val acted = ctx.act(tab) {
                // The page may have moved while the outline showed; a fling gets one more look.
                repeat(2) { attempt ->
                    val at = ctx.locate(tab, ref, scroll = false)
                    if (!at.hit) throw ToolError("click did not reach $ref: at that point is ${at.atPoint}.")
                    when (tab.tap(at.cx, at.cy, at.innerWidth)) {
                        BrowserTab.Tap.DONE -> return@act
                        BrowserTab.Tap.FAILED -> throw ToolError("click did not reach $ref: the tab's view did not take the tap (not attached or not laid out).")
                        BrowserTab.Tap.MOVING -> if (attempt == 1) throw ToolError("click did not reach $ref: the page kept scrolling.")
                    }
                }
            }
            ctx.actionResult(tab, "Clicked $ref", acted, label, top = "$ref: $label")
        }
    }
}
