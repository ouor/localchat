package com.ouor.localchat.tools.browser

import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Targets
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

/**
 * browser-layers 3-5: a URL, a path or search words in a new tab (the executor opened it; the front stays) or in the
 * given tab, then the page's elements with refs. A word with a dot that is no address is searched for, as the
 * address bar does (G-m6).
 */
object Open : Tool {
    override val name = "open"
    override val groups = setOf(ToolGroup.BROWSER)
    override val facts = ToolFacts(TargetRule.OPEN, browser = true, changesPage = true)
    override val description =
        "Open a URL or search words and list the page's elements with refs. Without tab it opens a new tab, so the page you " +
            "were on stays as it is: open a link this way to look at it without losing your place. With tab it goes there in " +
            "that tab. To look something up, use web_search. Close tabs you opened once you no longer need them."
    override val parameters = params {
        string("url", "http(s) URL, a path such as /a/b (on your current tab's site), or search words.", required = true)
        tabParam("Go there in this tab instead of a new one.")
    }

    override fun argSummary(args: JsonObject) = args.optString("url").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = args.string("url").trim()
        return onMain {
            val tab = ctx.tab()
            // A path is opened on the site of the page the chat was on: the target tab, or for a new tab the chat's current one.
            val base = tab.url.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: Targets.defaultTab(ctx.browser.tabs.value.filter { it.id != tab.id }, ctx.browser.front.value, ctx.chat)?.let { id ->
                    ctx.browser.tabs.value.firstOrNull { it.id == id }?.url
                }
            val url = when {
                Targets.isPath(raw) -> webUrlOrThrow(raw, base)
                Targets.isUrl(raw) -> webUrlOrThrow(raw, null)
                else -> return@onMain searched(ctx, tab, raw)
            }
            val o = ctx.open(tab, url)
            tab.downloadsSince(o.since).lastOrNull()?.let { d -> return@onMain ctx.fileResult(tab, d, isError = false) }
            if (o.readiness == Readiness.FAILED) {
                val err = tab.navError(o.since)
                if ("://" !in raw && !Targets.isPath(raw) && err?.desc?.contains("ERR_NAME_NOT_RESOLVED") == true) return@onMain searched(ctx, tab, raw)
                return@onMain ToolOutcome(lead = err?.let { TabRules.navErrorText(it, o.retried) } ?: "Could not open $url.", isError = true)
            }
            ctx.withPicture(tab, ctx.pageResult(tab, o.readiness, o.since), keep = true)
        }
    }

    /** A search results page for [words] through the same engine loop as web_search (A-M3). */
    private suspend fun searched(ctx: ToolContext, tab: BrowserTab, words: String): ToolOutcome {
        val run = ctx.searchEngines(tab, words)
        val problem = run.problem(words)
        if (run.failed || run.readiness == Readiness.FAILED) {
            return ToolOutcome(lead = problem ?: "Could not open a search page for \"$words\".", isError = true)
        }
        val page = ctx.pageResult(tab, run.readiness, run.since)
        return ctx.withPicture(tab, page.copy(lead = problem ?: "Search results for \"$words\" (${run.engines()}):"), keep = true)
    }
}
