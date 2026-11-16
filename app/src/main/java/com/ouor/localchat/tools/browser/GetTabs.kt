package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

object GetTabs : Tool {
    override val name = "get_tabs"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_get_tabs
    override val description =
        "List open browser tabs with id, owner, title and URL. You can only use tabs marked [yours]; " +
            "the user can hand you one of theirs."
    override val parameters = params {}

    override fun argSummary(args: JsonObject) = ""

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome = onMain {
        val me = Owner.Ai(ctx.sessionId)
        val current = ctx.browser.currentAiTab(ctx.sessionId)
        val tabs = ctx.browser.tabs.value
        if (tabs.isEmpty()) return@onMain ToolOutcome("No tabs are open.")
        val lines = tabs.map { t ->
            val owner = when (t.owner) {
                me -> "[yours]"
                Owner.User -> "[user]"
                else -> "[other chat]"
            }
            val mark = if (t.id == current) " [current]" else ""
            // B-S3: a sensitive tab shows its domain only.
            val page = if (ctx.browser.sensitiveUrls.isSensitive(t.url)) "(sensitive site: ${SensitiveUrls.hostOf(t.url)})"
            else "\"${t.title}\" ${t.url}"
            "- tab ${t.id} $owner$mark $page"
        }
        ToolOutcome(lines.joinToString("\n"), summary = "${tabs.size} tabs")
    }
}
