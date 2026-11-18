package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.NavError
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.ReadText
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.tools.ReadCursor
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

/**
 * browser-layers 3-5: a page's main text in parts, fresh from the page each call. With url, a tab already showing it
 * is read (Targets rule 4), else the executor opened a new one. Where the last read ended is kept per chat and tab
 * ([ReadRules]); LinkPrefetcher reads links in a question through the same chain.
 */
object Read : Tool {
    override val name = "read"
    override val groups = setOf(ToolGroup.SEARCH, ToolGroup.BROWSER)
    override val facts = ToolFacts(TargetRule.READ, browser = true, changesPage = false)
    override val labelRes = R.string.tool_read
    override val description =
        "Read the main text of a page to quote it, in parts for long pages. With url, reads it in a tab that already shows " +
            "it, or else opens a new tab. Without url, reads your tab. Starts with a citation number [n]; the end says how to " +
            "read the next part."
    override val parameters = params {
        string("url", "http(s) URL or a path on your current tab's site.")
        tabParam()
        string("part", "Part number to read instead of continuing.")
    }

    override fun argSummary(args: JsonObject) = args.optString("url") ?: args.optString("tab").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = args.optString("url")?.trim()?.takeIf { it.isNotEmpty() }
        val part = args.optString("part")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: throw ToolError("part must be a number from 1.")
        }
        return onMain {
            val tab = ctx.tab()
            var since = now()
            var retried = false
            var readiness: Readiness
            val url = raw?.let { webUrlOrThrow(it, tab.url.takeIf { u -> u.startsWith("http") } ?: currentUrl(ctx)) }
            if (url != null && !shows(tab.url, tab.urlChain, url)) {
                val o = ctx.open(tab, url)
                since = o.since
                retried = o.retried
                readiness = o.readiness
                tab.downloadsSince(o.since).lastOrNull()?.let { d ->
                    return@onMain ToolOutcome(lead = d.describe() + " Its text cannot be read in this app.", isError = true, summary = d.fileName)
                }
                if (readiness == Readiness.FAILED) {
                    val err = tab.navError(o.since)
                    return@onMain ToolOutcome(lead = err?.let { TabRules.navErrorText(it, retried) } ?: "Could not open $url.", isError = true)
                }
            } else {
                readiness = ctx.ready(tab, since)
            }
            val err = tab.navError(since)
            val t = PageScript.call<ReadText>(tab, "readText")
            val doc = mark(tab)?.doc
            val plan = ReadRules.plan(t.text, TabRules.readChunk(ctx.contextWindow), doc, ctx.readCursors[tab.id], part, ctx.chat.turn, t.screenAt)
            plan.nothingAfter?.let { end ->
                return@onMain ToolOutcome(lead = "No text after where your last read ended (…$end). On a feed, scroll() shows more; part=1 reads from the start.")
            }
            ctx.readCursors[tab.id] = plan.cursor
            val title = t.title.ifBlank { t.url }
            val n = ctx.numberSource(t.url, title, null)
            val where = listOfNotNull(
                "tab ${tab.id}",
                if (plan.count > 1) "part ${plan.index + 1} of ${plan.count}" else null,
            ).joinToString(", ") + listOfNotNull(
                plan.screenPart?.let { "the user's screen is at part $it" },
                "continuing after your read in an earlier reply; part=1 starts over".takeIf { plan.earlier },
            ).joinToString("") { "; $it" }
            val marks = listOfNotNull(
                "(the page changed since your last read; from the start)".takeIf { plan.restarted },
                flags(t, readiness, err).takeIf { plan.index == 0 && it.isNotBlank() },
            ).joinToString("\n").let { if (it.isEmpty()) "" else "\n$it" }
            ToolOutcome(
                page = "[$n] $title — ${t.url} ($where)$marks\n\n${plan.text.ifBlank { "(no readable text)" }}",
                summary = "$title (${plan.index + 1}/${plan.count})",
                sources = listOf(Source(n, t.url, title, siteName = WebSearch.hostOf(t.url))),
                note = if (plan.index + 1 < plan.count) "[more: read() continues with part ${plan.index + 2} of ${plan.count}]" else "",
                isError = err?.http == true,
            )
        }
    }

    private fun currentUrl(ctx: ToolContext): String? =
        ctx.chat.current?.let { id -> ctx.browser.tabs.value.firstOrNull { it.id == id }?.url }

    private fun shows(url: String, chain: List<String>, target: String) =
        (chain + url).any { it.substringBefore('#').trimEnd('/') == target.substringBefore('#').trimEnd('/') }

    private fun flags(t: ReadText, r: Readiness, err: NavError?): String {
        val marks = listOfNotNull(
            "(the page asks to sign in)".takeIf { "login" in t.flags },
            "(blocked by a bot check)".takeIf { "bot" in t.flags },
            err?.takeIf { it.http }?.let { "(HTTP ${it.code}${it.desc.takeIf(String::isNotBlank)?.let { d -> " $d" }.orEmpty()})" },
            "(page still changing; text may be incomplete)".takeIf { r == Readiness.CHANGING },
            "(still loading after ${TabRules.LOAD_CAP / 1000} s)".takeIf { r == Readiness.LOADING },
        )
        val blocked = "login" in t.flags || "bot" in t.flags || err?.http == true
        return marks.joinToString(" ") + if (blocked) " To get past it, ask_user can let the user do it, or pick another source." else ""
    }
}

