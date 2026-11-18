package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.SearchEngines
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

/**
 * S-01 (tools-redesign 3-1): result pages in a hidden tab, the configured engine first; blocked, empty or unreachable
 * engines are replaced by the next one by the app ([SearchEngines.next]).
 */
object WebSearch : Tool {
    override val name = "web_search"
    override val groups = setOf(ToolGroup.SEARCH)
    override val labelRes = R.string.tool_web_search
    override val description =
        "Search the web. Returns up to 8 numbered results [n] with title, URL and snippet; cite them as [n]. The app " +
            "chooses the engine and switches engines by itself when one is blocked or returns nothing, so never repeat the same query."
    override val parameters = params {
        string("query", "What to search for, in the language most likely to find good sources.", required = true)
    }

    override fun argSummary(args: JsonObject) = args.optString("query").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val query = args.string("query")
        val b = ctx.browser
        val first = SearchEngines.url(SearchEngines.order(b.searchEngine, query)[0], query, b.searchLanguage, b.region)
        val start = now()
        val run = ctx.hiddenTab(first) { tab -> ctx.searchEngines(tab, query, loadingSince = start) }
        run.problem(ctx, query)?.let { return ToolOutcome("", isError = run.next == SearchEngines.Next.NET_FAILED, summary = "0", lead = it) }
        val sources = run.results.map { r ->
            Source(ctx.numberSource(r.url, r.title, r.snippet), r.url, r.title, r.snippet, SensitiveUrls.hostOf(r.url))
        }
        return ToolOutcome(
            text = sources.joinToString("\n\n") { s -> "[${s.n}] ${s.title}\n${s.url}" + (s.snippet?.let { "\n$it" } ?: "") },
            summary = "${sources.size}",
            sources = sources,
            lead = "Results for \"$query\" (${run.engines()}):",
            note = ctx.hint("read", "Read the 2-3 most relevant with read(url); several read calls can go in one step."),
        )
    }
}
