package com.ouor.localchat.browser

import com.ouor.localchat.browser.script.CssRect

/** Pure rules behind BrowserEngine/BrowserTab, kept apart so JVM tests can run them. */
internal object TabRules {

    /** At most this many idle AI tabs of other chats stay open (BUG-13). */
    const val MAX_IDLE_AI_TABS = 3

    /** An AI tab unused this long counts as idle. */
    const val AI_IDLE_MS = 10 * 60_000L

    /** CSS px around the element in an approval preview. */
    private const val CROP_PAD = 24f

    /** Ids to close, oldest first, from [lastUse] (tab id -> last AI use) of the eligible tabs. */
    fun idleAiTabsToClose(lastUse: Map<Int, Long>, now: Long): List<Int> {
        val idle = lastUse.entries.filter { now - it.value >= AI_IDLE_MS }.sortedBy { it.value }.map { it.key }
        return idle.take((idle.size - MAX_IDLE_AI_TABS).coerceAtLeast(0))
    }

    /** [x, y, w, h] in view px of [rect] scaled by [scale] plus padding, clipped to the view; null if nothing is left. */
    fun cropBox(rect: CssRect, scale: Float, viewW: Int, viewH: Int): IntArray? {
        val l = ((rect.left - CROP_PAD) * scale).toInt().coerceIn(0, viewW)
        val t = ((rect.top - CROP_PAD) * scale).toInt().coerceIn(0, viewH)
        val r = ((rect.left + rect.width + CROP_PAD) * scale).toInt().coerceIn(0, viewW)
        val b = ((rect.top + rect.height + CROP_PAD) * scale).toInt().coerceIn(0, viewH)
        return if (r - l < 1 || b - t < 1) null else intArrayOf(l, t, r - l, b - t)
    }
}
