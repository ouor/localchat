package com.ouor.localchat.browser

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.drawToBitmap
import com.ouor.localchat.browser.script.CssRect
import com.ouor.localchat.browser.script.PageMark
import com.ouor.localchat.browser.script.PageScript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One tab: a WebView that can be put to sleep and woken ([webView] changes then), its load signals and the facts of what
 * happened in it (browser-layers 2-3). The tab has no owner: the user and the AI use the same calls. Every action runs
 * once and never waits; waiting is [awaitReady]. Created and used on the main thread; suspend functions switch to
 * Main themselves. The WebView's context is a [MutableContextWrapper] the engine points at the host Activity.
 */
class BrowserTab internal constructor(
    val id: Int,
    appContext: Context,
    private val events: Events,
) {
    /** Engine hooks; the defaults let instrumented tests create a bare tab. */
    interface Events {
        fun onChanged(tab: BrowserTab) {}

        /** Asks the listeners, then the default (browser-layers 2-4). [Answer.ToUser] = the engine lists it in prompts. */
        fun dispatch(tab: BrowserTab, e: BrowserEvent): Answer = BrowserEngine.defaultAnswer(e, shown = false)

        /** An event that waited for the user got its answer (or the tab went). */
        fun answered(tab: BrowserTab, handled: Handled) {}

        /** `window.open` / `target=_blank`: a new tab with no page loaded, whose WebView the page gets. */
        fun popup(opener: BrowserTab): BrowserTab? = null

        /** Brings [tab] to the front: only for the user's own gesture (a popup they opened). */
        fun select(tab: BrowserTab) {}

        /** The page called `window.close()`. */
        fun closeWindow(tab: BrowserTab) {}

        fun openExternal(uri: Uri) {}

        /** A new WebView (first one or after [wake]) goes into the host at full size. */
        fun park(tab: BrowserTab, view: WebView) {}
    }

    internal val contextWrapper = MutableContextWrapper(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _webView = MutableStateFlow<WebView?>(null)

    /** null while asleep. The drawer borrows the current one and borrows again when it changes (wake). */
    val webView: StateFlow<WebView?> = _webView.asStateFlow()

    /** The current WebView; a sleeping tab has none ([wake] first). */
    val view: WebView get() = _webView.value ?: throw PageError("Tab $id is asleep.")

    /** One tool action at a time on this tab (several chats may run at once). Not ownership: anyone takes turns. */
    val lock = Mutex()

    var url: String = ""
        private set
    var title: String = ""
        private set

    /** null = awake. */
    var asleep: Asleep? = null
        private set

    /** Epoch ms the tab was last picked, loaded or acted on. */
    var lastUsedAt: Long = System.currentTimeMillis()
        internal set

    /** See [TabInfo.urlChain]. */
    var urlChain: List<String> = emptyList()
        private set

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Uptime ms of the user's last own touch or key; injected input ([tap], [key]) is not counted (2-6). */
    @Volatile var lastUserInputAt = 0L
        private set

    /** The user's finger is on the page right now. */
    @Volatile var pointerDown = false
        private set

    // Load signals (uptime ms). Requests arrive on a WebView thread, the rest on main.
    private val lastRequestAt = AtomicLong()
    private val mainRequestAt = AtomicLong()
    @Volatile private var pageStartedAt = 0L

    /** The navigation got an answer without a new document (same-page anchor, history push, a cancelled scheme). */
    @Volatile private var committedAt = 0L
    private val navErrors = ArrayDeque<NavError>()

    /**
     * The document an app load replaced, and when: its late start/commit/finish events (a new tab's about:blank, an
     * aborted page's onPageFinished) are not the answer to the load. Cleared by the first event of another URL. Main.
     */
    private var superseded: Pair<Long, String>? = null

    /** Main-frame request URLs (uptime, url), redirects included, newest last; any thread. */
    private val mainRequests = ArrayDeque<Pair<Long, String>>()

    /** First main-frame request of the navigation on its way (uptime), 0 = none: [urlChain] starts there. */
    @Volatile private var navStartAt = 0L

    /** A document other than about:blank was loaded: a new tab whose first navigation is a download is closed (2-5). */
    private var hadDocument = false

    /**
     * First ref number for the next document (G-M1): refs never repeat within the tab, so a ref from a page that is
     * gone is always refused, never matched to another element. Raised after every agent.js call; kept across sleep.
     */
    @Volatile internal var refNext = 1

    /** The document the last list was marked "still changing" for (G-m13). Main thread. */
    var changingDoc: String? = null

    /** Events of the tab and their answers, newest last (at most [LOG]). */
    private val eventLog = ArrayDeque<Handled>()
    private val downloadLog = ArrayDeque<DownloadNote>()

    private val _dialog = MutableStateFlow<BrowserEvent.Dialog?>(null)

    /** The JS dialog the page is blocked on while the user answers it. */
    val dialog: StateFlow<BrowserEvent.Dialog?> = _dialog.asStateFlow()
    private var dialogResult: JsResult? = null

    /** Events waiting for the user: id -> the event and how to apply the answer. */
    private val waiting = LinkedHashMap<Long, Pair<BrowserEvent, (Answer) -> Unit>>()

    /** An event of the tab waits for the user. */
    val pending: Boolean get() = waiting.isNotEmpty()

    /** True while the engine injects a native event, so the user's own input is told apart (2-6). */
    private var injecting = false

    /** Back/forward list saved when the tab was put to sleep; null after a renderer death (it cannot be saved then). */
    private var savedState: Bundle? = null

    /** URL the renderer last died on: dying there again is [BrowserEvent.RendererGone.repeated]. */
    private var crashUrl: String? = null

    init {
        _webView.value = newWebView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun newWebView(): WebView = WebViewFactory.create(contextWrapper).also { w ->
        w.webViewClient = Client()
        w.webChromeClient = Chrome()
        w.setDownloadListener { dlUrl, _, disposition, mime, length -> onDownload(dlUrl, disposition, mime, length) }
        w.setOnTouchListener { _, e ->
            if (!injecting) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> pointerDown = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerDown = false
                }
                lastUserInputAt = SystemClock.uptimeMillis()
            }
            false
        }
        w.setOnKeyListener { _, _, _ ->
            if (!injecting) lastUserInputAt = SystemClock.uptimeMillis()
            false
        }
        events.park(this, w)
    }

    /** A real user touch or key within [USER_INPUT_MS], not an injected one (external links, popups). */
    private fun userInput(): Boolean = !injecting && SystemClock.uptimeMillis() - lastUserInputAt <= USER_INPUT_MS

    // ---------- navigation

    fun load(target: String) {
        val now = SystemClock.uptimeMillis()
        mainRequestAt.set(now)
        navStartAt = now
        requested(target)
        superseded = (now to url).takeIf { url != target }
        markLoading(target)
        view.loadUrl(target)
    }

    fun goBack() { if (view.canGoBack()) { superseded = null; _loading.value = true; view.goBack() } }
    fun goForward() { if (view.canGoForward()) { superseded = null; _loading.value = true; view.goForward() } }
    fun reload() { superseded = null; _loading.value = true; view.reload() }

    val canGoBack: Boolean get() = _webView.value?.canGoBack() ?: (savedState != null)
    val canGoForward: Boolean get() = _webView.value?.canGoForward() ?: false

    /** Set before loadUrl so a waiter never sees the previous page's "loaded" state. */
    private fun markLoading(target: String) {
        _loading.value = true
        url = target
        events.onChanged(this)
    }

    // ---------- sleep (2-1)

    /** Saves the back/forward list and drops the WebView; the tab stays in the list. Main thread. False when asleep. */
    internal fun discard(): Boolean {
        val w = _webView.value ?: return false
        savedState = Bundle().takeIf { w.saveState(it) != null }
        drop(w)
        asleep = Asleep.DISCARDED
        events.onChanged(this)
        return true
    }

    /**
     * A sleeping tab gets a new WebView with its back/forward list restored, or its URL loaded when none was saved. The tab object, number, [lock] and [refNext] stay. True when it was asleep.
     */
    suspend fun wake(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (asleep == null) return@withContext false
        val state = savedState
        savedState = null
        asleep = null
        _webView.value = newWebView()
        val restored = state != null && view.restoreState(state) != null
        if (!restored) load(url.ifEmpty { BrowserEngine.BLANK }) else { superseded = null; _loading.value = true }
        lastUsedAt = System.currentTimeMillis()
        events.onChanged(this@BrowserTab)
        true
    }

    /**
     * Renderer gone: every WebView sharing it died, so the tab keeps only its list entry. The answer to the event says
     * whether it loads again now (Accept) or when it is next used (2-1). Main thread; tests call it directly.
     */
    @androidx.annotation.VisibleForTesting
    internal fun rendererGone(crashed: Boolean) {
        val w = _webView.value ?: return
        val repeated = crashUrl == url
        crashUrl = url
        // Measured on devphone03 (TabModelTest.saveStateAfterRendererDeath): the list still saves after the renderer died.
        savedState = runCatching { Bundle().takeIf { w.saveState(it) != null } }.getOrNull()
        drop(w)
        asleep = Asleep.CRASHED
        events.onChanged(this)
        raise(BrowserEvent.RendererGone(nextEventId(), id, SystemClock.uptimeMillis(), crashed, repeated)) { a ->
            if (a is Answer.Accept) scope.launch { wake() }
        }
    }

    private fun drop(w: WebView) {
        cancelWaiting()
        _webView.value = null
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        w.destroy()
        _loading.value = false
    }

    /** Closed for good. Main thread. */
    internal fun destroy() {
        scope.cancel()
        _webView.value?.let(::drop)
    }

    // ---------- facts

    /** The latest main-frame failure at or after [since] (uptime), HTTP >= 400 included. Main thread. */
    fun navError(since: Long): NavError? = navErrors.lastOrNull { it.at >= since }

    /** Events at or after [since] (uptime) and how they were answered. Main thread. */
    fun eventsSince(since: Long): List<Handled> = eventLog.filter { it.event.at >= since }

    /** Downloads started at or after [since] (uptime), with their state now. Main thread. */
    fun downloadsSince(since: Long): List<DownloadNote> = downloadLog.filter { it.at >= since }

    /** Main-frame URLs requested at or after [since] (uptime), redirects included. Any thread. */
    fun mainRequestsSince(since: Long): List<String> =
        synchronized(mainRequests) { mainRequests.filter { it.first >= since }.map { it.second } }

    /** An event of the document [load] replaced, unless that URL was requested again since (a redirect back to it). */
    private fun stale(eventUrl: String): Boolean {
        val (at, old) = superseded ?: return false
        if (eventUrl == old && mainRequestsSince(at).none { it == eventUrl }) return true
        superseded = null
        return false
    }

    private fun requested(url: String) {
        val now = SystemClock.uptimeMillis()
        if (navStartAt == 0L) navStartAt = now
        synchronized(mainRequests) {
            if (mainRequests.size >= MAIN_REQUESTS) mainRequests.removeFirst()
            mainRequests.addLast(now to url)
        }
    }

    private fun record(h: Handled) {
        if (eventLog.size >= LOG) eventLog.removeFirst()
        eventLog.addLast(h)
    }

    /**
     * Waits until the page is ready to read or act on after something that started at [since] (uptime ms;
     * tools-redesign 2-1): the document is in (a request made after [since] is waited for), then the DOM and the
     * network stay quiet for [TabRules.QUIET]. A short page waits for a change up to [TabRules.SETTLE_CAP]. A dialog the
     * user must answer pauses the wait without a cap. [status] reports the phase for the step line. Main thread.
     */
    suspend fun awaitReady(since: Long, status: (ToolStatus) -> Unit = {}): Readiness = withContext(Dispatchers.Main.immediate) {
        var docStart = since
        var settleStart: Long? = null
        var settleN: Int? = null
        var shown: ToolStatus? = null
        fun show(s: ToolStatus) { if (s != shown) { shown = s; status(s) } }
        show(ToolStatus.OPENING)
        var done: Readiness? = null
        while (done == null) {
            if (_dialog.value != null) {
                show(ToolStatus.PAGE_DIALOG)
                _dialog.first { it == null }
                show(if (settleStart == null) ToolStatus.OPENING else ToolStatus.SETTLING)
                continue
            }
            var busy = false
            val mark = try {
                PageScript.call<PageMark>(this@BrowserTab, "mark", timeoutMs = TabRules.MARK_EVAL, retry = false)
            } catch (e: PageError) {
                busy = e.message?.startsWith(NOT_ANSWERED) == true
                null
            }
            // A page too busy to answer is read as it is, marked as maybe unfinished (A-m5).
            if (busy && settleStart != null) return@withContext Readiness.CHANGING
            val now = SystemClock.uptimeMillis()
            val input = TabRules.ReadyInput(
                now, since, docStart, settleStart, _loading.value, mark, settleN, mainRequestAt.get(), pageStartedAt,
                committedAt, lastRequestAt.get(), navErrors.lastOrNull { !it.http }?.at, downloadLog.lastOrNull()?.at,
            )
            when (val step = TabRules.readyStep(input)) {
                is TabRules.ReadyStep.Done -> done = step.readiness
                TabRules.ReadyStep.Settle -> { settleStart = now; settleN = mark?.n }
                TabRules.ReadyStep.Restart -> { docStart = mainRequestAt.get(); settleStart = null; settleN = null; show(ToolStatus.OPENING) }
                is TabRules.ReadyStep.Wait -> {
                    settleStart?.let { if (now - it > SETTLING_SHOWN_MS) show(ToolStatus.SETTLING) }
                    delay(step.ms)
                }
            }
        }
        done
    }

    // ---------- script

    /**
     * evaluateJavascript with a timeout. A JS dialog blocks the page's script thread, so the call also returns
     * early when one opens for the user (tools-redesign 2-4).
     */
    suspend fun evaluate(script: String, timeoutMs: Long): String = withContext(Dispatchers.Main.immediate) {
        _dialog.value?.let { throw PageError(dialogNote(it)) }
        val done = CompletableDeferred<String?>()
        view.evaluateJavascript(script) { done.complete(it) }
        val result = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val opened = async { _dialog.first { it != null } }
                select<Any> {
                    done.onAwait { it ?: "null" }
                    opened.onAwait { it!! }
                }.also { opened.cancel() }
            }
        } ?: throw PageError("$NOT_ANSWERED ${timeoutMs / 1000} s (it may be busy).")
        if (result is BrowserEvent.Dialog) throw PageError(dialogNote(result))
        result as String
    }

    // ---------- native input (trusted events)

    /**
     * Native tap at CSS px ([innerWidth] = window.innerWidth). Waits first while the user's finger is down or they
     * touched within [USER_GAP_MS] (up to [USER_WAIT_MS]), so their gesture is not cut (2-6). Does not tap a page
     * that is still scrolling (a fling: the down would only stop it) and answers [Tap.MOVING], nor one whose current
     * layout is not drawn yet ([Tap.NOT_DRAWN]). [beforeTouch] runs right before the touch goes in.
     */
    suspend fun tap(cssX: Float, cssY: Float, innerWidth: Float, beforeTouch: suspend () -> Unit = {}): Tap = withContext(Dispatchers.Main.immediate) {
        val start = SystemClock.uptimeMillis()
        while ((pointerDown || SystemClock.uptimeMillis() - lastUserInputAt < USER_GAP_MS) && SystemClock.uptimeMillis() - start < USER_WAIT_MS) {
            delay(FRAME_MS)
        }
        val w = view
        if (!w.isAttachedToWindow || w.width == 0 || innerWidth <= 0f) return@withContext Tap.FAILED
        val y0 = w.scrollY
        delay(FRAME_MS * 2)
        if (w.scrollY != y0) return@withContext Tap.MOVING
        // Touches are hit-tested on the last drawn frame: wait until the current DOM state (after the scroll into view)
        // has been drawn, or a tap lands where the element was before (QA R-B1, devphone02).
        val drawn = CompletableDeferred<Unit>()
        w.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { drawn.complete(Unit) }
        })
        if (withTimeoutOrNull(DRAW_MS) { drawn.await() } == null) return@withContext Tap.NOT_DRAWN
        beforeTouch() // after the waits, so a user's touch meanwhile is never taken for this one
        val scale = w.width / innerWidth
        val x = cssX * scale
        val y = cssY * scale
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + TAP_MS, MotionEvent.ACTION_UP, x, y, 0)
        // The AI's tap does not take the app's focus (the user may be typing in the chat). A WebView that gains focus
        // from a touch also moves its page back to the top before the click lands (QA B1, measured on devphone03).
        val blockFocus = !w.hasFocus()
        val focusable = w.isFocusable to w.isFocusableInTouchMode
        if (blockFocus) { w.isFocusable = false; w.isFocusableInTouchMode = false }
        try {
            injecting = true
            val taken = try {
                w.dispatchTouchEvent(down) or w.dispatchTouchEvent(up)
            } finally {
                injecting = false
                down.recycle()
                up.recycle()
            }
            if (blockFocus) delay(FOCUS_HOLD_MS) // the page asks for focus after it handled the tap
            if (taken) Tap.DONE else Tap.FAILED
        } finally {
            if (blockFocus) { w.isFocusable = focusable.first; w.isFocusableInTouchMode = focusable.second }
        }
    }

    enum class Tap { DONE, FAILED, MOVING, NOT_DRAWN }

    /** Native key press, e.g. KEYCODE_ENTER with META_CTRL_ON. */
    fun key(keyCode: Int, meta: Int): Boolean {
        val w = view
        val t = SystemClock.uptimeMillis()
        injecting = true
        try {
            val a = w.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            val b = w.dispatchKeyEvent(KeyEvent(t, t + TAP_MS, KeyEvent.ACTION_UP, keyCode, 0, meta))
            return a || b
        } finally {
            injecting = false
        }
    }

    /** Visible area as a bitmap, null when the view is asleep or not laid out (R2). */
    fun capture(): Bitmap? {
        val w = _webView.value ?: return null
        return if (w.isAttachedToWindow && w.width > 0 && w.height > 0) runCatching { w.drawToBitmap() }.getOrNull() else null
    }

    /**
     * SC-13 preview: the element at [rect] (CSS px, viewport of [innerWidth]) with a margin, from the visible
     * area. Null when not laid out or off screen. Main thread.
     */
    fun captureElement(rect: CssRect, innerWidth: Float): Bitmap? {
        val w = _webView.value ?: return null
        if (innerWidth <= 0f) return null
        val box = TabRules.cropBox(rect, w.width / innerWidth, w.width, w.height) ?: return null
        val full = capture() ?: return null
        return Bitmap.createBitmap(full, box[0], box[1], box[2], box[3]).also { if (it !== full) full.recycle() }
    }

    /** Downscaled ([THUMB_WIDTH]) last view of the page for the tab list; refreshed after load and when parked. */
    var thumbnail: Bitmap? = null
        private set

    /** Main thread. False when the view is not laid out (thumbnail kept). */
    fun refreshThumbnail(): Boolean {
        val full = capture() ?: return false
        val h = (full.height.toLong() * THUMB_WIDTH / full.width).toInt().coerceAtLeast(1)
        thumbnail = Bitmap.createScaledBitmap(full, THUMB_WIDTH, h, true) // old one is not recycled: UI may still draw it
        full.recycle()
        return true
    }

    // ---------- events (2-4)

    /** Asks the engine, applies the answer now, or keeps the event for the user ([resolve]). */
    private fun raise(e: BrowserEvent, apply: (Answer) -> Unit) {
        val a = events.dispatch(this, e)
        record(Handled(e, a))
        if (a == Answer.ToUser) {
            waiting[e.id] = e to apply
            events.onChanged(this)
        } else {
            apply(a)
        }
    }

    /** The user's answer to an event that waited for them. Main thread. */
    internal fun resolve(eventId: Long, answer: Answer) {
        val (e, apply) = waiting.remove(eventId) ?: return
        record(Handled(e, answer))
        apply(answer)
        events.answered(this, Handled(e, answer))
        events.onChanged(this)
    }

    private fun cancelWaiting() {
        waiting.keys.toList().forEach { resolve(it, Answer.Dismiss) }
    }

    private fun dialog(kind: DialogKind, message: String?, default: String?, result: JsResult): Boolean {
        val e = BrowserEvent.Dialog(nextEventId(), id, SystemClock.uptimeMillis(), kind, message.orEmpty(), default, url)
        if (dialogResult != null) { // one at a time, like a browser
            result.cancel()
            record(Handled(e, Answer.Dismiss))
            return true
        }
        raise(e) { a ->
            if (_dialog.value?.id == e.id) { _dialog.value = null; dialogResult = null }
            when (a) {
                is Answer.Accept -> if (result is JsPromptResult && kind == DialogKind.PROMPT) result.confirm(a.text ?: default.orEmpty()) else result.confirm()
                else -> result.cancel()
            }
        }
        if (e.id in waiting) { dialogResult = result; _dialog.value = e }
        return true
    }

    private fun onDownload(dlUrl: String, disposition: String?, mime: String?, length: Long) {
        val now = SystemClock.uptimeMillis()
        val firstNavigation = !hadDocument
        val same = downloadLog.lastOrNull { it.url == dlUrl && now - it.at < TabRules.SAME_DOWNLOAD_MS }
        if (same != null) {
            addDownload(same.copy(at = now, again = true))
        } else {
            val e = BrowserEvent.Download(nextEventId(), id, now, dlUrl, mime, length, TabRules.fileName(dlUrl, disposition, mime))
            val note = DownloadNote(now, dlUrl, mime, e.fileName)
            addDownload(note)
            raise(e) { a -> if (a is Answer.Accept) save(note) else update(note) { it.copy(state = DownloadNote.State.NOT_SAVED) } }
        }
        committedAt = now
        _loading.value = false
        // The navigation ended in a file: the tab still shows the page it had (QA B5).
        if (hadDocument) url = _webView.value?.url ?: url
        events.onChanged(this)
        if (firstNavigation) events.closeWindow(this) // Chrome: a new tab that only downloads goes away
    }

    private fun addDownload(n: DownloadNote) {
        if (downloadLog.size >= LOG) downloadLog.removeFirst()
        downloadLog.addLast(n)
    }

    private fun update(n: DownloadNote, change: (DownloadNote) -> DownloadNote) {
        val i = downloadLog.indexOfFirst { it.at == n.at && it.url == n.url }
        if (i >= 0) downloadLog[i] = change(downloadLog[i])
    }

    /**
     * Every download is fetched by the app (2-5, QA B3): http(s) with the page's cookies, user agent and referrer,
     * data: and blob: from the page. One path into Downloads; the note says started, saved (name, size) or failed (why).
     */
    private fun save(n: DownloadNote) {
        val scheme = Uri.parse(n.url).scheme?.lowercase()
        val referer = url.takeIf { it.startsWith("http") }
        val agent = _webView.value?.settings?.userAgentString
        update(n) { it.copy(state = DownloadNote.State.STARTED) }
        val web = scheme == "http" || scheme == "https"
        // A web file outlives its tab (a new tab that only downloads is closed at once); data: and blob: need the page.
        (if (web) downloads else scope).launch {
            val r = runCatching {
                if (web) {
                    withContext(Dispatchers.IO) { fetch(n, agent, referer) }
                } else {
                    val (bytes, type) = if (scheme == "data") TabRules.dataUrl(n.url) ?: error("not a readable data: URL")
                    else TabRules.dataUrl(PageScript.blob(this@BrowserTab, n.url)) ?: error("the page did not hand over the file")
                    withContext(Dispatchers.IO) { writeDownload(n.fileName, n.mime ?: type) { it.write(bytes) } } to type
                }
            }
            update(n) { cur ->
                r.fold(
                    { (saved, type) -> cur.copy(state = DownloadNote.State.SAVED, fileName = saved.first, size = saved.second, savedMime = type) },
                    { cur.copy(state = DownloadNote.State.FAILED, reason = it.message ?: it.javaClass.simpleName) },
                )
            }
            events.onChanged(this@BrowserTab)
        }
    }

    /** GET [n] like the page would; (saved name, size) to its type. IO thread. */
    private fun fetch(n: DownloadNote, agent: String?, referer: String?): Pair<Pair<String, Long>, String> {
        val req = Request.Builder().url(n.url).apply {
            agent?.let { header("User-Agent", it) }
            referer?.let { header("Referer", it) }
            CookieManager.getInstance().getCookie(n.url)?.let { header("Cookie", it) }
        }.build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val type = res.body.contentType()?.let { "${it.type}/${it.subtype}" } ?: n.mime ?: "application/octet-stream"
            return writeDownload(n.fileName, n.mime ?: type) { out -> res.body.byteStream().use { it.copyTo(out) } } to type
        }
    }

    /**
     * Writes into Downloads as a pending entry that is published only once complete, so a crash leaves no partial file
     * (Android removes expired pending entries). Returns the name Downloads gave it (it adds " (1)" for a taken name) and
     * the size. IO thread.
     */
    private fun writeDownload(name: String, mime: String, write: (java.io.OutputStream) -> Unit): Pair<String, Long> {
        val resolver = contextWrapper.applicationContext.contentResolver
        fun insert(asName: String) = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, asName)
                // A generic type makes Downloads put " (1)" after the extension of a taken name: use the name's type.
                val byName = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(asName.substringAfterLast('.', "").lowercase())
                put(MediaStore.Downloads.MIME_TYPE, if (mime == "application/octet-stream" && byName != null) byName else mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            })
        }.getOrNull()
        // MediaStore numbers only about 32 copies of one name, then refuses the insert: retry once under a unique name.
        val uri = insert(name) ?: insert(uniqueName(name, System.currentTimeMillis())) ?: error("Downloads refused the file")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("Downloads could not be written")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) return (c.getString(0) ?: name) to c.getLong(1)
            }
            return name to -1L
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, startUrl: String, favicon: Bitmap?) {
            if (stale(startUrl)) return
            pageStartedAt = SystemClock.uptimeMillis()
            requested(startUrl)
            urlChain = mainRequestsSince(navStartAt.takeIf { it > 0 } ?: pageStartedAt).plus(startUrl).distinct()
            navStartAt = 0L
            if (startUrl != crashUrl) crashUrl = null
            _loading.value = true
            url = startUrl
            // Committed, finished or not: a page that never finishes loading is still a document (QA B2).
            if (startUrl != BrowserEngine.BLANK) hadDocument = true
            events.onChanged(this@BrowserTab)
        }

        override fun onPageFinished(view: WebView, finishedUrl: String) {
            if (stale(finishedUrl)) return
            committedAt = SystemClock.uptimeMillis()
            url = view.url ?: finishedUrl
            title = view.title.orEmpty()
            if (url != BrowserEngine.BLANK) hadDocument = true
            _loading.value = false
            lastUsedAt = System.currentTimeMillis()
            events.onChanged(this@BrowserTab)
            view.postDelayed({ if (!_loading.value && refreshThumbnail()) events.onChanged(this@BrowserTab) }, THUMB_DELAY_MS)
        }

        override fun doUpdateVisitedHistory(view: WebView, newUrl: String, isReload: Boolean) {
            if (stale(newUrl)) return
            committedAt = SystemClock.uptimeMillis()
            url = newUrl
            events.onChanged(this@BrowserTab)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (request.isForMainFrame) requested(request.url.toString())
            if (scheme in WEB_SCHEMES) return false
            // The navigation ends here without a document (B-m3).
            if (request.isForMainFrame) committedAt = SystemClock.uptimeMillis()
            val uri = request.url
            raise(BrowserEvent.ExternalLink(nextEventId(), id, SystemClock.uptimeMillis(), uri.toString(), userInput())) { a ->
                if (a is Answer.Accept) events.openExternal(uri)
            }
            return true
        }

        // Every request: the readiness wait's network signal; a main-frame one is a new document on its way (before its first byte).
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val now = SystemClock.uptimeMillis()
            lastRequestAt.set(now)
            if (request.isForMainFrame) {
                mainRequestAt.set(now)
                requested(request.url.toString())
            }
            return null
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) addError(NavError(SystemClock.uptimeMillis(), request.url.toString(), false, error.errorCode, error.description.toString()))
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400) {
                addError(NavError(SystemClock.uptimeMillis(), request.url.toString(), true, response.statusCode, response.reasonPhrase.orEmpty()))
            }
        }

        private fun addError(e: NavError) {
            if (navErrors.size >= LOG) navErrors.removeFirst()
            navErrors.addLast(e)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === _webView.value) rendererGone(detail.didCrash())
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, t: String?) {
            title = t.orEmpty()
            events.onChanged(this@BrowserTab)
        }

        // Page logs are not kept (nothing reads them) and not printed to logcat.
        override fun onConsoleMessage(m: ConsoleMessage): Boolean = true

        override fun onJsAlert(view: WebView, u: String, message: String?, result: JsResult) = dialog(DialogKind.ALERT, message, null, result)
        override fun onJsConfirm(view: WebView, u: String, message: String?, result: JsResult) = dialog(DialogKind.CONFIRM, message, null, result)
        override fun onJsPrompt(view: WebView, u: String, message: String?, default: String?, result: JsPromptResult) =
            dialog(DialogKind.PROMPT, message, default, result)
        override fun onJsBeforeUnload(view: WebView, u: String, message: String?, result: JsResult) =
            dialog(DialogKind.BEFOREUNLOAD, message?.takeIf { it.isNotBlank() } ?: LEAVE_PAGE, null, result)

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val user = userInput()
            val tab = events.popup(this@BrowserTab) ?: return false
            (resultMsg.obj as WebView.WebViewTransport).webView = tab.view
            resultMsg.sendToTarget()
            raise(BrowserEvent.Popup(nextEventId(), id, SystemClock.uptimeMillis(), tab.id, user)) { a ->
                if (a !is Answer.Accept) events.closeWindow(tab) else if (user) events.select(tab)
            }
            return true
        }

        override fun onCloseWindow(window: WebView) = events.closeWindow(this@BrowserTab)

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val e = BrowserEvent.FileChooser(nextEventId(), id, SystemClock.uptimeMillis(), params.acceptTypes.filter { it.isNotBlank() },
                params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            raise(e) { a -> callback.onReceiveValue((a as? Answer.Files)?.uris?.toTypedArray()) }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val e = BrowserEvent.Permission(nextEventId(), id, SystemClock.uptimeMillis(), request.origin.toString(), request.resources.toList())
            raise(e) { a -> if (a is Answer.Accept) request.grant(request.resources) else request.deny() }
        }

        // The app holds no location permission.
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }

    companion object {
        const val THUMB_WIDTH = 240
        private const val LOG = 20
        private const val MAIN_REQUESTS = 20
        private const val LEAVE_PAGE = "Leave this page? Changes you made may not be saved."

        /** A touch this recent counts as the user's own gesture for a link or popup (2-4, 추정 1 s). */
        const val USER_INPUT_MS = 1_000L
        private const val USER_GAP_MS = 300L
        private const val USER_WAIT_MS = 2_000L
        private const val FRAME_MS = 16L

        /** The settle phase shows "waiting for the page to finish" only after this long (no flicker on quick pages). */
        private const val SETTLING_SHOWN_MS = 1_000L

        /** [awaitReady]'s load phase cap: a mobile network's slow first response. */
        const val LOAD_CAP = TabRules.LOAD_CAP

        /** One page mark() evaluate; a page busier than this is read as it is. */
        const val MARK_EVAL = TabRules.MARK_EVAL

        /** Start of [evaluate]'s timeout message. */
        const val NOT_ANSWERED = "The page did not answer within"

        /** Pages keep painting after onPageFinished (fonts, images). */
        private const val THUMB_DELAY_MS = 800L
        private const val TAP_MS = 50L
        private const val FOCUS_HOLD_MS = 300L
        private const val DRAW_MS = 1_000L
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")
        private val eventIds = AtomicLong()
        private val http by lazy { OkHttpClient() }
        private val downloads = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        internal fun nextEventId() = eventIds.incrementAndGet()

        /** invoice.pdf at [at] -> invoice-<at>.pdf: the extension stays, so the file still opens. */
        internal fun uniqueName(name: String, at: Long): String {
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: return "$name-$at"
            return "${name.substring(0, dot)}-$at${name.substring(dot)}"
        }

        fun dialogNote(d: BrowserEvent.Dialog): String =
            "A JavaScript ${d.kind.name.lowercase()} dialog is open: \"${d.message.take(300)}\". The page is waiting for the user to answer it."
    }
}

/** A download of the tab (uptime [at]) and where it stands. [again] = the same URL within 30 s: not fetched twice. */
data class DownloadNote(
    val at: Long,
    val url: String,
    val mime: String?,
    val fileName: String,
    val state: State = State.ASKED,
    val size: Long = -1,
    /** The type of the saved file (a POST download fetched again as GET may come back as a page). */
    val savedMime: String? = null,
    val reason: String? = null,
    val again: Boolean = false,
) {
    enum class State { ASKED, NOT_SAVED, STARTED, SAVED, FAILED }
}
