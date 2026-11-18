package com.ouor.localchat.ui.browser

import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.policy.Notice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FB-1c / 6-2: the Peek strip shows the tab this chat's run works on; FB-5 step order; policy notices (6-1). */
class BrowserStripTest {
    private val t = TabInfo(4, "t", "https://e.com/4")
    private fun peek(activity: Map<Int, ToolActivity>, running: Boolean = true, panel: PanelState = PanelState.Peek) =
        BrowserUiState(panel = panel, tabs = listOf(t), sessionId = "A", running = running, activity = activity)

    @Test fun `strip while this chat's run works on a tab`() {
        assertEquals(t, peek(mapOf(4 to ToolActivity("A", "click"))).stripTab)
    }

    @Test fun `no strip for another chat's work, an idle run or an open panel`() {
        assertNull(peek(mapOf(4 to ToolActivity("B", "click"))).stripTab)
        assertNull(peek(emptyMap()).stripTab)
        assertNull(peek(mapOf(4 to ToolActivity("A", "click")), running = false).stripTab)
        assertNull(peek(mapOf(4 to ToolActivity("A", "click")), panel = PanelState.Half).stripTab)
    }

    @Test fun `steps go peek half full and back`() {
        assertEquals(PanelState.Half, PanelState.Peek.up())
        assertEquals(PanelState.Full, PanelState.Half.up())
        assertEquals(PanelState.Half, PanelState.Full.down())
        assertEquals(PanelState.Peek, PanelState.Half.down())
    }

    @Test fun `a policy banner shows over its tab, badges go on their card, none without policies`() {
        val banner = Notice("siteBlock", "Sensitive site.", 4, Notice.Style.BANNER)
        val badge = Notice("x", "Paused", 4, Notice.Style.BADGE)
        val s = BrowserUiState(tabs = listOf(t), notices = listOf(badge, banner))
        assertEquals(banner, s.banner(4))
        assertNull(s.banner(5))
        assertEquals(listOf("Paused"), s.badges(4))
        assertEquals(emptyList<String>(), s.badges(5))
        assertNull(BrowserUiState(tabs = listOf(t)).banner(4))
        assertEquals(Notice("all", "Browser paused.", null, Notice.Style.BANNER), BrowserUiState(notices = listOf(Notice("all", "Browser paused.", null, Notice.Style.BANNER))).banner(9))
    }
}
