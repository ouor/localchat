package com.ouor.localchat.policy

import android.content.ComponentCallbacks2
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.BrowserFacts
import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps at most K tabs awake (browser-layers 4-2, S3, S15): the least recently used others are put to sleep with
 * layer 1's discard, never closed or refused. The front tab, the shown one, tabs a tool works on and tabs waiting
 * for the user stay awake. The system killing the renderer for memory lowers K for good (min [MIN_K]); a memory
 * signal puts every other tab to sleep. [attach]'s scope runs on the main thread (discard is main-only).
 */
class TabMemoryPolicy(startK: Int, saved: Int?, private val save: (Int) -> Unit) : BrowserPolicy {

    @Volatile var k: Int = saved ?: startK
        private set

    override fun attach(browser: BrowserFacts, scope: CoroutineScope) {
        scope.launch {
            combine(browser.tabs, browser.front, browser.shown, browser.activity) { tabs, front, shown, act ->
                toDiscard(tabs, front, shown, act, k)
            }.collect { ids -> ids.forEach(browser::discard) }
        }
        scope.launch {
            browser.events.collect { h ->
                when (val e = h.event) {
                    is BrowserEvent.RendererGone -> if (!e.crashed && k > MIN_K) { k -= 1; save(k) }
                    is BrowserEvent.MemoryPressure -> if (e.level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                        toDiscard(browser.tabs.value, browser.front.value, browser.shown.value, browser.activity.value, 0).forEach(browser::discard)
                    }
                    else -> Unit
                }
            }
        }
    }

    companion object {
        const val MIN_K = 2

        /** Half the device's RAM in GB, 3 to 6 tabs (추정; QA measures renderer memory). */
        fun startK(totalMemBytes: Long): Int = (totalMemBytes / 1_073_741_824.0 / 2).roundToInt().coerceIn(3, 6)

        /** The awake tabs past [k] to put to sleep, least recently used first, never an excluded one. */
        fun toDiscard(tabs: List<TabInfo>, front: Front?, shown: Int?, activity: Map<Int, ToolActivity>, k: Int): List<Int> {
            val awake = tabs.filter { it.asleep == null }
            if (awake.size <= k) return emptyList()
            return awake.filter { it.id != front?.tabId && it.id != shown && it.id !in activity && !it.pending }
                .sortedBy { it.lastUsedAt }
                .take(awake.size - k)
                .map { it.id }
        }
    }
}
