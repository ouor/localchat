package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.SearchEngines
import com.ouor.localchat.browser.SearchEngines.Outcome
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.core.model.SearchEngine
import com.ouor.localchat.core.model.ToolStatus
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * tools-redesign 3-3: the work tab (created here when the chat has none) goes to a URL or a search page, and the
 * result lists the page's elements with refs. Without url it lists the current page again. A vision model also gets
 * a picture of the visible part.
 */
object Browse : Tool {
    override val name = "browse"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_browse
    override val description =
        "Open a URL, or search words, in your browser tab (the user can watch it) and list the page's elements with refs " +
            "for click and type. Without url, lists the current page again, e.g. after the user gave the tab back or when " +
            "the page was still changing. Use read for text you want to quote."
    override val parameters = params {
        string("url", "http(s) URL, or search words to open a search results page. Omit to look at the current page.")
    }

    private const val JPEG_QUALITY = 80

    override fun argSummary(args: JsonObject) = args.optString("url").orEmpty()

    /** Like the address bar: a dot or a scheme and no spaces is a URL, anything else is searched for. */
    internal fun isUrl(s: String) = ("." in s || "://" in s) && s.none { it.isWhitespace() }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = args.optString("url")?.trim()?.takeIf { it.isNotEmpty() }
        val url = raw?.takeIf(::isUrl)?.let { ctx.checkedUrl(it) }
        val outcome = onMain {
            if (raw == null) {
                val tab = ctx.workTab()
                val since = now()
                return@onMain tab to ToolOutcome(ctx.pageResult(tab, ctx.ready(tab, since), since), summary = tab.title)
            }
            val tab = ctx.workTab(navigating = true)
            val o = if (url != null) ctx.open(tab, url) else search(ctx, tab, raw)
            if (o.readiness == Readiness.FAILED) {
                val err = tab.navError(o.since)
                return@onMain tab to ToolOutcome(err?.let { TabRules.navErrorText(it, o.retried) } ?: "Could not open ${url ?: raw}.", isError = true)
            }
            tab to ToolOutcome(
                ctx.pageResult(tab, o.readiness, o.since),
                isError = tab.navError(o.since)?.http == true,
                summary = tab.title,
            )
        }
        val (tab, result) = outcome
        if (!ctx.vision || result.isError) return result
        val bitmap = onMain { tab.capture() } ?: return result // not laid out: no picture
        val bytes = withContext(Dispatchers.Default) { jpeg(bitmap, JPEG_QUALITY) }
        val id = ctx.saveArtifact("browse-${System.currentTimeMillis()}.jpg", "image/jpeg", bytes)
        return result.copy(artifactIds = listOf(id), imageForModelId = id)
    }

    /** A search results page for [words]: the engines in order, moving on from a blocked or empty one. */
    private suspend fun search(ctx: ToolContext, tab: BrowserTab, words: String): Opened {
        val b = ctx.browser
        val plan = SearchEngines.order(b.searchEngine, words)
        val tries = ArrayList<Pair<SearchEngine, Outcome>>()
        var engine = plan[0]
        while (true) {
            val o = ctx.open(tab, SearchEngines.url(engine, words, b.searchLanguage, b.region))
            val outcome = when {
                o.readiness == Readiness.FAILED -> Outcome.NET_ERROR
                tab.navError(o.since)?.http == true || SearchEngines.BOT_URL.containsMatchIn(tab.url) -> Outcome.BLOCKED
                else -> try {
                    val page = PageScript.search(tab, engine)
                    when {
                        page.blocked -> Outcome.BLOCKED
                        page.results.isNotEmpty() -> Outcome.RESULTS
                        else -> Outcome.EMPTY
                    }
                } catch (_: PageError) {
                    Outcome.BLOCKED
                }
            }
            // open() already retried a network error once.
            tries += engine to outcome
            if (outcome == Outcome.NET_ERROR) tries += engine to outcome
            if (SearchEngines.next(plan, tries) != SearchEngines.Next.NEXT_ENGINE) return o
            val tried = tries.map { it.first }.toSet()
            engine = plan.first { it !in tried }
            ctx.status(ToolStatus.OTHER_ENGINE, SearchEngines.displayName(engine))
        }
    }
}
