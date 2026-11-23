package com.ouor.localchat.browser

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The narrow view of the browser a layer-3 policy gets (browser-layers 2-2, S1): facts plus what the user can do too. */
interface BrowserFacts {
    val tabs: StateFlow<List<TabInfo>>
    val front: StateFlow<Front?>
    val shown: StateFlow<Int?>
    val activity: StateFlow<Map<Int, ToolActivity>>
    val events: SharedFlow<Handled>

    /** Puts a tab to sleep (Chrome's tab discard); false when it is asleep already, gone, or a tool holds it. */
    fun discard(tabId: Int): Boolean

    /** The cookies the page at [url] can see (`a=1; b=2`), null when none. */
    fun cookieHeader(url: String): String?
}

/** The tab the user last picked, and when (epoch ms). */
data class Front(val tabId: Int, val at: Long)

/** A tool running on a tab right now: coordination only, layer 1 never branches on it. */
data class ToolActivity(val sessionId: String, val tool: String)

enum class Asleep { DISCARDED, CRASHED }

data class TabInfo(
    /** Never used twice, across restarts too: the model's "tab 3". */
    val id: Int,
    val title: String,
    val url: String,
    /** The first URL the current document was requested at, then every redirect on the way (browser-layers S10). */
    val urlChain: List<String> = emptyList(),
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** [BrowserTab.THUMB_WIDTH] px wide, null until the page was first drawn. Never recycle it. */
    val thumbnail: android.graphics.Bitmap? = null,
    /** null = awake. */
    val asleep: Asleep? = null,
    /** Epoch ms the tab was last picked, loaded or acted on. */
    val lastUsedAt: Long = 0,
    /** An event of the tab waits for the user (dialog, permission, file chooser). */
    val pending: Boolean = false,
) {
    /** Plain http page: the address bar shows a warning. */
    val insecure: Boolean get() = url.startsWith("http://", ignoreCase = true)
}
