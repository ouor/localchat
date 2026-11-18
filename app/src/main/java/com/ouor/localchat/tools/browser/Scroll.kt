package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.ScrollResult
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * browser-layers 3-5: one screen down or up, of the page or of a [scrollable] element, then what is on screen now, text
 * included (S12). The act wait covers what an infinite list loads; a page that did not move is given one longer wait
 * before it counts as the end, so a late load is no false end (S16).
 */
object Scroll : Tool {
    override val name = "scroll"
    override val groups = setOf(ToolGroup.BROWSER)

    /** PAGE: with ref, the ref's tab wins (Targets rule 1). */
    override val facts = ToolFacts(TargetRule.PAGE, browser = true, changesPage = true)
    override val labelRes = R.string.tool_scroll
    override val description =
        "Scroll a page one screen down (default) or up; with ref, scroll inside that element (lines marked [scrollable], " +
            "such as a chat pane or a map's result list). Waits for anything the page loads, then lists what is now on screen, " +
            "text included, and says how far down it is. On a feed, call it again to see more."
    override val parameters = params {
        string("direction", "down (default) or up.", enum = listOf("down", "up"))
        refParam("A [scrollable] element's ref. Omit for the page.", required = false)
        tabParam()
    }

    /** S16 (추정): how long a page that did not move gets to load more before it counts as the end. */
    internal const val END_WAIT_MS = 1_500L

    override fun argSummary(args: JsonObject) = listOfNotNull(args.optString("direction"), args.optString("ref")).joinToString(" ")

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val dir = args.optString("direction")?.trim()?.lowercase()?.ifEmpty { null } ?: "down"
        if (dir != "down" && dir != "up") throw ToolError("direction must be down or up.")
        val ref = args.optString("ref")?.trim()?.takeIf { it.isNotEmpty() }
        return onMain {
            val tab = ctx.tab()
            var r: ScrollResult? = null
            val acted = ctx.act(tab) {
                r = scroll(tab, dir, ref)
                if (r?.moved == false) {
                    delay(END_WAIT_MS)
                    r = scroll(tab, dir, ref)
                }
            }
            val s = r ?: throw ToolError("The page did not scroll.")
            // QA B7: where it is once the page settled; when the page grew the % can go back, so the result says it grew.
            val at = runCatching { scroll(tab, "none", ref) }.getOrNull() ?: s
            val where = if (at.max <= 0) 100 else (at.y * 100 / at.max).coerceIn(0, 100)
            val grew = if (at.max > s.maxBefore) " (the page grew longer: ${s.maxBefore + at.docHeight - at.max} → ${at.docHeight} px)" else ""
            val lead = when {
                !s.moved && dir == "down" -> "Reached the bottom; nothing more loaded."
                !s.moved -> "Already at the top."
                else -> "Scrolled $dir" + (if (acted.change == Change.UPDATED || acted.change == Change.NEW_PAGE) " → the page loaded more" else "") +
                    "; $where% down ${if (ref != null) "the element" else "the page"}$grew."
            } + (s.box?.takeIf { ref == null }?.let { " (scrolled the page's main pane, $it)" } ?: "")
            ctx.actionResult(tab, lead, acted, "$dir $where%", screen = true, change = false)
        }
    }

    private suspend fun scroll(tab: BrowserTab, dir: String, ref: String?): ScrollResult =
        PageScript.call(tab, "scroll", buildJsonObject { put("dir", dir); ref?.let { put("target", it) } }, retry = false)
}