/**
 * Where read goes on (browser-layers 3-5), pure: after the [ReadCursor.anchor] (the last 200 characters it returned)
 * when it is still in the same document's text, from the start when it is gone, or the asked [part].
 */
object ReadRules {
    const val ANCHOR = 200
    private const val END_SHOWN = 60

    /**
     * [index] 0-based of [count]; [text] the part; [cursor] where this read ends; [restarted] = a cursor was lost;
     * [earlier] = it goes on from an earlier turn; [screenPart] 1-based part at the top of the user's screen (null at
     * the top); [nothingAfter] = the last read already reached the end (its last characters).
     */
    data class Plan(
        val index: Int,
        val count: Int,
        val text: String,
        val cursor: ReadCursor,
        val restarted: Boolean = false,
        val earlier: Boolean = false,
        val screenPart: Int? = null,
        val nothingAfter: String? = null,
    )

    /** [text] cut like [TabRules.parts], as start..end offsets (end exclusive). */
    fun bounds(text: String, size: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) {
                val blank = text.lastIndexOf("\n\n", end - 2)
                if (blank > start) end = blank + 2
            }
            out += start to end
            start = end
        }
        return out.ifEmpty { listOf(0 to 0) }
    }

    fun plan(text: String, size: Int, doc: String?, cursor: ReadCursor?, part: Int?, turn: Int, screenAt: Int?): Plan {
        val b = bounds(text, size)
        val screen = screenAt?.takeIf { it > 0 }?.let { at -> b.indexOfFirst { at >= it.first && at < it.second }.takeIf { it >= 0 }?.plus(1) }
        fun of(i: Int, start: Int, end: Int, restarted: Boolean = false, earlier: Boolean = false) = Plan(
            i, b.size, text.substring(start, end).trim('\n'),
            ReadCursor(doc, text.substring(maxOf(start, end - ANCHOR), end), i + 1, turn), restarted, earlier, screen,
        )
        if (part != null) {
            val i = (part - 1).coerceIn(0, b.size - 1)
            return of(i, b[i].first, b[i].second)
        }
        val pos = cursor?.takeIf { it.doc == doc && it.anchor.isNotEmpty() }?.let { text.lastIndexOf(it.anchor) } ?: -1
        if (pos < 0) {
            return of(0, b[0].first, b[0].second, restarted = cursor != null)
        }
        val after = pos + cursor!!.anchor.length
        if (text.substring(after).isBlank()) {
            return of(b.size - 1, b.last().first, b.last().second).copy(nothingAfter = cursor.anchor.takeLast(END_SHOWN).trim(), cursor = cursor)
        }
        val (s, e) = bounds(text.substring(after), size).first()
        val end = after + e
        val i = b.indexOfFirst { end - 1 >= it.first && end - 1 < it.second }.coerceAtLeast(0)
        return of(i, after + s, end, earlier = cursor.turn != turn)
    }
}
