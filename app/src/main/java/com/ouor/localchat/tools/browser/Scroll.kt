package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.ScrollResult
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optInt
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object Scroll : Tool {
    override val name = "scroll"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_scroll
    override val description =
        "Scroll the page in your tab, or scroll an element into view (target without direction), or scroll inside " +
            "a scrollable element (target with direction)."
    override val parameters = params {
        string("direction", "up, down, left or right (default down when no target).", enum = DIRECTIONS)
        int("amount", "Pixels to scroll (default 600).")
        string("target", "$TARGET Optional.")
        tabIdParam()
    }

    private val DIRECTIONS get() = listOf("up", "down", "left", "right")

    override fun argSummary(args: JsonObject) = args.optString("target") ?: args.optString("direction") ?: "down"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val target = args.optString("target")?.takeIf { it.isNotBlank() }
        val direction = args.optString("direction")?.lowercase() ?: if (target == null) "down" else null
        if (direction != null && direction !in DIRECTIONS) throw ToolError("direction must be up, down, left or right.")
        val amount = (args.optInt("amount") ?: 600).coerceIn(1, 100_000)
        val (dx, dy) = when (direction) {
            "up" -> 0 to -amount
            "down" -> 0 to amount
            "left" -> -amount to 0
            "right" -> amount to 0
            else -> 0 to 0
        }
        return onMain {
            val tab = ctx.tabFor(args)
            if (target != null) ctx.highlight(tab, locate(tab, target, scroll = direction == null), R.string.browser_highlight_scroll)
            val r = PageScript.call<ScrollResult>(tab, "scroll", buildJsonObject {
                target?.let { put("target", it) }
                put("dx", dx)
                put("dy", dy)
            })
            val inner = r.elementY?.let { " Element scrolled to y=$it of ${r.elementMaxY}." }.orEmpty()
            ToolOutcome("Page at y=${r.y} of ${r.maxY}.$inner", summary = argSummary(args))
        }
    }
}
