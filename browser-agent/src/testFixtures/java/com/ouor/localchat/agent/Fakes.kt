package com.ouor.localchat.agent

import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The engine's narrow port without WebViews: tabs are rows; [tab] has no BrowserTab on the JVM. */
class FakeBrowserPort : BrowserPort {
    val tabs = MutableStateFlow<List<TabInfo>>(emptyList())
    var front: Front? = null
    var shown: Int? = null
    val activity = mutableListOf<Pair<Int, ToolActivity?>>()
    val closed = mutableListOf<Int>()
    val cookies = HashMap<String, String>()
    private var next = 40
    override fun tabs(): List<TabInfo> = tabs.value
    override fun front(): Front? = front
    override fun shown(): Int? = shown
    override suspend fun open(): Int = (++next).also { id -> tabs.value = tabs.value + TabInfo(id, "", "") }
    override suspend fun close(tabId: Int) {
        closed += tabId
        tabs.value = tabs.value.filter { it.id != tabId }
    }
    override fun setActivity(tabId: Int, activity: ToolActivity?) { this.activity += tabId to activity }
    override fun tab(tabId: Int): BrowserTab = throw IllegalStateException("no WebView on the JVM (tab $tabId)")
    override fun cookieHeader(url: String): String? = cookies[url]
    var clock = 0L
    override fun now(): Long = ++clock
}

/** A tool whose behaviour is a lambda. */
class FakeTool(
    override val name: String,
    override val facts: ToolFacts = ToolFacts(TargetRule.NONE, browser = false, changesPage = false),
    override val groups: Set<ToolGroup> = setOf(ToolGroup.SEARCH, ToolGroup.BROWSER),
    val body: suspend (JsonObject, ToolContext) -> ToolOutcome = { args, _ -> ToolOutcome(page = "ok ${args["q"]?.jsonPrimitive?.content}") },
) : Tool {
    var calls = 0
    override val description = "fake $name"
    override val parameters = JsonObject(emptyMap())
    override fun argSummary(args: JsonObject) = ""
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        calls++
        return body(args, ctx)
    }
}
