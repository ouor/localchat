package com.ouor.localchat.tools.browser

import com.ouor.localchat.browser.SearchEngines
import com.ouor.localchat.tools.Source
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject
import java.net.URI

/**
 * browser-layers 3-5: the engines in order in the chat's search tab (its last one while still on a result page, else
 * a new tab, which stays open for the user and the model). Blocked, empty or unreachable engines are replaced by the
 * next one ([SearchEngines.next]).
 */
object WebSearch : Tool {
    override val name = "web_search"
    override val groups = setOf(ToolGroup.SEARCH)
    override val facts = ToolFacts(TargetRule.SEARCH, browser = true, changesPage = false)
    override val description =
        "Search the web. Returns up to 8 numbered results [n] with title, URL and snippet; cite them as [n]. The app picks " +
            "the engine and switches engines by itself when one is blocked or returns nothing, so never repeat the same query. " +
            "Then read the 2-3 best with read(url); several read calls can go in one step."
    override val parameters = params {
        string("query", "What to search for, in the language most likely to find good sources.", required = true)
    }

    override fun argSummary(args: JsonObject) = args.optString("query").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val query = args.string("query")
        val run = onMain {
            val tab = ctx.tab()
            ctx.chat.searchTab = tab.id
            ctx.searchEngines(tab, query)
        }
        run.problem(query)?.let { return ToolOutcome(lead = it, isError = run.next == SearchEngines.Next.NET_FAILED, summary = "0") }
        val sources = run.results.map { r ->
            Source(ctx.numberSource(r.url, r.title, r.snippet), r.url, r.title, r.snippet, hostOf(r.url))
        }
        return ToolOutcome(
            lead = "Results for \"$query\" (${run.engines()}):",
            page = sources.joinToString("\n\n") { s -> "[${s.n}] ${s.title}\n${s.url}" + (s.snippet?.let { "\n$it" } ?: "") },
            summary = "${sources.size}",
            sources = sources,
        )
    }

    internal fun hostOf(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
}
