package com.ouor.localchat.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import com.ouor.localchat.browser.safety.SensitiveUrls
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.core.ApplicationScope
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.SearchEngine
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.storage.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every WebView tab (public surface, architecture 7.1, 10.2). Threads (F-10): methods without a result
 * and suspend functions may be called from any thread; methods that return a tab, id or WebView are
 * [MainThread] (lint WrongThread checks callers). StateFlows may be read anywhere. WebViews outlive Activities: they live in the host container
 * at the Activity root ([attachHost], Phase 0 spike (b)) and ui.browser.BrowserSurface borrows them
 * ([unpark] / [park]). Never calls pauseTimers()/onPause().
 */
@Singleton
class BrowserEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsStore,
    @ApplicationScope scope: CoroutineScope,
    private val clock: Clock,
) : BrowserTab.Events {

    private val main = Handler(Looper.getMainLooper())
    private val byId = LinkedHashMap<Int, BrowserTab>()
    private var nextId = 1

    /** sessionId -> tab the AI acts on when a tool omits tabId. */
    private val defaultAiTab = mutableMapOf<String, Int>()

    private var host: FrameLayout? = null
    private var hostActivity: Activity? = null

    private val _tabs = MutableStateFlow<List<TabInfo>>(emptyList())
    val tabs: StateFlow<List<TabInfo>> = _tabs.asStateFlow()

    private val _highlight = MutableStateFlow<Highlight?>(null)

    /** B-09 element highlight, null when nothing is highlighted. */
    val highlight: StateFlow<Highlight?> = _highlight.asStateFlow()

    private val _aiActivity = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** Tab id -> name of the AI tool currently running on it (B-09 ②). */
    val aiActivity: StateFlow<Map<Int, String>> = _aiActivity.asStateFlow()

    private val _prompts = MutableStateFlow<List<BrowserPrompt>>(emptyList())

    /** Pending page requests for the user (dialogs, file choosers, downloads, permissions). */
    val prompts: StateFlow<List<BrowserPrompt>> = _prompts.asStateFlow()

    private val _userTouches = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** B-09 ③: tab id of an AI tab the user touched (not an injected tap). UI offers "take control". */
    val userTouches: SharedFlow<Int> = _userTouches.asSharedFlow()

    private val builtInDomains =
        SensitiveUrls.parseList(context.assets.open("browser/sensitive_domains.txt").bufferedReader().use { it.readText() })

    /** FB2-2: entries in the built-in list, for the settings subtitle. */
    val builtInSensitiveCount: Int get() = builtInDomains.size

    // Before settings load: the full built-in list (fail safe).
    private val _sensitive = MutableStateFlow(SensitiveUrls(builtInDomains, emptyList()))

    /** B-S3 rule with the user's settings applied; the panel's sensitive banner follows it. */
    val sensitive: StateFlow<SensitiveUrls> = _sensitive.asStateFlow()

    /** Current [sensitive] rule; callers read it at each check. */
    val sensitiveUrls: SensitiveUrls get() = _sensitive.value

    /** S-05 engine for web_search. */
    @Volatile
    var searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO
        private set

    init {
        scope.launch {
            settings.settings.collect { s ->
                _sensitive.value = sensitiveRule(builtInDomains, s)
                searchEngine = s.searchEngine
            }
        }
    }

    // ---------- tabs

    @MainThread
    fun openUserTab(url: String): Int = open(Owner.User, url)

    /** Throws [PageError] at [MAX_TABS]. */
    @MainThread
    fun openAiTab(sessionId: String, url: String?): Int {
        trimIdleAiTabs(except = sessionId)
        return open(Owner.Ai(sessionId), url).also {
            defaultAiTab[sessionId] = it
            aiUsedAt[it] = clock.now()
        }
    }

    /** Tab id -> last time an AI tool opened or acquired it (BUG-13 idle trimming). */
    private val aiUsedAt = mutableMapOf<Int, Long>()

    /**
     * BUG-13: keeps at most [TabRules.MAX_IDLE_AI_TABS] idle AI tabs of other chats. Eligible = no tool running, AI
     * control, parked (not shown in a panel); idle = unused for [TabRules.AI_IDLE_MS]. Oldest go first.
     */
    private fun trimIdleAiTabs(except: String) {
        val eligible = byId.values.filter { t ->
            val o = t.owner
            o is Owner.Ai && o.sessionId != except && t.id !in _aiActivity.value && t.control == Control.AI && t.webView.parent === host
        }
        TabRules.idleAiTabsToClose(eligible.associate { it.id to (aiUsedAt[it.id] ?: 0L) }, clock.now()).forEach(::close)
    }

    // ---------- per chat (BUG-04)

    /** The chat's own AI tabs, for its browser panel and preview (B-04). Any thread. */
    fun sessionTabs(sessionId: String): Flow<List<TabInfo>> =
        tabs.map { list -> list.filter { it.owner == Owner.Ai(sessionId) } }.distinctUntilChanged()

    /** [aiActivity] limited to the chat's own tabs: what its "AI is operating" bar shows (B-09 ②). Any thread. */
    fun sessionActivity(sessionId: String): Flow<Map<Int, String>> =
        combine(aiActivity, sessionTabs(sessionId)) { act, mine -> act.filterKeys { id -> mine.any { it.id == id } } }
            .distinctUntilChanged()

    /** BUG-13: closes every AI tab of a chat (chat deleted). Any thread. */
    fun closeSessionTabs(sessionId: String) = onMain {
        byId.values.filter { it.owner == Owner.Ai(sessionId) }.map { it.id }.forEach(::close)
        defaultAiTab.remove(sessionId)
    }

    private fun open(owner: Owner, url: String?): Int {
        if (byId.size >= MAX_TABS) throw PageError("Too many tabs are open ($MAX_TABS). Close unused tabs with close_tab.")
        val tab = BrowserTab(nextId++, context, owner, this)
        byId[tab.id] = tab
        hostActivity?.let { tab.contextWrapper.baseContext = it }
        parkInHost(tab.webView)
        tab.load(url ?: BLANK)
        publish()
        return tab.id
    }

    /** B-04 hand a tab to the AI or take it back. */
    fun setOwner(tabId: Int, owner: Owner) {
        onMain {
            val tab = byId[tabId] ?: return@onMain
            tab.owner = owner
            tab.control = Control.AI
            defaultAiTab.values.removeAll { it == tabId }
            if (owner is Owner.Ai) defaultAiTab[owner.sessionId] = tabId
            publish()
        }
    }

    /**
     * AI | USER (direct control / give back). Tools waiting in [awaitAiControl] resume on AI. Any thread:
     * the run loop calls it from a worker (handoff), and [publish] reads WebView state, which is main-only.
     */
    fun setControl(tabId: Int, control: Control) {
        onMain {
            byId[tabId]?.control = control
            publish()
        }
    }

    fun close(tabId: Int) {
        onMain {
            val tab = byId.remove(tabId) ?: return@onMain
            aiUsedAt.remove(tabId)
            defaultAiTab.values.removeAll { it == tabId }
            tab.cancelPrompts()
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView.destroy()
            _aiActivity.value -= tabId
            if (_highlight.value?.tabId == tabId) _highlight.value = null
            publish()
        }
    }

    /** For ui.browser.BrowserSurface and tools. Throws NoSuchElementException for a closed tab. */
    @MainThread
    fun tab(tabId: Int): BrowserTab = byId[tabId] ?: throw NoSuchElementException("tab $tabId")

    fun navigate(tabId: Int, url: String) = onMain { byId[tabId]?.load(url) }
    fun goBack(tabId: Int) = onMain { byId[tabId]?.goBack() }
    fun goForward(tabId: Int) = onMain { byId[tabId]?.goForward() }
    fun reload(tabId: Int) = onMain { byId[tabId]?.reload() }

    /** B-07. */
    fun clearData(cookies: Boolean, cache: Boolean, storage: Boolean) {
        onMain {
            if (cookies) CookieManager.getInstance().run { removeAllCookies(null); flush() }
            if (storage) WebStorage.getInstance().deleteAllData()
            if (cache) {
                // clearCache is per-process; any WebView will do.
                val w = byId.values.firstOrNull()?.webView ?: WebView(context)
                w.clearCache(true)
                if (byId.isEmpty()) w.destroy()
            }
        }
    }

    /** B-S4 input for CookieMasker: the cookies the page at [url] can see. */
    fun cookieHeader(url: String): String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    // ---------- host container (Activity root, below the Compose content)

    /**
     * Called by MainActivity.onCreate with a full-size container placed under the Compose content. Every tab
     * WebView stays attached there at real size so timers, trusted taps and screenshots work while the panel
     * is closed (Phase 0 spike (b)).
     */
    @MainThread
    fun attachHost(activity: Activity, container: FrameLayout) {
        host = container
        hostActivity = activity
        byId.values.forEach { tab ->
            tab.contextWrapper.baseContext = activity
            if (tab.webView.parent == null) parkInHost(tab.webView)
        }
    }

    /** MainActivity.onDestroy: keep the WebViews, drop the Activity. */
    @MainThread
    fun detachHost(activity: Activity) {
        if (hostActivity !== activity) return
        byId.values.forEach { tab ->
            if (tab.webView.parent === host) host?.removeView(tab.webView)
            tab.contextWrapper.baseContext = context
        }
        host = null
        hostActivity = null
    }

    /** BrowserSurface takes the WebView into its panel. */
    @MainThread
    fun unpark(tabId: Int): WebView {
        val w = tab(tabId).webView
        (w.parent as? ViewGroup)?.removeView(w)
        w.visibility = View.VISIBLE
        return w
    }

    /** BrowserSurface gives the WebView back to the host container. */
    fun park(tabId: Int) {
        onMain {
            val t = byId[tabId] ?: return@onMain
            val w = t.webView
            if (t.refreshThumbnail()) publish() // last frame the user saw
            (w.parent as? ViewGroup)?.removeView(w)
            parkInHost(w)
        }
    }

    /**
     * Parked WebViews are INVISIBLE: measured on devphone01 (track-c.md) timers, evaluateJavascript, trusted
     * taps, drawToBitmap and PDF still work, while rAF drops ~8x and process CPU on an animated page 65% -> 40%.
     */
    private fun parkInHost(w: WebView) {
        w.visibility = View.INVISIBLE
        host?.addView(w, full())
    }

    // ---------- AI access (architecture 6.4: ToolContext.aiTab)

    /**
     * The one way the run loop gets the tab an AI tool acts on (F-02): [tabId] or the session's default AI tab
     * (created when missing), owned by this session, not on a sensitive site (B-03, B-04, B-S3, B-S6), under AI
     * control. While the user holds control it calls [onUserControl] (the loop shows Pause.UserControl), waits for
     * control to come back (B-09 ③), calls [onResumed] and checks again. Marks [toolName] as the tab's AI activity
     * (B-09 ②) until [clearAiActivity]. Any thread. Throws [PageError] when denied.
     */
    suspend fun acquireAiTab(
        sessionId: String,
        tabId: Int?,
        toolName: String,
        onUserControl: suspend (tabId: Int) -> Unit = {},
        onResumed: suspend () -> Unit = {},
    ): BrowserTab {
        while (true) {
            val tab = withContext(Dispatchers.Main.immediate) { aiTabFor(sessionId, tabId) }
            if (tab.control == Control.AI) {
                withContext(Dispatchers.Main.immediate) {
                    defaultAiTab[sessionId] = tab.id
                    aiUsedAt[tab.id] = clock.now()
                    _aiActivity.value += (tab.id to toolName)
                }
                return tab
            }
            onUserControl(tab.id)
            try {
                tabs.first { list -> list.none { it.id == tab.id && it.control == Control.USER } }
            } finally {
                onResumed()
            }
        }
    }

    /** The tab tools use when tabId is omitted, if it is still open and owned by the session. */
    @MainThread
    fun currentAiTab(sessionId: String): Int? = defaultAiTab[sessionId]?.takeIf { byId[it]?.owner == Owner.Ai(sessionId) }

    /** Ownership only (no sensitive-URL or control check): close_tab and request_handoff. */
    @MainThread
    fun ownedAiTab(sessionId: String, tabId: Int?): BrowserTab {
        val id = tabId ?: defaultAiTab[sessionId] ?: throw PageError("You have no open tab.")
        val tab = byId[id] ?: throw PageError("Tab $id does not exist. Use get_tabs.")
        if (tab.owner != Owner.Ai(sessionId)) throw PageError(NOT_YOURS.format(id))
        return tab
    }

    /** [acquireAiTab] without the control wait: default tab (created when missing), ownership, B-S3. */
    @MainThread
    fun aiTabFor(sessionId: String, tabId: Int?): BrowserTab {
        val id = tabId
            ?: defaultAiTab[sessionId]?.takeIf { byId[it]?.owner == Owner.Ai(sessionId) }
            ?: openAiTab(sessionId, null)
        val tab = byId[id] ?: throw PageError("Tab $id does not exist. Use get_tabs.")
        if (tab.owner != Owner.Ai(sessionId)) throw PageError(NOT_YOURS.format(id))
        if (sensitiveUrls.isSensitive(tab.url)) {
            throw PageError("Tab $id: ${SensitiveUrls.hostOf(tab.url)} ${SensitiveUrls.REFUSAL}. The AI may not read or operate it; ask the user to do this part.")
        }
        return tab
    }

    /** End of a tool call: clears the B-09 ② "AI is operating" state for the session's tabs. */
    fun clearAiActivity(sessionId: String) {
        onMain {
            val mine = byId.values.filter { it.owner == Owner.Ai(sessionId) }.map { it.id }.toSet()
            _aiActivity.value = _aiActivity.value.filterKeys { it !in mine }
        }
    }

    /** B-09 ①: outline [target] for [HIGHLIGHT_MS] with label [labelRes]. */
    fun showHighlight(tabId: Int, target: Located, labelRes: Int) {
        val r = target.rect
        val h = Highlight(tabId, r.left, r.top, r.width, r.height, target.innerWidth, labelRes, clock.now() + HIGHLIGHT_MS)
        _highlight.value = h
        main.postDelayed({ if (_highlight.value === h) _highlight.value = null }, HIGHLIGHT_MS)
    }

    // ---------- prompts

    fun answerDialog(promptId: Long, accept: Boolean, text: String? = null) {
        onMain {
            byId.values.firstOrNull { it.dialog.value?.id == promptId }?.answerDialog(accept, text)
        }
    }

    fun answerFileChooser(promptId: Long, uris: List<Uri>?) = onMain { promptTab(promptId)?.answerFileChooser(promptId, uris) }
    fun answerPermission(promptId: Long, grant: Boolean) = onMain { promptTab(promptId)?.answerPermission(promptId, grant) }
    fun answerDownload(promptId: Long, allow: Boolean) = onMain { promptTab(promptId)?.answerDownload(promptId, allow) }

    private fun promptTab(promptId: Long): BrowserTab? = _prompts.value.firstOrNull { it.id == promptId }?.let { byId[it.tabId] }

    /** F-10: methods without a result may be called from any thread; WebView work then runs on main. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ---------- BrowserTab.Events

    override fun onChanged(tab: BrowserTab) = publish()

    override fun onPrompt(prompt: BrowserPrompt) {
        _prompts.value += prompt
    }

    override fun onPromptDone(promptId: Long) {
        _prompts.value = _prompts.value.filterNot { it.id == promptId }
    }

    // A crashed or killed renderer leaves an unusable WebView; drop the tab instead of crashing the app.
    override fun onRendererGone(tab: BrowserTab) {
        main.post { close(tab.id) }
    }

    override fun onUserTouch(tab: BrowserTab) {
        if (tab.owner is Owner.Ai) _userTouches.tryEmit(tab.id)
    }

    override fun onExternalLink(tab: BrowserTab, uri: Uri) {
        val a = hostActivity ?: return
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun publish() {
        _tabs.value = byId.values.map { t ->
            TabInfo(t.id, t.title, t.url, t.owner, t.control, t.loading.value, t.webView.canGoBack(), t.webView.canGoForward(), t.thumbnail)
        }
    }

    companion object {
        /** FB2-2: the built-in list only while its switch is on; user domains always. */
        internal fun sensitiveRule(builtIn: List<String>, s: AppSettings) =
            SensitiveUrls(if (s.builtInSensitiveEnabled) builtIn else emptyList(), s.userSensitiveDomains)

        const val MAX_TABS = 10
        const val HIGHLIGHT_MS = 1_000L
        const val BLANK = "about:blank"
        private const val NOT_YOURS = "Tab %d is not your tab. Only tabs you opened or the user handed to you can be used; open your own with open_tab."
        private fun full() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

data class TabInfo(
    val id: Int,
    val title: String,
    val url: String,
    val owner: Owner,
    val control: Control,
    val loading: Boolean,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** [BrowserTab.THUMB_WIDTH] px wide, null until the page was first drawn. Never recycle it. */
    val thumbnail: Bitmap? = null,
) {
    /** Plain http page: the address bar shows a warning; AI typing here needs approval (team decision 1). */
    val insecure: Boolean get() = url.startsWith("http://", ignoreCase = true)
}

sealed interface Owner {
    data object User : Owner
    data class Ai(val sessionId: String) : Owner
}

enum class Control { AI, USER }
