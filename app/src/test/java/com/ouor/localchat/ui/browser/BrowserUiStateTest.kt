package com.ouor.localchat.ui.browser

import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.TabInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BUG-04: a chat's panel defaults to its own AI tabs and user tabs, never another chat's. */
class BrowserUiStateTest {
    private fun tab(id: Int, owner: Owner) = TabInfo(id, "t$id", "https://e.com/$id", owner, Control.AI, loading = false)

    private val user = tab(1, Owner.User)
    private val mine = tab(2, Owner.Ai("A"))
    private val other = tab(3, Owner.Ai("B"))

    @Test fun `default tab skips other chats`() {
        val s = BrowserUiState(tabs = listOf(user, mine, other), sessionId = "A")
        assertEquals(listOf(user, mine), s.visible)
        assertEquals(mine, s.current)
    }

    @Test fun `new chat sees no AI tabs`() {
        assertNull(BrowserUiState(tabs = listOf(mine, other), sessionId = null).current)
    }

    @Test fun `picked tab wins and a closed pick falls back`() {
        assertEquals(other, BrowserUiState(tabs = listOf(user, other), sessionId = "A", currentId = 3).current)
        assertEquals(user, BrowserUiState(tabs = listOf(user), sessionId = "A", currentId = 3).current)
    }

    @Test fun `tab list slots keep a closed tab's place, refresh live ones and append new ones`() {
        // P-STABLE L8 / FB3 arch m7: computed without mutating anything during composition.
        val slots = tabSlots(listOf(user, mine, other), listOf(user.copy(title = "new"), other, tab(4, Owner.User)))
        assertEquals(listOf(1, 2, 3, 4), slots.map { it.id })
        assertEquals("new", slots[0].title)
        assertEquals(mine, slots[1]) // closed: placeholder with its old size-giving text
        assertEquals(true, slots.all { it.thumbnail == null })
    }

    @Test fun `handedBack does not touch the panel height`() { // FB4 U-3; giveBack itself has no VM test (BrowserEngine and ChatEngine are concrete Android classes)
        val handoff = com.ouor.localchat.chat.run.Pause.Handoff(2, "login")
        for (p in listOf(PanelState.Full, PanelState.Half)) {
            val after = BrowserUiState(panel = p, tabs = listOf(mine), sessionId = "A", handoff = handoff).handedBack()
            assertEquals(p, after.panel)
            assertNull(after.handoff)
        }
    }

    @Test fun `a prompt of a tab outside the visible list never reaches the panel`() {
        // The engine hands the panel visibleTabs only (hidden link-prefetch tabs filtered there, FB3 arch M1).
        val download = com.ouor.localchat.browser.BrowserPrompt.Download(1, 9, "https://e.com/f", "f", "a/b", 1)
        val s = BrowserUiState(sessionId = "A").merge(listOf(user, mine), emptyMap(), null, listOf(download))
        assertEquals(mine, s.current)
        assertNull(s.prompt)
        val userDownload = download.copy(tabId = 1)
        assertEquals(userDownload, BrowserUiState().merge(listOf(user), emptyMap(), null, listOf(download, userDownload)).prompt)
    }
}
