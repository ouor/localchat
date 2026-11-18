package com.ouor.localchat.tools

import com.ouor.localchat.tools.browser.AskUser
import com.ouor.localchat.tools.browser.Click
import com.ouor.localchat.tools.browser.CloseTab
import com.ouor.localchat.tools.browser.Execute
import com.ouor.localchat.tools.browser.Look
import com.ouor.localchat.tools.browser.Navigate
import com.ouor.localchat.tools.browser.Open
import com.ouor.localchat.tools.browser.Read
import com.ouor.localchat.tools.browser.Scroll
import com.ouor.localchat.tools.browser.Tabs
import com.ouor.localchat.tools.browser.Type
import com.ouor.localchat.tools.browser.WebSearch

/** Every tool (browser-layers 3-1). Adding a tool = one file in tools/browser + one line here. */
object ToolCatalog {
    val ALL: List<Tool> = listOf(WebSearch, Open, Navigate, Read, Look, Click, Type, Scroll, Execute, Tabs, CloseTab, AskUser)

    fun byName(name: String): Tool? = ALL.firstOrNull { it.name == name }
}
