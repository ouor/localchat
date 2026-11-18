package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.NavError
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.SearchEngines
import com.ouor.localchat.browser.SearchEngines.Outcome
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.SearchResult
import com.ouor.localchat.core.model.SearchEngine
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.core.model.ToolStatus
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.coroutines.delay
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
        val plan = SearchEngines.order(b.searchEngine, query)
        fun urlOf(e: SearchEngine) = SearchEngines.url(e, query, b.searchLanguage, b.region)
        val start = now()
        return ctx.hiddenTab(urlOf(plan[0])) { tab ->
            val tries = ArrayList<Pair<SearchEngine, Outcome>>()
            var engine = plan[0]
            var since = start
            var lastNet: NavError? = null
            while (true) {
                val r = if (tries.isEmpty()) ctx.ready(tab, since) else tab.awaitReady(since)
                val (outcome, results) = look(tab, engine, r, since)
                if (outcome == Outcome.NET_ERROR) lastNet = tab.navError(since)
                tries += engine to outcome
                when (SearchEngines.next(plan, tries)) {
                    SearchEngines.Next.DONE -> return@hiddenTab success(ctx, query, engine, tries, results)
                    SearchEngines.Next.RETRY -> {
                        ctx.status(ToolStatus.RETRYING)
                        delay(SearchEngines.RETRY_MS)
                    }
                    SearchEngines.Next.NEXT_ENGINE -> {
                        if (now() - start > SearchEngines.BUDGET_MS) {
                            val names = tries.map { SearchEngines.displayName(it.first) }.distinct().joinToString(", ")
                            return@hiddenTab ToolOutcome("Search timed out after trying $names. Try again in a moment.", summary = "0")
                        }
                        val tried = tries.map { it.first }.toSet()
                        engine = plan.first { it !in tried }
                        ctx.status(ToolStatus.OTHER_ENGINE, SearchEngines.displayName(engine))
                    }
                    SearchEngines.Next.NO_RESULTS -> {
                        val names = tries.filter { it.second == Outcome.EMPTY }.map { SearchEngines.displayName(it.first) }.distinct()
                        return@hiddenTab ToolOutcome(
                            "No results for \"$query\" on ${names.joinToString(" or ")}. Do not repeat this query; use fewer or different words, or another language.",
                            summary = "0",
                        )
                    }
                    SearchEngines.Next.ALL_BLOCKED -> {
                        val names = plan.joinToString(", ") { SearchEngines.displayName(it) }
                        val handoff = if ("browse" in ctx.exposed && "request_handoff" in ctx.exposed) {
                            ", or browse a search page and use request_handoff so the user can pass the check"
                        } else ""
                        return@hiddenTab ToolOutcome(
                            "Every search engine showed a bot check or consent page ($names). Answer from what you know and say it is unchecked$handoff.",
                            summary = "0",
                        )
                    }
                    SearchEngines.Next.NET_FAILED -> {
                        val desc = lastNet?.desc.orEmpty()
                        return@hiddenTab ToolOutcome(
                            "Search failed: ${TabRules.netCause(desc)} ($desc). The app already retried; try again in a moment.",
                            isError = true,
                            summary = "0",
                        )
                    }
                }
                since = now()
                tab.load(urlOf(engine))
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }.also { ctx.status(null) }
    }

    /** One result page: blocked (bot check, consent, HTTP error, never loaded), empty, or results. */
    private suspend fun look(tab: BrowserTab, engine: SearchEngine, r: Readiness, since: Long): Pair<Outcome, List<SearchResult>> {
        if (r == Readiness.FAILED) return Outcome.NET_ERROR to emptyList()
        if (tab.navError(since)?.http == true || SearchEngines.BOT_URL.containsMatchIn(tab.url)) return Outcome.BLOCKED to emptyList()
        val page = try {
            PageScript.search(tab, engine)
        } catch (_: PageError) {
            return Outcome.BLOCKED to emptyList()
        }
        val results = page.results.map { it.copy(url = SearchEngines.clean(it.url)) }
            .filterNot { SearchEngines.isEngineLink(it.url) }.distinctBy { it.url }.take(PageScript.MAX_RESULTS)
        return when {
            page.blocked -> Outcome.BLOCKED to emptyList()
            results.isNotEmpty() -> Outcome.RESULTS to results
            r == Readiness.LOADING -> Outcome.BLOCKED to emptyList()
            else -> Outcome.EMPTY to emptyList()
        }
    }

    private fun success(ctx: ToolContext, query: String, engine: SearchEngine, tries: List<Pair<SearchEngine, Outcome>>, results: List<SearchResult>): ToolOutcome {
        val sources = results.map { r ->
            Source(ctx.numberSource(r.url, r.title, r.snippet), r.url, r.title, r.snippet, SensitiveUrls.hostOf(r.url))
        }
        val before = tries.filter { it.first != engine }.map { it.first }.distinct().map { e ->
            val why = when (tries.last { it.first == e }.second) {
                Outcome.BLOCKED -> "showed a bot check"
                Outcome.EMPTY -> "had no results"
                else -> "could not be reached"
            }
            "${SearchEngines.displayName(e)} $why"
        }
        val head = "Results for \"$query\" (" + (listOf(SearchEngines.displayName(engine)) + before).joinToString("; ") + "):\n\n"
        val list = sources.joinToString("\n\n") { s -> "[${s.n}] ${s.title}\n${s.url}" + (s.snippet?.let { "\n$it" } ?: "") }
        val next = ctx.hint("read", "\n\nRead the 2-3 most relevant with read(url); several read calls can go in one step.")
        return ToolOutcome(head + list + next, summary = "${sources.size}", sources = sources)
    }
}
