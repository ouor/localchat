package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

object Navigate : Tool {
    private val ACTIONS = setOf("back", "forward", "reload")

    override val name = "navigate"
    override val groups = setOf(ToolGroup.SEARCH, ToolGroup.BROWSER)
    override val labelRes = R.string.tool_navigate
    override val description =
        "Load a URL in your tab, or go back, forward or reload. Waits for the page to load. " +
            "Take a snapshot before interacting with the new page."
    override val parameters = params {
        string("url", "Absolute http(s) URL to load. Omit when using action.")
        string("action", "Instead of a URL: back, forward or reload.", enum = ACTIONS.toList())
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = args.optString("url") ?: args.optString("action").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val action = args.optString("action")?.lowercase()
        val url = args.optString("url")?.takeIf { it.isNotBlank() }?.let { ctx.checkedUrl(it) }
        if (url == null && action !in ACTIONS) throw ToolError("Pass a url, or action back, forward or reload.")
        return onMain {
            val tab = ctx.tabFor(args)
            when {
                url != null -> tab.load(url)
                action == "back" -> if (tab.webView.canGoBack()) tab.goBack() else throw ToolError("There is no previous page.")
                action == "forward" -> if (tab.webView.canGoForward()) tab.goForward() else throw ToolError("There is no next page.")
                else -> tab.reload()
            }
            val loaded = tab.awaitLoaded(LOAD_TIMEOUT)
            ToolOutcome("${pageLine(tab)}${if (loaded) "" else " (still loading)"}${dialogSuffix(tab)}", summary = tab.title)
        }
    }
}
