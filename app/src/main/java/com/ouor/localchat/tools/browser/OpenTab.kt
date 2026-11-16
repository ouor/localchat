package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

object OpenTab : Tool {
    override val name = "open_tab"
    override val groups = setOf(ToolGroup.LINK, ToolGroup.SEARCH, ToolGroup.BROWSER)
    override val labelRes = R.string.tool_open_tab
    override val description =
        "Open a URL in a new browser tab of your own and make it your current tab. Use it to read a link " +
            "(then page_text) or to keep the current page while visiting another. Close tabs you no longer need."
    override val parameters = params { string("url", "Absolute http(s) URL to open.", required = true) }

    override fun argSummary(args: JsonObject) = args.optString("url").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val url = ctx.checkedUrl(args.string("url"))
        return onMain {
            val id = ctx.browser.openAiTab(ctx.sessionId, url)
            val tab = ctx.aiTab(id)
            val loaded = tab.awaitLoaded(LOAD_TIMEOUT)
            if (ctx.browser.sensitiveUrls.isSensitive(tab.url)) {
                return@onMain ToolOutcome(
                    "Opened tab $id, but it redirected to a sensitive site. " + sensitiveMessage(SensitiveUrls.hostOf(tab.url).orEmpty()),
                    isError = true,
                )
            }
            ToolOutcome("Opened tab $id${if (loaded) "" else " (still loading)"}. ${pageLine(tab)}", summary = tab.title)
        }
    }
}
