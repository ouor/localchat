package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.SearchEngines
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optIntIn
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

/** S-01: loads the configured engine's result page (S-05) in the AI tab and extracts results with search.js. */
object WebSearch : Tool {
    override val name = "web_search"
    override val groups = setOf(ToolGroup.SEARCH)
    override val labelRes = R.string.tool_web_search
    override val description =
        "Search the web. Returns numbered results [n] with title, URL and snippet; cite them as [n]. " +
            "Read a result with open_tab, then page_text."
    override val parameters = params {
        string("query", "Search query.", required = true)
        int("maxResults", "How many results (default 8, max 15).")
    }

    override fun argSummary(args: JsonObject) = args.optString("query").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val query = args.string("query")
        val max = args.optIntIn("maxResults", 1..15) ?: 8
        return onMain {
            val engine = ctx.browser.searchEngine
            val url = SearchEngines.url(engine, query)
            // Current tab, or a fresh one when it cannot be used (sensitive site, user's tab).
            val tab = try {
                ctx.aiTab(null)
            } catch (e: PageError) {
                ctx.aiTab(ctx.browser.openAiTab(ctx.sessionId, null))
            }
            tab.load(url)
            tab.awaitLoaded(LOAD_TIMEOUT)
            val results = PageScript.search(tab, engine, max)
            val engineName = SearchEngines.displayName(engine)
            if (results.isEmpty()) {
                return@onMain ToolOutcome(
                    "No results for \"$query\" on $engineName. ${pageLine(tab)}\nIf the page shows a CAPTCHA or consent screen, use request_handoff.",
                    summary = "0",
                )
            }
            val sources = results.map { r ->
                Source(ctx.numberSource(r.url, r.title, r.snippet), r.url, r.title, r.snippet, SensitiveUrls.hostOf(r.url))
            }
            val text = sources.joinToString("\n\n", "Results for \"$query\" ($engineName):\n\n") { s ->
                "[${s.n}] ${s.title}\n${s.url}" + (s.snippet?.let { "\n$it" } ?: "")
            }
            ToolOutcome(text, summary = "${sources.size}", sources = sources)
        }
    }
}
