package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.PageTextResult
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optInt
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PageText : Tool {
    override val name = "page_text"
    override val groups = setOf(ToolGroup.SEARCH, ToolGroup.BROWSER)
    override val labelRes = R.string.tool_page_text
    override val description =
        "Read the main text of the page in your tab, in pages of 20,000 characters. The result starts with a " +
            "citation number [n]; cite the page with it."
    override val parameters = params {
        int("page", "1-based page of the text (default 1).")
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = args.optInt("page")?.let { "page $it" }.orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val page = (args.optInt("page") ?: 1).coerceAtLeast(1)
        return onMain {
            val tab = ctx.tabFor(args)
            tab.awaitLoaded(LOAD_TIMEOUT)
            val r = PageScript.call<PageTextResult>(tab, "pageText", buildJsonObject { put("page", page) })
            val n = ctx.numberSource(r.url, r.title, null)
            val more = if (r.page < r.pages) "\n[more: call page_text with page=${r.page + 1}]" else ""
            ToolOutcome(
                text = "[$n] ${r.title} — ${r.url} (page ${r.page}/${r.pages})\n\n${r.text.ifBlank { "(no text)" }}$more",
                summary = "${r.title} (${r.page}/${r.pages})",
                sources = listOf(Source(n, r.url, r.title, siteName = SensitiveUrls.hostOf(r.url))),
            )
        }
    }
}
