package com.ouor.localchat.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FB2-3 transition specs. */
class TransitionsTest {

    @Test fun `push is shared axis X - enters from the end, leaves toward the start, 300ms`() {
        val s = navMotionSpec(NavMove.Forward, reduced = false)
        assertEquals(30, s.enterFromDp)
        assertEquals(-30, s.exitToDp)
        assertEquals(300, s.slideMs)
        assertEquals(90, s.fadeOutMs)
        assertEquals(90, s.fadeInDelayMs)
        assertEquals(210, s.fadeInMs)
    }

    @Test fun `pop mirrors push`() {
        val push = navMotionSpec(NavMove.Forward, false)
        val pop = navMotionSpec(NavMove.Back, false)
        assertEquals(-push.enterFromDp, pop.enterFromDp)
        assertEquals(-push.exitToDp, pop.exitToDp)
        assertEquals(push.copy(enterFromDp = pop.enterFromDp, exitToDp = pop.exitToDp), pop)
    }

    @Test fun `chat swap is a short fade through without a slide`() {
        val s = navMotionSpec(NavMove.Replace, false)
        assertEquals(0, s.slideMs)
        assertEquals(0, s.enterFromDp)
        assertEquals(0, s.exitToDp)
        assertEquals(150, s.fadeInDelayMs + s.fadeInMs)
        assertTrue(s.fadeOutMs <= s.fadeInDelayMs)
    }

    @Test fun `reduced motion is instant for every move`() {
        NavMove.entries.forEach { assertEquals(INSTANT, navMotionSpec(it, reduced = true)) }
    }
}
