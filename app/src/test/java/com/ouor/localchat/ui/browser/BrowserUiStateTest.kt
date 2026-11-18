package com.ouor.localchat.ui.browser

import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.RunPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** browser-layers 6: the drawer shows every tab and follows only the user's picks (P-STABLE). */
class BrowserUiStateTest {
    private fun tab(id: Int, used: Long = id.toLong()) = TabInfo(id, "t$id", "https://e.com/$id", lastUsedAt = used)

    private val a = tab(1)
    private val b = tab(2)
    private val c = tab(3)

    @Test fun `every tab shows, the front first, else the tab used last`() {
        assertEquals(b, BrowserUiState(tabs = listOf(a, b, c), front = 2).current)
        assertEquals(c, BrowserUiState(tabs = listOf(a, b, c)).current)
        assertEquals(c, BrowserUiState(tabs = listOf(a, c), front = 2).current) // a closed front falls back
        assertNull(BrowserUiState().current)
    }

    @Test fun `tab list slots keep a closed tab's place, refresh live ones and append new ones`() {
        // P-STABLE L8 / FB3 arch m7: computed without mutating anything during composition.
        val slots = tabSlots(listOf(a, b, c), listOf(a.copy(title = "new"), c, tab(4)))
        assertEquals(listOf(1, 2, 3, 4), slots.map { it.id })
        assertEquals("new", slots[0].title)
        assertEquals(b, slots[1]) // closed: placeholder with its old size-giving text
        assertEquals(true, slots.all { it.thumbnail == null })
    }

    @Test fun `the shown tab's event is asked in place, another tab's is a banner`() {
        val download = BrowserEvent.Download(1, 3, 0, "https://e.com/f", "a/b", 1, "f")
        val s = BrowserUiState(tabs = listOf(a, b, c), front = 2, prompts = listOf(download))
        assertNull(s.prompt)
        assertEquals(download, s.otherPrompt)
        val here = download.copy(tabId = 2)
        assertEquals(here, s.copy(prompts = listOf(download, here)).prompt)
    }

    @Test fun `a running tool never changes the shown tab and peeks only from Hidden`() {
        val picks = mutableListOf<Int>()
        val running = RunPhase.RunningTool("c", "click", tabId = 3)
        val hidden = BrowserUiState(tabs = listOf(a, b, c), front = 1).onPhase(running) { picks += it }
        assertEquals(PanelState.Peek, hidden.panel)
        assertEquals(1, hidden.front)
        val half = BrowserUiState(panel = PanelState.Half, tabs = listOf(a, b, c), front = 1).onPhase(running) { picks += it }
        assertEquals(PanelState.Half, half.panel)
        assertEquals(1, half.front)
        assertEquals(emptyList<Int>(), picks)
        // The auto-peek goes with the run; an opened panel stays.
        assertEquals(PanelState.Hidden, hidden.onPhase(null) { picks += it }.panel)
        assertEquals(PanelState.Half, half.onPhase(null) { picks += it }.panel)
    }

    @Test fun `an ask_user wait opens its tab Full from Hidden, else it is a banner`() {
        val step = Pause.UserStep(3, "Sign in")
        val picks = mutableListOf<Int>()
        val fromHidden = BrowserUiState(tabs = listOf(a, b, c), front = 1).onPhase(RunPhase.Paused(step)) { picks += it }
        assertEquals(PanelState.Full, fromHidden.panel)
        assertEquals(listOf(3), picks)
        assertEquals(step, fromHidden.userStep)
        val open = BrowserUiState(panel = PanelState.Half, tabs = listOf(a, b, c), front = 1).onPhase(RunPhase.Paused(step)) { picks += it }
        assertEquals(PanelState.Half, open.panel)
        assertEquals(1, open.front)
        assertEquals(listOf(3), picks)
        assertEquals(step, open.userStep)
        // The same pause seen again does not pick again; the run going on clears it.
        assertEquals(listOf(3), picks.also { fromHidden.onPhase(RunPhase.Paused(step)) { picks += it } })
        assertNull(open.onPhase(RunPhase.Streaming) { picks += it }.userStep)
    }

    @Test fun `the activity of each tab is kept with its chat`() {
        val s = BrowserUiState(tabs = listOf(a, b), sessionId = "A", activity = mapOf(2 to ToolActivity("B", "read")))
        assertEquals("B", s.activity[2]?.sessionId)
    }

    // FB5 B1 (review ui M2 / arch M1): the keyboard's return height is ViewModel state, and a height set meanwhile wins.
    @Test fun `a keyboard for the panel goes Full and back to the user's height`() {
        val up = BrowserUiState(panel = PanelState.Half).keyboard(true)
        assertEquals(PanelState.Full, up.panel)
        assertEquals(PanelState.Half, up.keyboard(false).panel)
        assertNull(up.keyboard(false).keyboardFrom)
    }

    @Test fun `a keyboard at Full remembers nothing`() {
        assertEquals(PanelState.Full, BrowserUiState(panel = PanelState.Full).keyboard(true).keyboard(false).panel)
    }

    @Test fun `a user step while the keyboard is up keeps Full`() {
        val s = BrowserUiState(panel = PanelState.Half).keyboard(true).withPanel(PanelState.Full)
        assertEquals(PanelState.Full, s.keyboard(false).panel)
    }

    @Test fun `a height the user drops the panel at wins`() {
        val s = BrowserUiState(panel = PanelState.Half).keyboard(true).withPanel(PanelState.Peek)
        assertEquals(PanelState.Peek, s.keyboard(false).panel)
    }
}
