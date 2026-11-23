package com.ouor.localchat.tools.browser

import com.ouor.localchat.browser.BrowserFacts
import com.ouor.localchat.tools.ChatTabs
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Targets
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

/** browser-layers 3-5: the open tabs as facts: which one the user sees, which is this chat's, which it opened. */
object Tabs : Tool {
    override val name = "tabs"
    override val groups = setOf(ToolGroup.BROWSER)
    override val facts = ToolFacts(TargetRule.NONE, browser = true, changesPage = false)
    override val description =
        "List the open tabs: number, title and URL, which one is on the user's screen, which one is your current tab, and " +
            "which ones you opened."
    override val parameters = params {}

    override fun argSummary(args: JsonObject) = ""

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val b = ctx.browser
        val tabs = b.tabs.value
        if (tabs.isEmpty()) return ToolOutcome(lead = Targets.NO_TAB)
        val current = ctx.chat.current?.takeIf { id -> tabs.any { it.id == id } }
        return ToolOutcome(
            lead = "${count(tabs.size)} open" + (current?.let { "; your current tab is $it." } ?: "."),
            page = lines(b, ctx.chat, ctx.sessionId),
            summary = count(tabs.size),
        )
    }

    private fun count(n: Int) = if (n == 1) "1 tab" else "$n tabs"

    /** One line per tab: `[2] Home / X — https://x.com/home (on the user's screen)`. */
    fun lines(b: BrowserFacts, chat: ChatTabs, sessionId: String): String {
        val shown = b.shown.value
        val front = b.front.value?.tabId
        val activity = b.activity.value
        return b.tabs.value.joinToString("\n") { t ->
            val marks = listOfNotNull(
                when {
                    t.id == shown -> "on the user's screen"
                    shown == null && t.id == front -> "last viewed by the user"
                    else -> null
                },
                "your current tab".takeIf { t.id == chat.current },
                "you opened it".takeIf { t.id in chat.opened },
                "another chat is using it".takeIf { activity[t.id]?.let { it.sessionId != sessionId } == true },
                "asleep".takeIf { t.asleep != null },
            )
            "[${t.id}] ${t.title.ifBlank { "(untitled)" }} — ${t.url}" + marks.joinToString("") { " ($it)" }
        }
    }
}
