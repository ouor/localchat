package com.ouor.localchat.tools

import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.SearchEngines
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.TabRules
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * One chat's memory of tabs (browser-layers 3-2), kept by TurnRunner across turns in this process. Not ownership: the
 * same tab may be several chats' current tab, and the user's tabs are as usable as these.
 */
class ChatTabs {
    /** The tab this chat's calls last acted on, opened or read, and when (epoch ms, like [Front.at]). */
    @Volatile var current: Int? = null
        private set
    @Volatile var currentAt: Long = 0
        private set

    /** Tabs this chat opened: tabs() marks them (you opened it). */
    val opened: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** The tab of this chat's last web_search. */
    @Volatile var searchTab: Int? = null

    /** Counts this chat's turns; read cursors remember the turn they were made in. */
    @Volatile var turn: Int = 0

    val readCursors: MutableMap<Int, ReadCursor> = ConcurrentHashMap()

    /** Rule 8: the call's tab is now this chat's current tab. */
    fun used(tabId: Int, at: Long) {
        current = tabId
        currentAt = at
    }
}

/** The default-tab rules (browser-layers 3-2), pure: the runner locks, the policies see and the tool uses this tab. */
object Targets {
    sealed interface Target {
        data object None : Target
        data class Tab(val id: Int) : Target
        data object NewTab : Target
        data class Error(val message: String) : Target
    }

    const val NO_TAB = "No tab is open; use open(url) first."

    fun resolve(rule: TargetRule, args: JsonObject, tabs: List<TabInfo>, front: Front?, chat: ChatTabs): Target {
        if (rule == TargetRule.NONE) return Target.None
        val open = tabs.associateBy { it.id }
        // 1. A ref names its tab.
        args.optString("ref")?.trim()?.takeIf { it.isNotEmpty() }?.let { ref ->
            val id = TabRules.tabOfRef(ref)
                ?: return Target.Error("$ref is not a ref of an open tab; refs look like 4e7. Use look() for fresh refs." + listed(tabs))
            return if (id in open) Target.Tab(id) else Target.Error("Ref $ref is from tab $id, which is closed." + listed(tabs))
        }
        // 2. A tab given by number.
        args.optString("tab")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val id = raw.removePrefix("tab").trim().toIntOrNull() ?: return Target.Error("tab must be a tab number such as 3." + listed(tabs))
            return if (id in open) Target.Tab(id) else Target.Error("Tab $id is closed." + listed(tabs))
        }
        val default = defaultTab(tabs, front, chat)
        when (rule) {
            // 3. open without tab: a new tab.
            TargetRule.OPEN -> return Target.NewTab
            // 4. read(url): a tab already showing it, else a new tab.
            TargetRule.READ -> args.optString("url")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                // Not a web URL: an error before any tab opens.
                val url = webUrl(raw, default?.let(open::get)?.url) ?: return Target.Error(notWebUrl(raw))
                val matching = tabs.filter { t -> (t.urlChain + t.url).any { sameUrl(it, url) } }
                val pick = matching.firstOrNull { it.id == chat.current } ?: matching.maxByOrNull { it.lastUsedAt }
                return pick?.let { Target.Tab(it.id) } ?: Target.NewTab
            }
            // 5. web_search: the chat's last search tab while it is still on a result page.
            TargetRule.SEARCH -> {
                val t = chat.searchTab?.let(open::get)
                return if (t != null && SearchEngines.isResultPage(t.url)) Target.Tab(t.id) else Target.NewTab
            }
            else -> Unit
        }
        // 6, 7.
        return default?.let { Target.Tab(it) } ?: Target.Error(NO_TAB)
    }

    /**
     * Rule 6: the more recent of this chat's current tab and the tab the user last picked; with neither open, the tab
     * used last. Null when no tab is open.
     */
    fun defaultTab(tabs: List<TabInfo>, front: Front?, chat: ChatTabs): Int? {
        val ids = tabs.mapTo(HashSet()) { it.id }
        val mine = chat.current?.takeIf { it in ids }
        val users = front?.tabId?.takeIf { it in ids }
        return when {
            mine != null && users != null -> if (front.at >= chat.currentAt) users else mine
            else -> mine ?: users ?: tabs.maxByOrNull { it.lastUsedAt }?.id
        }
    }

    /** "\nOpen tabs: [1] Title — url; [2] …" for an error, or that none is open. */
    fun listed(tabs: List<TabInfo>): String =
        if (tabs.isEmpty()) " $NO_TAB" else "\nOpen tabs: " + tabs.joinToString("; ") { "[${it.id}] ${it.title.ifBlank { "(untitled)" }} — ${it.url}" }

    private val NON_WEB = Regex("^(javascript|data|file|about|blob|intent|mailto|tel|sms|content|chrome|market):", RegexOption.IGNORE_CASE)

    /** Like the address bar: a dot or a scheme and no spaces is a URL, anything else is searched for. */
    fun isUrl(s: String) = ("." in s || "://" in s || NON_WEB.containsMatchIn(s)) && s.none { it.isWhitespace() }

    /** A path from an element list or a page (G-m5): opened against the current page. */
    fun isPath(s: String) = (s.startsWith("/") || s.startsWith("./") || s.startsWith("../")) && s.none { it.isWhitespace() }

    /**
     * [raw] as a web URL: https:// added when the scheme is missing, a path resolved on [base]; null when it is not
     * one (another scheme, search words, a path without a web page to resolve it on).
     */
    fun webUrl(raw: String, base: String?): String? {
        val s = raw.trim()
        val url = when {
            isPath(s) -> base?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { runCatching { URI(it).resolve(s).toString() }.getOrNull() }
            !isUrl(s) -> null
            "://" in s || NON_WEB.containsMatchIn(s) -> s
            else -> "https://$s"
        } ?: return null
        val scheme = url.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") return null
        return url.takeIf { runCatching { URI(it).host }.getOrNull()?.isNotEmpty() == true }
    }

    /** Why [raw] is not opened. */
    fun notWebUrl(raw: String) =
        if (isPath(raw)) "$raw is a path and the tab has no web page to open it on; pass a full URL." else "Not an http(s) URL: $raw"

    private fun sameUrl(a: String, b: String) = a.substringBefore('#').trimEnd('/') == b.substringBefore('#').trimEnd('/')
}
