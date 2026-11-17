package com.ouor.localchat.browser

import com.ouor.localchat.browser.script.CssRect

/** Pure rules behind BrowserEngine/BrowserTab, kept apart so JVM tests can run them. */
internal object TabRules {

    /** B-09 ③: a user touch on an AI tab offers "take control", unless the user already controls it (handoff, QA F2). */
    fun asksToTakeOver(owner: Owner, control: Control): Boolean = owner is Owner.Ai && control == Control.AI

    /** At most this many idle AI tabs of other chats stay open (BUG-13). */
    const val MAX_IDLE_AI_TABS = 3

    /** An AI tab unused this long counts as idle. */
    const val AI_IDLE_MS = 10 * 60_000L

    /** CSS px around the element in an approval preview. */
    private const val CROP_PAD = 24f

    /** Hidden link-prefetch tabs open at once (LinkPrefetcher reads at most 3 links, one at a time). */
    const val MAX_HIDDEN_TABS = 3

    /**
     * Why a new tab may not open, null when it may. Visible tabs count toward [BrowserEngine.MAX_TABS]; hidden ones
     * only toward [MAX_HIDDEN_TABS], so a prefetch never takes a slot from the user (FB3 arch M1).
     */
    fun limitError(hidden: Boolean, visible: Int, hiddenOpen: Int): String? = when {
        hidden && hiddenOpen >= MAX_HIDDEN_TABS -> "Too many pages are being read at once ($MAX_HIDDEN_TABS)."
        !hidden && visible >= BrowserEngine.MAX_TABS -> "Too many tabs are open (${BrowserEngine.MAX_TABS}). Close unused tabs with close_tab."
        else -> null
    }

    /** Size for a parked WebView: the host's once laid out, else the window's, else the display's. */
    fun fallbackSize(hostW: Int, hostH: Int, windowW: Int?, windowH: Int?, displayW: Int, displayH: Int): Pair<Int, Int> =
        if (hostW > 0 && hostH > 0) hostW to hostH
        else if (windowW != null && windowH != null && windowW > 0 && windowH > 0) windowW to windowH
        else displayW to displayH

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
