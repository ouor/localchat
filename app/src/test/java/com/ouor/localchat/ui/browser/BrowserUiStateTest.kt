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
}
