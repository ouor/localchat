package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.TapCheck
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * browser-layers 3-5: a native tap at the element centre when elementFromPoint lands on it (after the user's finger is
 * up, a fling has stopped and the current layout is drawn). Otherwise nothing is pressed and the result says what is
 * at that point (QA B1); a tap the page receives on another element is stopped (QA R-B1b). In a tab that is not on
 * screen the element is then pressed by script and the result says so (re-QA 2); on screen the stop is the result.
 * The ref is the DOM element itself, so the same ref is the same element. The result is the page after the click.
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

    /**
     * What the armed tap reached (QA R-B1b): the page's own first touch target. Another element was stopped before the
     * page saw it, so nothing was pressed by the tap. On screen that is the result (the user sees the same); in a tab
     * that is not on screen the element is pressed by script instead (re-QA 2), and true says so. A new document
     * means the tap acted.
     */
    private suspend fun reached(ctx: ToolContext, tab: BrowserTab, ref: String): Boolean {
        val end = now() + CHECK_MS
        while (true) {
            val c = runCatching { PageScript.call<TapCheck>(tab, "tapCheck", retry = false) }.getOrNull() ?: return false
            if (!c.armed) return false
            if (c.seen || now() > end) {
                disarm(tab)
                if (!c.seen) throw ToolError("click did not reach $ref: the page got no touch; nothing was pressed.")
                if (c.hit) return false
                if (ctx.browser.shown.value == tab.id) throw ToolError("the tap would have landed on ${c.at} instead of $ref; nothing was pressed.")
                PageScript.callJson(tab, "press", refArgs(ref), retry = false)
                return true
            }
            delay(50)
        }
    }

    private suspend fun disarm(tab: BrowserTab) {
        runCatching { PageScript.callJson(tab, "disarmTap", retry = false) }
    }

    private const val CHECK_MS = 1_000L

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val ref = args.string("ref")
        return onMain {
            val tab = ctx.tab()
            val first = ctx.locate(tab, ref)
            if (first.disabled) throw ToolError("$ref is disabled.")
            val label = first.info.label()
            ctx.highlight(tab, first, R.string.browser_highlight_click)
            var byScript = false
            val acted = ctx.act(tab) {
                // The page may have moved while the outline showed; a fling gets one more look.
                repeat(2) { attempt ->
                    val at = ctx.locate(tab, ref, scroll = false)
                    if (!at.hit) throw ToolError("click did not reach $ref: at that point is ${at.atPoint}.")
                    val tap = try {
                        tab.tap(at.cx, at.cy, at.innerWidth) { PageScript.callJson(tab, "armTap", refArgs(ref), retry = false) }
                    } catch (e: Exception) {
                        disarm(tab); throw e
                    }
                    when (tap) {
                        BrowserTab.Tap.DONE -> { byScript = reached(ctx, tab, ref); return@act }
                        BrowserTab.Tap.FAILED -> { disarm(tab); throw ToolError("click did not reach $ref: the tab's view did not take the tap (not attached or not laid out).") }
                        BrowserTab.Tap.NOT_DRAWN -> { disarm(tab); throw ToolError("click did not reach $ref: the page did not draw its current layout within 1 s, so nothing was pressed.") }
                        BrowserTab.Tap.MOVING -> { disarm(tab); if (attempt == 1) throw ToolError("click did not reach $ref: the page kept scrolling.") }
                    }
                }
            }
            val lead = if (byScript) "Clicked $ref (pressed by script: the tap could not reach it in a tab that is not on screen)" else "Clicked $ref"
            ctx.actionResult(tab, lead, acted, label, top = "$ref: $label")
        }
    }
}
