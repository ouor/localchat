package com.ouor.localchat.browser

import com.ouor.localchat.browser.TabRules.AI_IDLE_MS
import com.ouor.localchat.browser.script.CssRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabRulesTest {

    @Test
    fun `only idle tabs beyond the cap close, oldest first (BUG-13)`() {
        val now = 100 * AI_IDLE_MS
        val old = (1..5).associateWith { now - AI_IDLE_MS - it * 1_000L } // tab 5 oldest
        assertEquals(listOf(5, 4), TabRules.idleAiTabsToClose(old, now))
        val recent = old + (6 to now - 1_000L) + (7 to now)
        assertEquals(listOf(5, 4), TabRules.idleAiTabsToClose(recent, now)) // recently used tabs never count
        assertEquals(emptyList<Int>(), TabRules.idleAiTabsToClose(old.filterKeys { it <= 3 }, now))
    }

    @Test
    fun `hidden prefetch tabs neither count toward nor are blocked by the visible tab limit (FB3 arch M1)`() {
        val max = BrowserEngine.MAX_TABS
        assertNull(TabRules.limitError(hidden = false, visible = max - 1, hiddenOpen = 3)) // 3 prefetches never block the user
        assertNull(TabRules.limitError(hidden = true, visible = max, hiddenOpen = 0)) // a full panel never blocks a prefetch
        assertEquals(true, TabRules.limitError(hidden = false, visible = max, hiddenOpen = 0)?.contains("close_tab"))
        assertEquals(true, TabRules.limitError(hidden = true, visible = 0, hiddenOpen = TabRules.MAX_HIDDEN_TABS) != null)
    }

    @Test
    fun `a parked WebView takes the window size before the display size (FB3 eng m6)`() {
        assertEquals(800 to 600, TabRules.fallbackSize(800, 600, 1000, 900, 1080, 2400))
        assertEquals(1000 to 900, TabRules.fallbackSize(0, 0, 1000, 900, 1080, 2400)) // split screen: the window, not the display
        assertEquals(1080 to 2400, TabRules.fallbackSize(0, 0, null, null, 1080, 2400))
    }

    @Test
    fun `crop box scales, pads and clips to the view (SC-13)`() {
        assertArrayEquals(intArrayOf(152, 52, 296, 136), TabRules.cropBox(CssRect(100f, 50f, 100f, 20f), 2f, 1080, 2000))
        assertArrayEquals(intArrayOf(0, 0, 48, 48), TabRules.cropBox(CssRect(-10f, -10f, 10f, 10f), 2f, 1080, 2000))
        assertNull(TabRules.cropBox(CssRect(0f, 5000f, 10f, 10f), 2f, 1080, 2000)) // off screen
    }
}
