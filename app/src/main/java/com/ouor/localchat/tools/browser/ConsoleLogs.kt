package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optIntIn
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

object ConsoleLogs : Tool {
    override val name = "console_logs"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_console_logs
    override val description = "Recent console messages of your tab (newest last, up to ${BrowserTab.CONSOLE_MAX})."
    override val parameters = params {
        int("limit", "How many of the newest lines (default 50, max ${BrowserTab.CONSOLE_MAX}).")
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = ""

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val limit = args.optIntIn("limit", 1..BrowserTab.CONSOLE_MAX) ?: 50
        return onMain {
            val lines = ctx.tabFor(args).consoleLines().takeLast(limit)
            ToolOutcome(lines.joinToString("\n").ifEmpty { "No console messages." }, summary = "${lines.size} lines")
        }
    }
}
