package com.ouor.localchat.chat.context

import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.tools.Untrusted
import com.ouor.localchat.tools.ChatTabs

/**
 * browser-layers 5-1: the tabs when a turn starts, at most three lines, put after the last user message. The user's
 * tab and this chat's current tab only (titles are page text, inside the boundary); the rest is for tabs().
 */
object TabNote {
    const val HEAD = "[App note: browser]"

    fun build(tabs: List<TabInfo>, front: Front?, shown: Int?, chat: ChatTabs): String? {
        if (tabs.isEmpty()) return null
        val byId = tabs.associateBy { it.id }
        fun line(t: TabInfo) = "tab ${t.id}, ${t.title.ifBlank { "(untitled)" }} — ${t.url}"
        val users = shown?.let(byId::get)?.let { "On the user's screen: " + line(it) }
            ?: front?.tabId?.let(byId::get)?.let { "Last viewed by the user: " + line(it) }
        val userTab = shown?.takeIf { it in byId } ?: front?.tabId
        val mine = chat.current?.takeIf { it != userTab }?.let(byId::get)?.let { "Your current tab: " + line(it) }
        val inside = listOfNotNull(users, mine)
        val count = (if (tabs.size == 1) "1 tab open" else "${tabs.size} tabs open") + "; tabs() lists them."
        return listOfNotNull(HEAD, inside.takeIf { it.isNotEmpty() }?.joinToString("\n")?.let(Untrusted::wrap), count).joinToString("\n")
    }
}
