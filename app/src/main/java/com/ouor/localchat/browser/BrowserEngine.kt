package com.ouor.localchat.browser

import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every WebView tab (public surface, architecture 7.1, 10.2). Main thread only.
 * WebViews outlive Activities; ui.browser.BrowserSurface only attaches them.
 */
@Singleton
class BrowserEngine @Inject constructor() {

    val tabs: StateFlow<List<TabInfo>> get() = TODO("track C")

    /** B-09 element highlight, null when nothing is highlighted. */
    val highlight: StateFlow<Highlight?> get() = TODO("track C")

    /** Tab id -> name of the AI tool currently running on it (B-09 ②). */
    val aiActivity: StateFlow<Map<Int, String>> get() = TODO("track C")

    fun openUserTab(url: String): Int = TODO("track C")

    fun openAiTab(sessionId: String, url: String?): Int = TODO("track C")

    /** B-04 hand a tab to the AI or take it back. */
    fun setOwner(tabId: Int, owner: Owner): Unit = TODO("track C")

    /** AI | USER (direct control / give back). */
    fun setControl(tabId: Int, control: Control): Unit = TODO("track C")

    fun close(tabId: Int): Unit = TODO("track C")

    fun tab(tabId: Int): BrowserTab = TODO("track C")

    fun navigate(tabId: Int, url: String): Unit = TODO("track C")

    fun goBack(tabId: Int): Unit = TODO("track C")

    fun goForward(tabId: Int): Unit = TODO("track C")

    fun reload(tabId: Int): Unit = TODO("track C")

    /** B-07. */
    fun clearData(cookies: Boolean, cache: Boolean, storage: Boolean): Unit = TODO("track C")
}

data class TabInfo(
    val id: Int,
    val title: String,
    val url: String,
    val owner: Owner,
    val control: Control,
    val loading: Boolean,
)

sealed interface Owner {
    data object User : Owner
    data class Ai(val sessionId: String) : Owner
}

enum class Control { AI, USER }
