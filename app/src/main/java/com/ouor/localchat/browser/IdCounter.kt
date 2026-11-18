package com.ouor.localchat.browser

import android.content.Context

/**
 * Tab numbers that are never used twice, across restarts too (browser-layers 2-1), so a ref ("4e7") or "tab 4" from
 * before a restart names nothing instead of a new tab. SharedPreferences rather than DataStore: [BrowserEngine.open]
 * returns the number synchronously on the main thread.
 */
class IdCounter(context: Context) {
    private val prefs = context.getSharedPreferences("browser_tabs", Context.MODE_PRIVATE)

    fun next(): Int = synchronized(IdCounter::class) {
        (prefs.getInt(KEY, 0) + 1).also { prefs.edit().putInt(KEY, it).apply() }
    }

    private companion object { const val KEY = "lastTabId" }
}
