package com.ouor.localchat.tools

import com.ouor.localchat.tools.browser.Browse
import com.ouor.localchat.tools.browser.Click
import com.ouor.localchat.tools.browser.Execute
import com.ouor.localchat.tools.browser.Read
import com.ouor.localchat.tools.browser.RequestHandoff
import com.ouor.localchat.tools.browser.Type
import com.ouor.localchat.tools.browser.WebSearch

/** Every tool (tools-redesign 1). Adding a tool = one file in tools/browser + one line here (architecture 6.2). */
object ToolCatalog {
    val ALL: List<Tool> = listOf(WebSearch, Read, Browse, Click, Type, Execute, RequestHandoff)

    fun byName(name: String): Tool? = ALL.firstOrNull { it.name == name }
}
