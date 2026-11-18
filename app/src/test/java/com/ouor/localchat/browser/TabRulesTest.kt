package com.ouor.localchat.browser

import com.ouor.localchat.browser.TabRules.AI_IDLE_MS
import com.ouor.localchat.browser.TabRules.ReadyInput
import com.ouor.localchat.browser.TabRules.ReadyStep
import com.ouor.localchat.browser.script.CssRect
import com.ouor.localchat.browser.script.PageMark
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(true, TabRules.limitError(hidden = false, visible = max, hiddenOpen = 0)?.contains("Ask the user to close some"))
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

    @Test fun `a touch asks to take control only of an AI tab the AI controls`() { // FB4 QA F2
        assertEquals(true, TabRules.asksToTakeOver(Owner.Ai("A"), Control.AI))
        assertEquals(false, TabRules.asksToTakeOver(Owner.Ai("A"), Control.USER)) // handoff / already taken over
        assertEquals(false, TabRules.asksToTakeOver(Owner.User, Control.AI))
    }

    // --- page readiness (tools-redesign 2-1)

    private fun mark(n: Int = 1, quiet: Long = 0, text: Int = 1000, state: String = "complete") = PageMark("d", n, quiet, text, state)

    private fun input(
        now: Long, settleStart: Long? = null, loading: Boolean = false, mark: PageMark? = mark(), settleN: Int? = null,
        mainRequestAt: Long = 0, pageStartedAt: Long = 0, committedAt: Long = 0, lastRequestAt: Long = 0, errorAt: Long? = null,
        downloadAt: Long? = null, since: Long = 1_000, docStart: Long = since,
    ) = ReadyInput(now, since, docStart, settleStart, loading, mark, settleN, mainRequestAt, pageStartedAt, committedAt, lastRequestAt, errorAt, downloadAt)

    @Test
    fun `load phase waits for the response, then settles`() {
        // Requested at 1100, no response yet (slow first byte, E2): the old document answers mark() as complete.
        assertEquals(ReadyStep.Wait(TabRules.LOAD_POLL), TabRules.readyStep(input(2_000, mainRequestAt = 1_100, pageStartedAt = 500)))
        // Response in, document interactive.
        assertEquals(ReadyStep.Settle, TabRules.readyStep(input(2_000, loading = true, mark = mark(state = "interactive"), mainRequestAt = 1_100, pageStartedAt = 1_200)))
        assertEquals(ReadyStep.Wait(TabRules.LOAD_POLL), TabRules.readyStep(input(2_000, loading = true, mark = mark(state = "loading"), mainRequestAt = 1_100, pageStartedAt = 1_200)))
        // An action that loads nothing settles at once.
        assertEquals(ReadyStep.Settle, TabRules.readyStep(input(1_100)))
        assertEquals(ReadyStep.Done(Readiness.LOADING), TabRules.readyStep(input(1_000 + TabRules.LOAD_CAP, loading = true, mark = null)))
    }

    @Test
    fun `settle phase is ready after a quiet window, with enough text or a change`() {
        val s = 2_000L
        assertEquals(ReadyStep.Done(Readiness.READY), TabRules.readyStep(input(2_600, settleStart = s, mark = mark(quiet = 600), settleN = 1)))
        // Quiet but sparse and unchanged (E7): a new document may still draw itself, so it waits.
        assertTrue(TabRules.readyStep(input(2_600, settleStart = s, mark = mark(quiet = 600, text = 50), settleN = 1, pageStartedAt = 1_200)) is ReadyStep.Wait)
        // B-m4: no new document in this wait (a type, a re-list on a short form): quiet is enough.
        assertEquals(ReadyStep.Done(Readiness.READY), TabRules.readyStep(input(2_600, settleStart = s, mark = mark(quiet = 600, text = 50), settleN = 1)))
        assertEquals(ReadyStep.Done(Readiness.READY), TabRules.readyStep(input(2_600, settleStart = s, mark = mark(n = 2, quiet = 600, text = 50), settleN = 1)))
        // A request 100 ms ago keeps it busy.
        assertEquals(ReadyStep.Wait(400), TabRules.readyStep(input(2_600, settleStart = s, mark = mark(quiet = 600), settleN = 1, lastRequestAt = 2_500)))
        // At the cap: a short unchanged page is ready, a changing one is CHANGING.
        assertEquals(ReadyStep.Done(Readiness.READY), TabRules.readyStep(input(s + TabRules.SETTLE_CAP, settleStart = s, mark = mark(quiet = 600, text = 50), settleN = 1, pageStartedAt = 1_200)))
        assertEquals(ReadyStep.Done(Readiness.CHANGING), TabRules.readyStep(input(s + TabRules.SETTLE_CAP, settleStart = s, mark = mark(n = 9, quiet = 10), settleN = 1)))
        // A new document requested while settling (JS redirect) loads again.
        assertEquals(ReadyStep.Restart, TabRules.readyStep(input(2_600, settleStart = s, mainRequestAt = 2_100, settleN = 1)))
    }

    @Test
    fun `a main-frame error fails the wait, a refused download ends it, older ones count for nothing`() {
        assertEquals(ReadyStep.Done(Readiness.FAILED), TabRules.readyStep(input(1_500, errorAt = 1_200)))
        assertEquals(ReadyStep.Done(Readiness.READY), TabRules.readyStep(input(1_500, downloadAt = 1_200, loading = true, mark = null)))
        assertEquals(ReadyStep.Settle, TabRules.readyStep(input(1_500, errorAt = 900, downloadAt = 900)))
    }

    @Test
    fun `a navigation answered without a new document ends the load phase (B-m3)`() {
        // Same-page anchor, history push or a cancelled intent: onPageFinished / doUpdateVisitedHistory / the override.
        assertEquals(ReadyStep.Settle, TabRules.readyStep(input(1_500, mainRequestAt = 1_100, pageStartedAt = 500, committedAt = 1_150)))
        assertEquals(ReadyStep.Wait(TabRules.LOAD_POLL), TabRules.readyStep(input(1_500, mainRequestAt = 1_100, pageStartedAt = 500, committedAt = 900)))
    }

    // --- navigation errors (tools-redesign 2-2)

    private fun net(desc: String) = NavError(0, "https://a.test/", false, -2, desc)
    private fun http(code: Int) = NavError(0, "https://a.test/", true, code, "")

    @Test
    fun `only passing failures are retried, after their own delay`() {
        assertEquals(1_500L, TabRules.netRetry(net("net::ERR_NAME_NOT_RESOLVED")))
        assertEquals(1_500L, TabRules.netRetry(net("net::ERR_CONNECTION_RESET")))
        assertNull(TabRules.netRetry(net("net::ERR_CERT_DATE_INVALID")))
        assertEquals(2_000L, TabRules.netRetry(http(503)))
        assertEquals(2_000L, TabRules.netRetry(http(429)))
        assertNull(TabRules.netRetry(http(404)))
    }

    @Test
    fun `error texts say what happened and whether to retry`() {
        assertEquals("https://a.test/ answered HTTP 404 (not found). Do not retry this URL.", TabRules.navErrorText(http(404), false))
        assertTrue(TabRules.navErrorText(http(403), false).contains("sign-in"))
        assertTrue(TabRules.navErrorText(http(503), true).endsWith("already retried once."))
        assertEquals(
            "Could not open https://a.test/: the site's address was not found (net::ERR_NAME_NOT_RESOLVED). Already retried once; try again later or use another source.",
            TabRules.navErrorText(net("net::ERR_NAME_NOT_RESOLVED"), true),
        )
        assertTrue(TabRules.navErrorText(net("net::ERR_CERT_AUTHORITY_INVALID"), false).contains("certificate"))
    }

    // --- read sizes (tools-redesign 3-2, 5-3)

    @Test
    fun `read part and budget follow the context window`() {
        assertEquals(12_000, TabRules.readChunk(null))
        assertEquals(4_000, TabRules.readChunk(8_000))
        assertEquals(8_000, TabRules.readChunk(32_000))
        assertEquals(20_000, TabRules.readChunk(200_000))
        assertEquals(40_000, TabRules.readKeep(null))
        assertEquals(4_000, TabRules.readKeep(4_000)) // never below one part
        assertEquals(19_200, TabRules.readKeep(32_000))
        assertEquals(40_000, TabRules.readKeep(1_000_000))
    }

    @Test
    fun `parts are cut after a blank line when there is one`() {
        assertEquals(listOf(""), TabRules.parts("", 10))
        assertEquals(listOf("aaaa", "bbbb"), TabRules.parts("aaaa\n\nbbbb", 8))
        assertEquals(listOf("abcde", "fgh"), TabRules.parts("abcdefgh", 5)) // no blank line: a hard cut
        val text = (1..50).joinToString("\n\n") { "para $it" }
        assertEquals(text.replace("\n", ""), TabRules.parts(text, 37).joinToString("") { it.replace("\n", "") })
        assertTrue(TabRules.parts(text, 37).all { it.length <= 37 })
    }
}
