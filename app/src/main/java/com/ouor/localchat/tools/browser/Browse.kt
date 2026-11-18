package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

/**
 * tools-redesign 3-3: the work tab (created here when the chat has none) goes to a URL or a search page, and the
 * result lists the page's elements with refs. Without url it lists the current page again, continuing with what the
 * last list left out. A vision model also gets a picture of the visible part.
 */
object Browse : Tool {
    override val name = "browse"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_browse
    override val description =
        "Open a URL, or search words, in your browser tab (the user can watch it) and list the page's elements with refs " +
            "for click and type. Without url, lists the current page again, starting with the elements the last list left " +
            "out; use it after the user gave the tab back or when the page was still changing. Use read for text you want to quote."
    override val parameters = params {
        string("url", "http(s) URL (a path such as /a/b is opened on the current site), or search words to open a search " +
            "results page. Omit to look at the current page.")
    }

    override fun argSummary(args: JsonObject) = args.optString("url").orEmpty()

    /** Like the address bar: a dot or a scheme and no spaces is a URL, anything else is searched for. */
    internal fun isUrl(s: String) = ("." in s || "://" in s) && s.none { it.isWhitespace() }

    /** A path from an element list or a page (G-m5): opened against the current page. */
    internal fun isPath(s: String) = (s.startsWith("/") || s.startsWith("./") || s.startsWith("../")) && s.none { it.isWhitespace() }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = args.optString("url")?.trim()?.takeIf { it.isNotEmpty() }
        val direct = raw?.takeIf { !isPath(it) && isUrl(it) }?.let { ctx.checkedUrl(it) }
        return onMain {
            if (raw == null) {
                val tab = ctx.workTab()
                val since = now()
                return@onMain ctx.withPicture(tab, ctx.pageResult(tab, ctx.ready(tab, since), since, more = true), keep = true)
            }
            val tab = ctx.workTab(navigating = true)
            val url = direct ?: if (isPath(raw)) ctx.pathUrl(raw, tab.url) else return@onMain searched(ctx, tab, raw)
            val o = ctx.open(tab, url)
            if (o.readiness == Readiness.FAILED) {
                val err = tab.navError(o.since)
                // G-m6: a word with a dot that is no address ("Next.js") is searched for, as the address bar does.
                if ("://" !in raw && !isPath(raw) && err?.desc?.contains("ERR_NAME_NOT_RESOLVED") == true) return@onMain searched(ctx, tab, raw)
                return@onMain ToolOutcome(err?.let { TabRules.navErrorText(it, o.retried) } ?: "Could not open $url.", isError = true)
            }
            ctx.withPicture(tab, ctx.pageResult(tab, o.readiness, o.since), keep = true)
        }
    }

    /** A search results page for [words] through the same engine loop as web_search (A-M3). */
    private suspend fun searched(ctx: ToolContext, tab: BrowserTab, words: String): ToolOutcome {
        val run = ctx.searchEngines(tab, words)
        val problem = run.problem(ctx, words)
        if (run.failed || run.readiness == Readiness.FAILED) {
            return ToolOutcome("", isError = true, lead = problem ?: "Could not open a search page for \"$words\".")
        }
        val page = ctx.pageResult(tab, run.readiness, run.since)
        return ctx.withPicture(tab, page.copy(lead = problem ?: "Search results for \"$words\" (${run.engines()}):"), keep = true)
    }
}
