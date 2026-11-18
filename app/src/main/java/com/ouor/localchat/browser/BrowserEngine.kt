package com.ouor.localchat.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.ouor.localchat.core.model.AppLanguage
import java.util.Locale
import com.ouor.localchat.storage.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * ([webView], [show] / [park]). Never calls pauseTimers()/onPause().
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

    /** sessionId -> the chat's work tab: the one tab its AI tools act on (tools-redesign 4). */
    private val workTabs = mutableMapOf<String, Int>()

    private var host: FrameLayout? = null
    private var hostActivity: Activity? = null

    private val _tabs = MutableStateFlow<List<TabInfo>>(emptyList())

    /** Every tab, hidden tool tabs included (the run loop's own checks). */
    val tabs: StateFlow<List<TabInfo>> = _tabs.asStateFlow()

    private val _visibleTabs = MutableStateFlow<List<TabInfo>>(emptyList())

    /** [tabs] without hidden tool tabs: what the panel and tab list show. */
    val visibleTabs: StateFlow<List<TabInfo>> = _visibleTabs.asStateFlow()

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

    /** S-05 engine for web_search (tried first). */
    @Volatile
    var searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO
        private set

    /** App language code for result pages (Google hl, Bing setlang). */
    @Volatile
    var searchLanguage: String = Locale.getDefault().language
        private set

    /** The user's region (Bing cc), null when unset. */
    @Volatile
    var region: String? = null
        private set

    init {
        scope.launch {
            settings.settings.collect { s ->
                _sensitive.value = sensitiveRule(builtInDomains, s)
                searchEngine = s.searchEngine
                searchLanguage = when (s.language) {
                    AppLanguage.KO -> "ko"
                    AppLanguage.EN -> "en"
                    AppLanguage.SYSTEM -> Locale.getDefault().language
                }
                region = s.region
            }
        }
    }

    // ---------- tabs

    @MainThread
    fun openUserTab(url: String): Int = open(Owner.User, url)

    /**
     * Throws [BrowserRefusal] at [MAX_TABS] visible tabs. A [hidden] tab (one tool call's) never shows in the panel or
     * tab list, never counts toward [MAX_TABS] (its own cap is [TabRules.MAX_HIDDEN_TABS]) and never becomes the
     * chat's work tab; its opener must close it.
     */
    @MainThread
    fun openAiTab(sessionId: String, url: String?, hidden: Boolean = false): Int {
        trimIdleAiTabs(except = sessionId)
        return open(Owner.Ai(sessionId), url, hidden).also {
            if (!hidden) workTabs[sessionId] = it
            aiUsedAt[it] = clock.now()
        }
    }

    /**
     * A hidden tab for one tool call: at [TabRules.MAX_HIDDEN_TABS] it waits for a slot (other chats' reads, link
     * prefetch) up to [TabRules.HIDDEN_WAIT] instead of failing (B-m9); the wait stops at once on cancel. [opened]
     * gets the id in the same main-thread step that makes the tab. Any thread.
     */
    suspend fun openHiddenTab(sessionId: String, url: String, opened: (Int) -> Unit) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            // Past the wait it opens anyway, which then fails with the tab limit message.
            val done = withContext(Dispatchers.Main.immediate) {
                (byId.values.count { it.hidden } < TabRules.MAX_HIDDEN_TABS || SystemClock.uptimeMillis() - start >= TabRules.HIDDEN_WAIT)
                    .also { if (it) opened(openAiTab(sessionId, url, hidden = true)) }
            }
            if (done) return
            delay(TabRules.HIDDEN_POLL)
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
        visibleTabs.map { list -> list.filter { it.owner == Owner.Ai(sessionId) } }.distinctUntilChanged()

    /** [aiActivity] limited to the chat's own tabs: what its "AI is operating" bar shows (B-09 ②). Any thread. */
    fun sessionActivity(sessionId: String): Flow<Map<Int, String>> =
        combine(aiActivity, sessionTabs(sessionId)) { act, mine -> act.filterKeys { id -> mine.any { it.id == id } } }
            .distinctUntilChanged()

    /** BUG-13: closes every AI tab of a chat (chat deleted). Any thread. */
    fun closeSessionTabs(sessionId: String) = onMain {
        byId.values.filter { it.owner == Owner.Ai(sessionId) }.map { it.id }.forEach(::close)
        workTabs.remove(sessionId)
    }

    private fun open(owner: Owner, url: String?, hidden: Boolean = false): Int {
        val hiddenCount = byId.values.count { it.hidden }
        TabRules.limitError(hidden, byId.size - hiddenCount, hiddenCount)?.let { throw BrowserRefusal(it) }
        val tab = BrowserTab(nextId++, context, owner, this)
        tab.hidden = hidden
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
            workTabs.values.removeAll { it == tabId }
            if (owner is Owner.Ai) workTabs[owner.sessionId] = tabId
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
            workTabs.values.removeAll { it == tabId }
            tab.cancelPrompts()
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView.destroy()
            _aiActivity.value -= tabId
            if (_highlight.value?.tabId == tabId) _highlight.value = null
            publish()
        }
    }

    /** For ui.browser.BrowserSurface and tools. Throws [TabClosed] for a closed tab. */
    @MainThread
    fun tab(tabId: Int): BrowserTab = byId[tabId] ?: throw TabClosed(tabId)

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
     * WebView stays attached there at real size so timers, trusted taps and page pictures work while the panel
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

    /** The tab's WebView, which stays in the host: BrowserSurface places it under its page area (FB5 B4: never moved). */
    @MainThread
    fun webView(tabId: Int): WebView = tab(tabId).webView

    /** tabId -> BrowserSurfaces showing it; two can overlap (chat cross-fade), the tab is parked when the last one goes. */
    private val shownBy = HashMap<Int, Int>()

    /** A BrowserSurface shows the tab (pair with [park]). Called before the frame is drawn, so it is visible in it (FB5 M1). */
    @MainThread
    fun show(tabId: Int) {
        val t = byId[tabId] ?: return
        shownBy[tabId] = (shownBy[tabId] ?: 0) + 1
        t.webView.visibility = View.VISIBLE
        // TalkBack reads the page after the app's UI, not before it (the host comes first in the view tree; FB5 M3).
        host?.let { t.webView.accessibilityTraversalAfter = it.accessibilityTraversalAfter }
    }

    /** A BrowserSurface is done with it: back to parked (hidden, full size) when no other one shows it. */
    fun park(tabId: Int) {
        onMain {
            val left = (shownBy.remove(tabId) ?: 0) - 1
            if (left > 0) { shownBy[tabId] = left; return@onMain }
            val t = byId[tabId] ?: return@onMain
            if (t.refreshThumbnail()) publish() // last frame the user saw
            parkInHost(t.webView)
        }
    }

    /**
     * Parked WebViews are INVISIBLE: measured on devphone01 (track-c.md) timers, evaluateJavascript, trusted
     * taps, drawToBitmap and PDF still work, while rAF drops ~8x and process CPU on an animated page 65% -> 40%.
     */
    private fun parkInHost(w: WebView) {
        w.visibility = View.INVISIBLE
        w.translationX = 0f
        w.translationY = 0f
        val h = host ?: return
        if (w.parent === h) w.layoutParams = full() else h.addView(w, full())
        // Screen off / dozing: no layout pass runs, so a new WebView would stay 0x0 (page innerWidth 0, no page pictures).
        // Give it the host's size now (when the host was never laid out: the Activity window's, split screen
        // included, else the display's); the next real pass agrees.
        val win = hostActivity?.windowManager?.currentWindowMetrics?.bounds
        val dm = context.resources.displayMetrics
        val (width, height) = TabRules.fallbackSize(h.width, h.height, win?.width(), win?.height(), dm.widthPixels, dm.heightPixels)
        w.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, width, height)
    }

    // ---------- AI access (architecture 6.4, tools-redesign 4: ToolContext.workTab)

    /**
     * The one way the run loop gets the tab an AI tool acts on (F-02): the session's work tab, owned by this session,
     * not on a sensitive site (B-03, B-04, B-S3, B-S6), under AI control. Only a [navigating] tool (browse) creates
     * the work tab when there is none, and only it may use one on a sensitive site, since it leaves the site. While
     * the user holds control it calls [onUserControl] (the loop shows Pause.UserControl), waits for control to come
     * back (B-09 ③), calls [onResumed] and checks again. Marks [toolName] as the tab's AI activity (B-09 ②) until
     * [clearAiActivity]. Any thread. Throws [BrowserRefusal] when denied.
     */
    suspend fun acquireAiTab(
        sessionId: String,
        toolName: String,
        navigating: Boolean,
        onUserControl: suspend (tabId: Int) -> Unit = {},
        onResumed: suspend () -> Unit = {},
    ): BrowserTab {
        while (true) {
            val tab = withContext(Dispatchers.Main.immediate) { aiTabFor(sessionId, navigating) }
            if (tab.control == Control.AI) {
                withContext(Dispatchers.Main.immediate) {
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

    /** The chat's work tab, if it is still open and owned by the session. */
    @MainThread
    fun workTab(sessionId: String): Int? = workTabs[sessionId]?.takeIf { byId[it]?.owner == Owner.Ai(sessionId) }

    /** The work tab by ownership only (no sensitive-URL or control check): request_handoff. */
    @MainThread
    fun ownedAiTab(sessionId: String): BrowserTab = workTab(sessionId)?.let(byId::get) ?: throw BrowserRefusal(NO_TAB)

    /** [acquireAiTab] without the control wait: the work tab (created when [navigating] and missing), B-S3. */
    @MainThread
    fun aiTabFor(sessionId: String, navigating: Boolean): BrowserTab {
        val id = workTab(sessionId) ?: if (navigating) openAiTab(sessionId, null) else throw BrowserRefusal(NO_TAB)
        val tab = byId.getValue(id)
        if (!navigating && sensitiveUrls.isSensitive(tab.url)) {
            throw BrowserRefusal("Your tab is on ${SensitiveUrls.hostOf(tab.url)}, which ${SensitiveUrls.REFUSAL}. The AI may not read or operate it; ask the user to do this part.")
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

    /** Panel ⋮ "Save as PDF": the tab's page as an A4 PDF into [uri] (SAF). False when printing or writing failed. */
    suspend fun savePdf(tabId: Int, uri: Uri): Boolean {
        val tab = withContext(Dispatchers.Main.immediate) { byId[tabId] } ?: return false
        return try {
            val bytes = PdfPrinter.print(tab)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } } != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
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

    override fun aiOperating(tab: BrowserTab): Boolean = tab.id in _aiActivity.value

    override fun onPrompt(prompt: BrowserPrompt) {
        // Nobody sees a hidden tab: refuse at once, or the page would wait forever (FB3 ui m6). read reports a download.
        val tab = byId[prompt.tabId]
        if (tab?.hidden == true) {
            when (prompt) {
                is BrowserPrompt.Dialog -> tab.answerDialog(false, null)
                is BrowserPrompt.FileChooser -> tab.answerFileChooser(prompt.id, null)
                is BrowserPrompt.Permission -> tab.answerPermission(prompt.id, false)
                is BrowserPrompt.Download -> tab.answerDownload(prompt.id, false)
            }
            return
        }
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
        if (TabRules.asksToTakeOver(tab.owner, tab.control)) _userTouches.tryEmit(tab.id)
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
            TabInfo(t.id, t.title, t.url, t.owner, t.control, t.loading.value, t.webView.canGoBack(), t.webView.canGoForward(), t.thumbnail, t.hidden)
        }
        _visibleTabs.value = _tabs.value.filterNot { it.hidden }
    }

    companion object {
        /** FB2-2: the built-in list only while its switch is on; user domains always. */
        internal fun sensitiveRule(builtIn: List<String>, s: AppSettings) =
            SensitiveUrls(if (s.builtInSensitiveEnabled) builtIn else emptyList(), s.userSensitiveDomains)

        const val MAX_TABS = 10
        const val HIGHLIGHT_MS = 1_000L
        const val BLANK = "about:blank"
        const val NO_TAB = "You have no browser tab open; call browse(url) first."
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
    /** One tool call's hidden tab: kept out of the panel and tab list ([BrowserEngine.openAiTab]). */
    val hidden: Boolean = false,
) {
    /** Plain http page: the address bar shows a warning; AI typing here needs approval (team decision 1). */
    val insecure: Boolean get() = url.startsWith("http://", ignoreCase = true)
}

sealed interface Owner {
    data object User : Owner
    data class Ai(val sessionId: String) : Owner
}

enum class Control { AI, USER }
