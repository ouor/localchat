package com.ouor.localchat.tools.browser

import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.NavError
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.SearchEngines
import com.ouor.localchat.browser.SearchEngines.Next
import com.ouor.localchat.browser.SearchEngines.Outcome
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.SearchResult
import com.ouor.localchat.core.model.SearchEngine
import com.ouor.localchat.core.model.ToolStatus
import com.ouor.localchat.tools.ToolContext
import kotlinx.coroutines.delay

/**
 * How a search over the engines ended: [engine] = the last one loaded, [next] = DONE (then [results]) or why it
 * stopped; [since]/[readiness] are of the last load.
 */
internal class EngineSearch(
    val engine: SearchEngine,
    val tries: List<Pair<SearchEngine, Outcome>>,
    val next: Next,
    val results: List<SearchResult>,
    val since: Long,
    val readiness: Readiness,
    private val lastNet: NavError?,
) {
    /** "Bing; DuckDuckGo showed a bot check": the engine used, then what happened to the ones before it. */
    fun engines(): String = (listOf(SearchEngines.displayName(engine)) + tries.filter { it.first != engine }.map { it.first }.distinct().map { e ->
        val why = when (tries.last { it.first == e }.second) {
            Outcome.BLOCKED -> "showed a bot check"
            Outcome.EMPTY -> "had no results"
            else -> "could not be reached"
        }
        "${SearchEngines.displayName(e)} $why"
    }).joinToString("; ")

    /** Why there are no results, the same for web_search and open (A-M3); null when it found some. */
    fun problem(query: String): String? = when (next) {
        Next.DONE, Next.RETRY, Next.NEXT_ENGINE -> null
        Next.NO_RESULTS -> {
            val names = tries.filter { it.second == Outcome.EMPTY }.map { SearchEngines.displayName(it.first) }.distinct()
            "No results for \"$query\" on ${names.joinToString(" or ")}. Do not repeat this query; use fewer or different words, or another language."
        }
        Next.ALL_BLOCKED -> {
            val names = tries.map { SearchEngines.displayName(it.first) }.distinct().joinToString(", ")
            "Every search engine showed a bot check or consent page ($names). Answer from what you know and say it is unchecked, " +
                "or open a search page and use ask_user so the user can pass the check."
        }
        Next.NET_FAILED -> {
            val desc = lastNet?.desc.orEmpty()
            "Search failed: ${TabRules.netCause(desc)} ($desc). The app already retried; try again in a moment."
        }
        Next.TIMED_OUT -> {
            val names = tries.map { SearchEngines.displayName(it.first) }.distinct().joinToString(", ")
            "Search timed out after trying $names. Try again in a moment."
        }
    }

    /** Ended without a page worth showing. */
    val failed: Boolean get() = next == Next.NET_FAILED || next == Next.TIMED_OUT
}

/**
 * The engines in order on [tab] for [query] (tools-redesign 6): a blocked, empty or unreachable result page moves on
 * to the next engine ([SearchEngines.next]), within [SearchEngines.BUDGET_MS] checked before every load. Results are
 * cleaned of tracking parameters and of the engines' own links. web_search and open (search words) run it in the
 * call's tab. Main thread.
 */
internal suspend fun ToolContext.searchEngines(tab: BrowserTab, query: String): EngineSearch {
    val b = browser
    val plan = SearchEngines.order(b.searchEngine, query)
    fun load(e: SearchEngine): Long = now().also { tab.load(SearchEngines.url(e, query, b.searchLanguage, b.region)) }
    val start = now()
    val tries = ArrayList<Pair<SearchEngine, Outcome>>()
    var engine = plan[0]
    var since = load(engine)
    var lastNet: NavError? = null
    while (true) {
        // The first wait shows its phase; later ones keep the "other engine" line.
        val r = if (tries.isEmpty()) ready(tab, since) else tab.awaitReady(since)
        val (outcome, results) = look(tab, engine, r, since)
        if (outcome == Outcome.NET_ERROR) lastNet = tab.navError(since)
        tries += engine to outcome
        when (val next = SearchEngines.next(plan, tries, now() - start)) {
            Next.RETRY -> {
                status(ToolStatus.RETRYING)
                delay(SearchEngines.RETRY_MS)
            }
            Next.NEXT_ENGINE -> {
                val tried = tries.map { it.first }.toSet()
                engine = plan.first { it !in tried }
                status(ToolStatus.OTHER_ENGINE, SearchEngines.displayName(engine))
            }
            else -> {
                status(null)
                return EngineSearch(engine, tries, next, results, since, r, lastNet)
            }
        }
        since = load(engine)
    }
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
