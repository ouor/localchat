package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.NavError
import com.ouor.localchat.browser.Readiness
import com.ouor.localchat.browser.TabRules
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.ReadText
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.tools.ReadCursor
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

/**
 * tools-redesign 3-2: a page's main text in parts. With url the page is read in a hidden tab (the work tab is left
 * alone) and kept for the chat (G-m9), so the next part needs no reload; without url the work tab is read. LinkPrefetcher
 * runs this tool for links in a question to a model without tools.
 */
object Read : Tool {
    override val name = "read"
    override val groups = setOf(ToolGroup.SEARCH, ToolGroup.BROWSER)
    override val labelRes = R.string.tool_read
    override val description =
        "Read the main text of a web page. With url, the page is opened out of sight and your browser tab is left alone; " +
            "without url, reads the page in your browser tab. Long pages come in parts: call read again with the same url " +
            "for the next part. The result starts with a citation number [n]."
    override val parameters = params {
        string("url", "http(s) URL to read. Omit to read the page in your browser tab.")
    }

    private const val TAB = "(tab)"

    override fun argSummary(args: JsonObject) = args.optString("url").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = args.optString("url")?.trim()?.takeIf { it.isNotEmpty() }
        if (raw == null) {
            if ("browse" !in ctx.exposed) throw ToolError("Pass the url to read.")
            return onMain {
                val tab = ctx.workTab()
                val r = ctx.ready(tab, now())
                val doc = mark(tab)?.doc
                val t = PageScript.call<ReadText>(tab, "readText")
                val old = ctx.readCursors[TAB]
                val cursor = old?.takeIf { it.doc == doc && it.head == t.text.take(HEAD) }
                    ?: newCursor(ctx, t, r, null).copy(doc = doc, head = t.text.take(HEAD)).also { ctx.readCursors[TAB] = it }
                part(ctx, cursor, null)
            }
        }
        val url = ctx.checkedUrl(raw)
        ctx.readCursors[url]?.let { return part(ctx, it, url) }
        val start = now()
        return ctx.hiddenTab(url) { tab ->
            val o = ctx.open(tab, url, alreadyLoading = start)
            tab.downloadSince(start)?.let { d ->
                return@hiddenTab ToolOutcome("This URL is a file download (${d.mime ?: "unknown type"}), which cannot be read here.", isError = true)
            }
            val err = tab.navError(o.since)
            if (o.readiness == Readiness.FAILED) {
                return@hiddenTab ToolOutcome(err?.let { TabRules.navErrorText(it, o.retried) } ?: "Could not open $url.", isError = true)
            }
            val t = PageScript.call<ReadText>(tab, "readText")
            // B-M1: a page reached through a sensitive site is refused before it is kept for the next part.
            val chain = (listOf(url, tab.url, t.url) + tab.mainRequestsSince(0)).distinct()
            chain.firstOrNull(ctx.browser.sensitiveUrls::isSensitive)?.let { throw ToolError(sensitiveMessage(SensitiveUrls.hostOf(it).orEmpty())) }
            val cursor = newCursor(ctx, t, o.readiness, err).copy(urls = chain)
            ctx.readCursors[url] = cursor
            ctx.readCursors[t.url] = cursor
            part(ctx, cursor, url).let { if (err?.http == true) it.copy(isError = true) else it }
        }
    }

    private const val HEAD = 500

    private fun newCursor(ctx: ToolContext, t: ReadText, r: Readiness, err: NavError?): ReadCursor {
        val marks = listOfNotNull(
            "(the page asks to sign in)".takeIf { "login" in t.flags },
            "(blocked by a bot check)".takeIf { "bot" in t.flags },
            err?.takeIf { it.http }?.let { "(HTTP ${it.code}${it.desc.takeIf(String::isNotBlank)?.let { d -> " $d" }.orEmpty()})" },
            "(page still changing; text may be incomplete)".takeIf { r == Readiness.CHANGING },
            "(still loading after ${TabRules.LOAD_CAP / 1000} s)".takeIf { r == Readiness.LOADING },
        )
        val blocked = "login" in t.flags || "bot" in t.flags || err?.http == true
        val way = when {
            !blocked -> ""
            "browse" in ctx.exposed && "request_handoff" in ctx.exposed -> " To get past it, browse the page and use request_handoff."
            "web_search" in ctx.exposed -> " Pick another source."
            else -> ""
        }
        return ReadCursor(t.url, t.title.ifBlank { t.url }, TabRules.parts(t.text, TabRules.readChunk(ctx.contextWindow)), 0, marks.joinToString(" ") + way)
    }

    /** The cursor's next part; [url] = what read was called with (null = the tab). */
    private fun part(ctx: ToolContext, c: ReadCursor, url: String?): ToolOutcome {
        ctx.markVisited(c.urls + c.url)
        val k = c.parts.size
        if (c.next >= k) return ToolOutcome("", lead = "That was the last part ($k of $k).", summary = "${c.title} ($k/$k)")
        val i = c.next++
        // Numbered per message: a cursor kept from an earlier turn gets this message's number.
        val n = ctx.numberSource(c.url, c.title, null)
        val partOf = if (k > 1) " (part ${i + 1} of $k)" else ""
        val flags = c.flags.takeIf { i == 0 && it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        val call = if (url == null) "read()" else "read(\"$url\")"
        return ToolOutcome(
            text = "[$n] ${c.title} — ${c.url}$partOf$flags\n\n${c.parts[i].ifBlank { "(no readable text)" }}",
            summary = "${c.title} (${i + 1}/$k)",
            sources = listOf(Source(n, c.url, c.title, siteName = SensitiveUrls.hostOf(c.url))),
            note = if (i + 1 < k) ctx.hint("read", "[more: call $call again for part ${i + 2} of $k]") else "",
        )
    }
}
