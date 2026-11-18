package com.ouor.localchat.tools

import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.tools.Targets.Target
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** browser-layers 3-2, rules 1–8. */
class TargetsTest {
    private fun tab(id: Int, url: String = "https://e$id.test/", chain: List<String> = emptyList(), used: Long = id.toLong()) =
        TabInfo(id, "Tab $id", url, urlChain = chain, lastUsedAt = used)

    private val tabs = listOf(tab(1), tab(2), tab(3, "https://www.bing.com/search?q=x"))
    private fun args(vararg kv: Pair<String, String>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })
    private fun chat(current: Int? = null, at: Long = 0) = ChatTabs().also { c -> current?.let { c.used(it, at) } }
    private fun resolve(rule: TargetRule, a: JsonObject = args(), front: Front? = null, c: ChatTabs = chat(), t: List<TabInfo> = tabs) =
        Targets.resolve(rule, a, t, front, c)

    @Test fun `1 a ref names its tab, a closed or old-style ref is an error with the open tabs`() {
        assertEquals(Target.Tab(2), resolve(TargetRule.REF, args("ref" to "2e7")))
        // scroll is a PAGE tool; with ref the ref's tab wins over tab.
        assertEquals(Target.Tab(2), resolve(TargetRule.PAGE, args("ref" to "2e7", "tab" to "1")))
        val closed = resolve(TargetRule.REF, args("ref" to "9e7")) as Target.Error
        assertTrue(closed.message, closed.message.startsWith("Ref 9e7 is from tab 9, which is closed.\nOpen tabs: [1] Tab 1"))
        val old = resolve(TargetRule.REF, args("ref" to "e7")) as Target.Error
        assertTrue(old.message, old.message.startsWith("e7 is not a ref of an open tab"))
    }

    @Test fun `2 a given tab, a closed one is an error`() {
        assertEquals(Target.Tab(1), resolve(TargetRule.PAGE, args("tab" to "1")))
        assertEquals(Target.Tab(1), resolve(TargetRule.OPEN, args("tab" to "1", "url" to "x.test")))
        assertTrue((resolve(TargetRule.PAGE, args("tab" to "7")) as Target.Error).message.startsWith("Tab 7 is closed."))
    }

    @Test fun `3 open without tab is a new tab`() {
        assertEquals(Target.NewTab, resolve(TargetRule.OPEN, args("url" to "x.test"), c = chat(1)))
    }

    @Test fun `4 read with url goes to a tab showing it, else a new tab`() {
        val t = listOf(tab(1, "https://a.test/final", chain = listOf("https://a.test/start")), tab(2, "https://b.test/p#frag", used = 9))
        assertEquals(Target.Tab(1), resolve(TargetRule.READ, args("url" to "https://a.test/start"), t = t))
        assertEquals(Target.Tab(2), resolve(TargetRule.READ, args("url" to "b.test/p"), t = t))
        assertEquals(Target.NewTab, resolve(TargetRule.READ, args("url" to "https://c.test/"), t = t))
        // Several: this chat's current tab, then the one used last.
        val same = listOf(tab(1, "https://a.test/", used = 5), tab(2, "https://a.test/", used = 1), tab(3, "https://a.test/", used = 3))
        assertEquals(Target.Tab(2), resolve(TargetRule.READ, args("url" to "https://a.test/"), c = chat(2), t = same))
        assertEquals(Target.Tab(1), resolve(TargetRule.READ, args("url" to "https://a.test/"), t = same))
        // Without url: the default tab.
        assertEquals(Target.Tab(2), resolve(TargetRule.READ, c = chat(2)))
    }

    @Test fun `5 web_search reuses its tab while it is on a result page`() {
        val c = chat().also { it.searchTab = 3 }
        assertEquals(Target.Tab(3), resolve(TargetRule.SEARCH, c = c))
        assertEquals(Target.NewTab, resolve(TargetRule.SEARCH, c = chat().also { it.searchTab = 1 }))
        assertEquals(Target.NewTab, resolve(TargetRule.SEARCH, c = chat().also { it.searchTab = 9 }))
    }

    @Test fun `6 the more recent of the chat's current tab and the user's pick`() {
        assertEquals(Target.Tab(1), resolve(TargetRule.PAGE, front = Front(2, at = 10), c = chat(1, at = 20)))
        assertEquals(Target.Tab(2), resolve(TargetRule.PAGE, front = Front(2, at = 30), c = chat(1, at = 20)))
        assertEquals(Target.Tab(2), resolve(TargetRule.PAGE, front = Front(2, at = 30)))
        // A closed current tab does not count; with neither, the tab used last.
        assertEquals(Target.Tab(3), resolve(TargetRule.PAGE, c = chat(9, at = 50)))
    }

    @Test fun `7 no tab at all, and NONE tools need none`() {
        assertEquals(Target.Error(Targets.NO_TAB), resolve(TargetRule.PAGE, t = emptyList()))
        assertEquals(Target.None, resolve(TargetRule.NONE, args("tab" to "9")))
        assertEquals(Target.NewTab, resolve(TargetRule.SEARCH, t = emptyList()))
    }

    @Test fun `8 the call's tab becomes the current tab`() {
        val c = chat(1, at = 5)
        c.used(3, at = 6)
        assertEquals(3, c.current)
        assertEquals(Target.Tab(3), resolve(TargetRule.PAGE, front = Front(1, at = 5), c = c))
    }
}
