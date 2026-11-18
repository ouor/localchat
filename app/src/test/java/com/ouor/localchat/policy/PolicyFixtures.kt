package com.ouor.localchat.policy

import com.ouor.localchat.browser.BrowserFacts
import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.Handled
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.browser.script.CssRect
import com.ouor.localchat.browser.script.ElementInfo
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.ToolFacts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Strings as "id(args)": tests check which string a policy picked, not its wording. */
object FakeTexts : Texts {
    override fun get(id: Int, vararg args: Any) = "$id${args.toList()}"
    override fun plural(id: Int, count: Int, vararg args: Any) = "$id#$count"
}

/** The narrow browser view without a browser; [discarded] records discard calls. */
class FakeFacts(tabs: List<TabInfo> = emptyList()) : BrowserFacts {
    override val tabs = MutableStateFlow(tabs)
    override val front = MutableStateFlow<Front?>(null)
    override val shown = MutableStateFlow<Int?>(null)
    override val activity = MutableStateFlow<Map<Int, ToolActivity>>(emptyMap())
    override val events = MutableSharedFlow<Handled>(extraBufferCapacity = 16)
    val discarded = mutableListOf<Int>()
    var cookies: Map<String, String> = emptyMap()

    override fun discard(tabId: Int): Boolean {
        val t = tabs.value.firstOrNull { it.id == tabId && it.asleep == null } ?: return false
        discarded += tabId
        tabs.value = tabs.value.map { if (it.id == tabId) t.copy(asleep = com.ouor.localchat.browser.Asleep.DISCARDED) else it }
        return true
    }

    override fun cookieHeader(url: String): String? = cookies[url]
}

fun tab(id: Int, url: String = "https://e$id.example/", lastUsedAt: Long = id.toLong(), pending: Boolean = false) =
    TabInfo(id, "Tab $id", url, lastUsedAt = lastUsedAt, pending = pending)

val TAB_1 = tab(1)
val PAGE_TOOL = ToolFacts(TargetRule.PAGE, browser = true, changesPage = false)
val REF_TOOL = ToolFacts(TargetRule.REF, browser = true, changesPage = true)

fun located(tag: String, role: String?, name: String, type: String? = null, frame: String? = null, submit: String? = null, search: Boolean = false) =
    Located(ElementInfo(tag, role, type, name, search = search), CssRect(0f, 0f, 10f, 10f), 400f, 5f, 5f, hit = true,
        editable = tag == "input" || tag == "textarea", disabled = false, formSubmitLabel = submit, frame = frame)

fun call(
    tool: String = "read",
    facts: ToolFacts = PAGE_TOOL,
    args: Map<String, String> = emptyMap(),
    tab: TabInfo? = TAB_1,
    session: String = "s",
    describe: Located? = null,
) = PolicyCall(session, tool, facts, JsonObject(args.mapValues { JsonPrimitive(it.value) }), 0L, tab, { describe }, { null })

fun facts(tabAfter: TabInfo? = tab(1), mainRequests: List<String> = emptyList(), cookies: (String) -> String? = { null }) =
    CallFacts(tabAfter, emptyList(), mainRequests, cookies, emptyList())
