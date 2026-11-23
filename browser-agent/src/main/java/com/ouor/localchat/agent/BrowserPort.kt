package com.ouor.localchat.agent

import android.os.SystemClock
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The engine as [BrowserToolRunner] uses it (browser-layers 8): the same API the drawer uses, narrowed so the runner
 * runs in JVM tests with a fake. [tab] is only reached by browser tools.
 */
interface BrowserPort {
    fun tabs(): List<TabInfo>
    fun front(): Front?

    /** The tab the drawer shows now, null when none. */
    fun shown(): Int?

    /** A new tab with nothing loaded yet; the front stays. Returns its id. */
    suspend fun open(): Int
    suspend fun close(tabId: Int)
    fun setActivity(tabId: Int, activity: ToolActivity?)

    /** Main thread. Throws TabClosed for a closed tab. */
    fun tab(tabId: Int): BrowserTab
    fun cookieHeader(url: String): String?

    /** The clock of the tabs' event, request and input logs (uptime ms). */
    fun now(): Long
}

/** [BrowserPort] over the real engine. */
class EngineBrowserPort(private val engine: BrowserEngine) : BrowserPort {
    override fun tabs(): List<TabInfo> = engine.tabs.value
    override fun front(): Front? = engine.front.value
    override fun shown(): Int? = engine.shown.value
    override suspend fun open(): Int = withContext(Dispatchers.Main.immediate) { engine.open() }
    override suspend fun close(tabId: Int) = withContext(Dispatchers.Main.immediate) { engine.close(tabId) }
    override fun setActivity(tabId: Int, activity: ToolActivity?) = engine.setActivity(tabId, activity)
    override fun tab(tabId: Int): BrowserTab = engine.tab(tabId)
    override fun cookieHeader(url: String): String? = engine.cookieHeader(url)
    override fun now(): Long = SystemClock.uptimeMillis()
}
