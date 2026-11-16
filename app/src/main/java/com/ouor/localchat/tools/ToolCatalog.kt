package com.ouor.localchat.tools

import com.ouor.localchat.tools.browser.Click
import com.ouor.localchat.tools.browser.CloseTab
import com.ouor.localchat.tools.browser.ConsoleLogs
import com.ouor.localchat.tools.browser.DialogHandle
import com.ouor.localchat.tools.browser.Execute
import com.ouor.localchat.tools.browser.GetTabs
import com.ouor.localchat.tools.browser.Hover
import com.ouor.localchat.tools.browser.Keyboard
import com.ouor.localchat.tools.browser.Navigate
import com.ouor.localchat.tools.browser.OpenTab
import com.ouor.localchat.tools.browser.PageText
import com.ouor.localchat.tools.browser.PrintToPdf
import com.ouor.localchat.tools.browser.RequestHandoff
import com.ouor.localchat.tools.browser.Screenshot
import com.ouor.localchat.tools.browser.Scroll
import com.ouor.localchat.tools.browser.SelectOption
import com.ouor.localchat.tools.browser.Snapshot
import com.ouor.localchat.tools.browser.Type
import com.ouor.localchat.tools.browser.WaitForNavigation
import com.ouor.localchat.tools.browser.WaitForSelector
import com.ouor.localchat.tools.browser.WebSearch

/** Every tool. Adding a tool = one file in tools/browser + one line here (architecture 6.2). */
object ToolCatalog {
    val ALL: List<Tool> = listOf(
        WebSearch,
        OpenTab,
        Navigate,
        PageText,
        CloseTab,
        RequestHandoff,
        GetTabs,
        Snapshot,
        Screenshot,
        Click,
        Type,
        Keyboard,
        Scroll,
        Hover,
        SelectOption,
        WaitForSelector,
        WaitForNavigation,
        Execute,
        DialogHandle,
        ConsoleLogs,
        PrintToPdf,
    )

    fun byName(name: String): Tool? = ALL.firstOrNull { it.name == name }
}
