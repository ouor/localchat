package com.ouor.localchat.ui.browser

import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.TabInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FB-1c: the Peek strip shows only while this chat's run operates its tab; FB-5 step order. */
class BrowserStripTest {
    private fun tab(owner: Owner, control: Control = Control.AI) = TabInfo(1, "t", "https://e.com/1", owner, control, loading = false)
    private fun peek(t: TabInfo, running: Boolean = true, panel: PanelState = PanelState.Peek) =
        BrowserUiState(panel = panel, tabs = listOf(t), sessionId = "A", running = running)

    @Test fun `strip while this chat's run operates the tab`() {
        val t = tab(Owner.Ai("A"))
        assertEquals(t, peek(t).stripTab)
    }

    @Test fun `no strip for a user tab, an idle run, user control or an open panel`() {
        assertNull(peek(tab(Owner.User)).stripTab)
        assertNull(peek(tab(Owner.Ai("A")), running = false).stripTab)
        assertNull(peek(tab(Owner.Ai("A"), Control.USER)).stripTab)
        assertNull(peek(tab(Owner.Ai("A")), panel = PanelState.Half).stripTab)
    }

    @Test fun `steps go peek half full and back`() {
        assertEquals(PanelState.Half, PanelState.Peek.up())
        assertEquals(PanelState.Full, PanelState.Half.up())
        assertEquals(PanelState.Half, PanelState.Full.down())
        assertEquals(PanelState.Peek, PanelState.Half.down())
    }
}
