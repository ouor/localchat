package com.ouor.localchat.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.core.ApplicationScope
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.AppLanguage
import com.ouor.localchat.core.model.SearchEngine
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A plain browser (browser-layers 2-2): every tab, which one the user has in front, which one the drawer shows, and
 * what happens in them. The user and the AI use the same calls; no tab has an owner. Threads (F-10): methods without a
 * result and suspend functions may be called from any thread; methods that return a tab, id or WebView are
 * [MainThread]. StateFlows may be read anywhere. WebViews outlive Activities: they live in the host container at the
 * Activity root ([attachHost]) and ui.browser.BrowserSurface borrows them ([webView], [show] / [park]). Never calls
 * pauseTimers()/onPause().
 */
@Singleton
class BrowserEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
    private val clock: Clock,
) : BrowserFacts, BrowserTab.Events {

    private val main = Handler(Looper.getMainLooper())
    private val byId = LinkedHashMap<Int, BrowserTab>()
    private val ids = IdCounter(context)

    private var host: FrameLayout? = null
    private var hostActivity: Activity? = null

    private val _tabs = MutableStateFlow<List<TabInfo>>(emptyList())
    override val tabs: StateFlow<List<TabInfo>> = _tabs.asStateFlow()

    private val _front = MutableStateFlow<Front?>(null)
    override val front: StateFlow<Front?> = _front.asStateFlow()

    private val _shown = MutableStateFlow<Int?>(null)
    override val shown: StateFlow<Int?> = _shown.asStateFlow()

    private val _activity = MutableStateFlow<Map<Int, ToolActivity>>(emptyMap())

    /** Coordination only: layer 1 never branches on it. The executor sets it inside the tab's lock. */
    override val activity: StateFlow<Map<Int, ToolActivity>> = _activity.asStateFlow()

    private val _events = MutableSharedFlow<Handled>(extraBufferCapacity = 64)
    override val events: SharedFlow<Handled> = _events.asSharedFlow()

    private val _prompts = MutableStateFlow<List<BrowserEvent>>(emptyList())

    /** Events waiting for the user's answer ([answer]). */
    val prompts: StateFlow<List<BrowserEvent>> = _prompts.asStateFlow()

    private val _highlight = MutableStateFlow<Highlight?>(null)

    /** B-09 element highlight, null when nothing is highlighted. */
    val highlight: StateFlow<Highlight?> = _highlight.asStateFlow()

    private val tabListeners = HashMap<Int, CopyOnWriteArrayList<EventListener>>()
    private val allListeners = CopyOnWriteArrayList<EventListener>()

    /** S-05 engine for web_search and the address bar. */
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
                searchEngine = s.searchEngine
                searchLanguage = when (s.language) {
                    AppLanguage.KO -> "ko"
                    AppLanguage.EN -> "en"
                    AppLanguage.SYSTEM -> Locale.getDefault().language
                }
                region = s.region
            }
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val dmId = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                onMain { byId.values.forEach { it.downloadFinished(dmId) } }
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    // ---------- tabs

    /** A new tab at [url] (null: nothing loaded yet, the caller loads it); [front] stays (the user picks tabs). Popups open in [popup]. */
    @MainThread
    fun open(url: String? = null): Int = newTab().also { t -> url?.let(t::load) }.id

    private fun newTab(): BrowserTab {
        val tab = BrowserTab(ids.next(), context, this)
        byId[tab.id] = tab
        tab.lastUsedAt = clock.now()
        publish()
        return tab
    }

    /** Closing the front tab moves the front to its right neighbour, else its left one, else none (A-m5). */
    fun close(tabId: Int) {
        onMain {
            if (tabId !in byId) return@onMain
            if (_front.value?.tabId == tabId) {
                val order = byId.keys.toList()
                val i = order.indexOf(tabId)
                _front.value = (order.getOrNull(i + 1) ?: order.getOrNull(i - 1))?.let { Front(it, clock.now()) }
            }
            val tab = byId.remove(tabId)!!
            tab.destroy()
            tabListeners.remove(tabId)
            _activity.value -= tabId
            _prompts.value = _prompts.value.filterNot { it.tabId == tabId }
            if (_highlight.value?.tabId == tabId) _highlight.value = null
            if (_shown.value == tabId) _shown.value = null
            shownBy.remove(tabId)
            publish()
        }
    }

    /** Main thread. False when the tab is gone, asleep already, or a tool holds its lock (S5). */
    @MainThread
    override fun discard(tabId: Int): Boolean {
        val tab = byId[tabId] ?: return false
        if (tab.lock.isLocked) return false
        return tab.discard()
    }

    /** The user picked [tabId]: it comes to the front and wakes when asleep. Only from the user's input (6-1). */
    fun select(tabId: Int) {
        onMain {
            val tab = byId[tabId] ?: return@onMain
            _front.value = Front(tabId, clock.now())
            tab.lastUsedAt = clock.now()
            if (tab.asleep != null) scope.launch(Dispatchers.Main.immediate) { tab.wake() }
            publish()
        }
    }

    /** Throws [TabClosed] for a closed tab. Does not wake it ([BrowserTab.wake]). */
    @MainThread
    fun tab(tabId: Int): BrowserTab = byId[tabId] ?: throw TabClosed(tabId)

    /** "4e7" -> 4 (whether or not tab 4 is still open). */
    fun tabOfRef(ref: String): Int? = TabRules.tabOfRef(ref)

    fun setActivity(tabId: Int, a: ToolActivity?) {
        _activity.value = if (a == null) _activity.value - tabId else _activity.value + (tabId to a)
        if (a != null) onMain { byId[tabId]?.lastUsedAt = clock.now(); publish() }
    }

    /** The tab's own listener, asked first (newest first). */
    fun listen(tabId: Int, listener: EventListener): AutoCloseable {
        val list = synchronized(tabListeners) { tabListeners.getOrPut(tabId) { CopyOnWriteArrayList() } }
        list += listener
        return AutoCloseable { list -= listener }
    }

    /** Asked for every tab's events, after the tab's own listeners (layer-3 policies sit here). */
    fun listenAll(listener: EventListener): AutoCloseable {
        allListeners += listener
        return AutoCloseable { allListeners -= listener }
    }

    /** The user's answer to an event in [prompts]. */
    fun answer(eventId: Long, answer: Answer) {
        onMain {
            val e = _prompts.value.firstOrNull { it.id == eventId } ?: return@onMain
            byId[e.tabId]?.resolve(eventId, answer)
        }
    }

    /** App.onTrimMemory: a [BrowserEvent.MemoryPressure] for whoever listens (a policy may discard tabs). */
    fun reportMemory(level: Int) {
        val e = BrowserEvent.MemoryPressure(BrowserTab.nextEventId(), 0, SystemClock.uptimeMillis(), level)
        _events.tryEmit(Handled(e, Answer.Dismiss))
    }

    fun navigate(tabId: Int, url: String) = onMain { awake(tabId)?.load(url) }
    fun goBack(tabId: Int) = onMain { awake(tabId)?.goBack() }
    fun goForward(tabId: Int) = onMain { awake(tabId)?.goForward() }

    /** A sleeping tab (the "reload this page" button after a crash) wakes, which loads it. */
    fun reload(tabId: Int) = onMain {
        val tab = byId[tabId] ?: return@onMain
        if (tab.asleep != null) scope.launch(Dispatchers.Main.immediate) { tab.wake() } else tab.reload()
    }

    private fun awake(tabId: Int): BrowserTab? = byId[tabId]?.takeIf { it.asleep == null }

    /** B-07. */
    fun clearData(cookies: Boolean, cache: Boolean, storage: Boolean) {
        onMain {
            if (cookies) CookieManager.getInstance().run { removeAllCookies(null); flush() }
            if (storage) WebStorage.getInstance().deleteAllData()
            if (cache) {
                // clearCache is per-process; any WebView will do.
                val open = byId.values.firstNotNullOfOrNull { it.webView.value }
                val w = open ?: WebView(context)
                w.clearCache(true)
                if (open == null) w.destroy()
            }
        }
    }

    /** The cookies the page at [url] can see (download headers, a policy's cookie masking). */
    override fun cookieHeader(url: String): String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    // ---------- events (2-4)

    override fun dispatch(tab: BrowserTab, e: BrowserEvent): Answer {
        var a = defaultAnswer(e, shown = _shown.value == tab.id)
        // A failing listener keeps the answer it was given: the page must get one.
        val ask = { l: EventListener -> runCatching { l.onEvent(e, a) }.getOrNull()?.let { a = it } }
        tabListeners[tab.id]?.reversed()?.forEach(ask)
        allListeners.forEach(ask)
        if (a == Answer.ToUser) _prompts.value += e
        _events.tryEmit(Handled(e, a))
        return a
    }

    override fun answered(tab: BrowserTab, handled: Handled) {
        _prompts.value = _prompts.value.filterNot { it.id == handled.event.id }
        _events.tryEmit(handled)
    }

    override fun onChanged(tab: BrowserTab) = publish()

    override fun popup(opener: BrowserTab): BrowserTab = newTab()

    override fun select(tab: BrowserTab) = select(tab.id)

    override fun closeWindow(tab: BrowserTab) = close(tab.id)

    override fun openExternal(uri: Uri) {
        val a = hostActivity ?: return
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    override fun park(tab: BrowserTab, view: WebView) {
        hostActivity?.let { tab.contextWrapper.baseContext = it }
        parkInHost(view)
        if ((shownBy[tab.id] ?: 0) > 0) view.visibility = View.VISIBLE
    }

    // ---------- host container (Activity root, below the Compose content)

    /**
     * Called by MainActivity.onCreate with a full-size container placed under the Compose content. Every tab
     * WebView stays attached there at real size so timers, trusted taps and page pictures work while the drawer
     * is closed (Phase 0 spike (b)).
     */
    @MainThread
    fun attachHost(activity: Activity, container: FrameLayout) {
        host = container
        hostActivity = activity
        byId.values.forEach { tab ->
            tab.contextWrapper.baseContext = activity
            tab.webView.value?.let { if (it.parent == null) parkInHost(it) }
        }
    }

    /** MainActivity.onDestroy: keep the WebViews, drop the Activity. */
    @MainThread
    fun detachHost(activity: Activity) {
        if (hostActivity !== activity) return
        byId.values.forEach { tab ->
            tab.webView.value?.let { if (it.parent === host) host?.removeView(it) }
            tab.contextWrapper.baseContext = context
        }
        host = null
        hostActivity = null
    }

    /** The tab's WebView, which stays in the host; null while asleep, a new one after it wakes (borrow again). */
    @MainThread
    fun webView(tabId: Int): StateFlow<WebView?> = tab(tabId).webView

    /** tabId -> BrowserSurfaces showing it; two can overlap (chat cross-fade), the tab is parked when the last one goes. */
    private val shownBy = HashMap<Int, Int>()

    /** A BrowserSurface shows the tab (pair with [park]); it becomes [shown]. Called before the frame is drawn (FB5 M1). */
    @MainThread
    fun show(tabId: Int) {
        val t = byId[tabId] ?: return
        shownBy[tabId] = (shownBy[tabId] ?: 0) + 1
        _shown.value = tabId
        val w = t.webView.value ?: return
        w.visibility = View.VISIBLE
        // TalkBack reads the page after the app's UI, not before it (the host comes first in the view tree; FB5 M3).
        host?.let { w.accessibilityTraversalAfter = it.accessibilityTraversalAfter }
    }

    /** A BrowserSurface is done with it: back to parked (hidden, full size) when no other one shows it. */
    fun park(tabId: Int) {
        onMain {
            val left = (shownBy.remove(tabId) ?: 0) - 1
            if (left > 0) { shownBy[tabId] = left; return@onMain }
            if (_shown.value == tabId) _shown.value = null
            val t = byId[tabId] ?: return@onMain
            if (t.refreshThumbnail()) publish() // last frame the user saw
            t.webView.value?.let(::parkInHost)
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
        val h = host
        if (h != null) {
            if (w.parent === h) w.layoutParams = full() else h.addView(w, full())
        }
        // Screen off / dozing / no host: no layout pass runs, so a new WebView would stay 0x0 (page innerWidth 0, no
        // page pictures). Give it the host's size now (when the host was never laid out: the Activity window's, split
        // screen included, else the display's); the next real pass agrees.
        val win = hostActivity?.windowManager?.currentWindowMetrics?.bounds
        val dm = context.resources.displayMetrics
        val (width, height) = TabRules.fallbackSize(h?.width ?: 0, h?.height ?: 0, win?.width(), win?.height(), dm.widthPixels, dm.heightPixels)
        w.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, width, height)
    }

    /** Drawer ⋮ "Save as PDF": the tab's page as an A4 PDF into [uri] (SAF). False when printing or writing failed. */
    suspend fun savePdf(tabId: Int, uri: Uri): Boolean {
        val tab = withContext(Dispatchers.Main.immediate) { byId[tabId]?.takeIf { it.asleep == null } } ?: return false
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

    /** F-10: methods without a result may be called from any thread; WebView work then runs on main. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun publish() {
        _tabs.value = byId.values.map { t ->
            TabInfo(t.id, t.title, t.url, t.urlChain, t.loading.value, t.canGoBack, t.canGoForward, t.thumbnail, t.asleep, t.lastUsedAt, t.pending)
        }
    }

    companion object {
        const val HIGHLIGHT_MS = 1_000L
        const val BLANK = "about:blank"

        /** What a browser does when no listener answers (browser-layers 2-4); [shown] = the drawer shows the tab. */
        fun defaultAnswer(e: BrowserEvent, shown: Boolean): Answer = when (e) {
            is BrowserEvent.Dialog -> if (shown) Answer.ToUser else Answer.Dismiss
            is BrowserEvent.ExternalLink -> if (e.userInput) Answer.Accept() else Answer.Dismiss
            is BrowserEvent.Popup -> Answer.Accept()
            is BrowserEvent.Download -> Answer.Accept()
            is BrowserEvent.Permission, is BrowserEvent.FileChooser -> Answer.ToUser
            is BrowserEvent.RendererGone -> if (shown && !e.repeated) Answer.Accept() else Answer.Dismiss
            is BrowserEvent.MemoryPressure -> Answer.Dismiss
        }

        private fun full() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
