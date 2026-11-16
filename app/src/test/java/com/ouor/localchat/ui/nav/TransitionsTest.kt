package com.ouor.localchat.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
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

    /** Linear fades: alpha of the leaving and the entering page [ms] into the transition. */
    private fun NavMotionSpec.alphas(ms: Float): Pair<Float, Float> =
        (1f - ms / fadeOutMs).coerceIn(0f, 1f) to ((ms - fadeInDelayMs) / fadeInMs).coerceIn(0f, 1f)

    @Test fun `predictive back keeps a page visible at every gesture progress (FB2 ui M-1)`() {
        val s = navMotionSpec(NavMove.PredictiveBack, false)
        val back = navMotionSpec(NavMove.Back, false)
        assertEquals(back.enterFromDp, s.enterFromDp)
        assertEquals(back.exitToDp, s.exitToDp)
        assertEquals(back.slideMs, s.slideMs)
        // NavDisplay seeks to progress x the full 300ms.
        for (i in 0..100) {
            val (out, inn) = s.alphas(i / 100f * s.slideMs)
            assertTrue("progress $i%: $out + $inn", out + inn >= 0.99f)
        }
    }

    private fun scene(previous: List<String>, shown: List<String>) = object : Scene<String> {
        override val key: Any = shown
        override val entries = shown.map { k -> NavEntry(k) {} }
        override val previousEntries = previous.map { k -> NavEntry(k) {} }
        override val content: @Composable () -> Unit = {}
    }

    @Test fun `push vs stack swap is decided by the stack root, not by an empty history (FB2 ui m-2)`() {
        assertEquals(NavMove.Forward, pushMove(scene(emptyList(), listOf("chat")), scene(listOf("chat"), listOf("settings"))))
        // A two-pane scene shows root + detail with no previous entries: still a push.
        assertEquals(NavMove.Forward, pushMove(scene(emptyList(), listOf("chat")), scene(emptyList(), listOf("chat", "detail"))))
        assertEquals(NavMove.Replace, pushMove(scene(emptyList(), listOf("chat")), scene(emptyList(), listOf("chat2"))))
        assertEquals(NavMove.Replace, pushMove(scene(listOf("chat"), listOf("settings")), scene(emptyList(), listOf("chat2"))))
    }

    @Test fun `reduced motion is instant for every move`() {
        NavMove.entries.forEach { assertEquals(INSTANT, navMotionSpec(it, reduced = true)) }
    }
}
