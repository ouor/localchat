package com.ouor.localchat.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
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
import com.ouor.localchat.core.model.ToolStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * One WebView with its client callbacks, load signals and held dialogs (architecture 7.1, tools-redesign 2). Created and used
 * on the main thread only; suspend functions switch to Main themselves. The WebView's context is a
 * [MutableContextWrapper] that the engine points at the host Activity (select popups, dialogs) or the
 * application.
 */
class BrowserTab internal constructor(
    val id: Int,
    appContext: Context,
    owner: Owner,
    private val events: Events,
) {
    /** Engine hooks; defaults are no-ops so instrumented tests can create a bare tab. */
    interface Events {
        fun onChanged(tab: BrowserTab) {}
        fun onPrompt(prompt: BrowserPrompt) {}
        fun onPromptDone(promptId: Long) {}
        fun onRendererGone(tab: BrowserTab) {}
        fun onUserTouch(tab: BrowserTab) {}
        fun onExternalLink(tab: BrowserTab, uri: Uri) {}

        /** An AI tool is running on [tab] right now (the engine's aiActivity). */
        fun aiOperating(tab: BrowserTab): Boolean = false
    }

    internal val contextWrapper = MutableContextWrapper(appContext)
    val webView: WebView = WebViewFactory.create(contextWrapper)

    var owner: Owner = owner
        internal set
    var control: Control = Control.AI
        internal set
    /** Hidden tab of one tool call (web_search, read(url), link prefetch), kept out of the UI ([TabInfo.hidden]). */
    var hidden: Boolean = false
        internal set
    var url: String = ""
        private set
    var title: String = ""
        private set

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

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

    /** The latest download the page started; the load that led to it ends there. */
    internal var lastDownload: DownloadNote? = null

    /**
     * First ref number for the next document (G-M1): refs never repeat within the tab, so a ref from a page that is
     * gone is always refused, never matched to another element. Raised after every agent.js call.
     */
    @Volatile internal var refNext = 1

    /** The document the last list was marked "still changing" for (G-m13). Main thread. */
    internal var changingDoc: String? = null

    /** Dialogs the page showed while the AI used it, newest last (at most [DIALOG_LOG]). */
    private val dialogLog = ArrayDeque<DialogNote>()

    private val _dialog = MutableStateFlow<BrowserPrompt.Dialog?>(null)

    /** The JS dialog the page is blocked on, if any. */
    val dialog: StateFlow<BrowserPrompt.Dialog?> = _dialog.asStateFlow()
    private var dialogResult: JsResult? = null

    private val fileChoosers = mutableMapOf<Long, ValueCallback<Array<Uri>>>()
    private val permissions = mutableMapOf<Long, PermissionRequest>()
    private val downloads = mutableMapOf<Long, BrowserPrompt.Download>()

    /** True while the engine injects a native event, so the touch listener can tell AI taps from user taps (B-09 ③). */
    private var injecting = false

    init {
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { dlUrl, _, disposition, mime, length ->
            val p = BrowserPrompt.Download(nextPromptId(), id, dlUrl, URLUtil.guessFileName(dlUrl, disposition, mime), mime, length)
            lastDownload = DownloadNote(SystemClock.uptimeMillis(), dlUrl, mime, p.fileName)
            downloads[p.id] = p
            events.onPrompt(p)
        }
        @SuppressLint("ClickableViewAccessibility")
        webView.setOnTouchListener { _, e ->
            if (!injecting && e.actionMasked == MotionEvent.ACTION_DOWN) events.onUserTouch(this)
            false
        }
    }

    // ---------- navigation

    fun load(target: String) {
        mainRequestAt.set(SystemClock.uptimeMillis())
        superseded = (mainRequestAt.get() to url).takeIf { url != target }
        markLoading(target)
        webView.loadUrl(target)
    }

    fun goBack() { if (webView.canGoBack()) { superseded = null; _loading.value = true; webView.goBack() } }
    fun goForward() { if (webView.canGoForward()) { superseded = null; _loading.value = true; webView.goForward() } }
    fun reload() { superseded = null; _loading.value = true; webView.reload() }

    /** Set before loadUrl so a waiter never sees the previous page's "loaded" state. */
    private fun markLoading(target: String) {
        _loading.value = true
        url = target
        events.onChanged(this)
    }

    /** The latest main-frame failure at or after [since] (uptime), HTTP >= 400 included. Main thread. */
    fun navError(since: Long): NavError? = navErrors.lastOrNull { it.at >= since }

    /** Dialogs shown at or after [since] (uptime). Main thread. */
    fun dialogsSince(since: Long): List<DialogNote> = dialogLog.filter { it.at >= since }

    /** A download started at or after [since]. */
    fun downloadSince(since: Long): DownloadNote? = lastDownload?.takeIf { it.at >= since }

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
        synchronized(mainRequests) {
            if (mainRequests.size >= MAIN_REQUESTS) mainRequests.removeFirst()
            mainRequests.addLast(now to url)
        }
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
                committedAt, lastRequestAt.get(), navErrors.lastOrNull { !it.http }?.at, lastDownload?.at,
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
     * early when one opens (tools-redesign 2-4).
     */
    suspend fun evaluate(script: String, timeoutMs: Long): String = withContext(Dispatchers.Main.immediate) {
        _dialog.value?.let { throw PageError(dialogNote(it)) }
        val done = CompletableDeferred<String?>()
        webView.evaluateJavascript(script) { done.complete(it) }
        val result = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val opened = async { _dialog.first { it != null } }
                select<Any> {
                    done.onAwait { it ?: "null" }
                    opened.onAwait { it!! }
                }.also { opened.cancel() }
            }
        } ?: throw PageError("$NOT_ANSWERED ${timeoutMs / 1000} s (it may be busy).")
        if (result is BrowserPrompt.Dialog) throw PageError(dialogNote(result))
        result as String
    }

    // ---------- native input (trusted events)

    /** Native tap at CSS px ([innerWidth] = window.innerWidth). False when the view cannot take input. */
    fun tap(cssX: Float, cssY: Float, innerWidth: Float): Boolean {
        if (!webView.isAttachedToWindow || webView.width == 0 || innerWidth <= 0f) return false
        val scale = webView.width / innerWidth
        val x = cssX * scale
        val y = cssY * scale
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + TAP_MS, MotionEvent.ACTION_UP, x, y, 0)
        injecting = true
        try {
            // Taken when the down was: a script click after it would activate the element twice.
            val a = webView.dispatchTouchEvent(down)
            val b = webView.dispatchTouchEvent(up)
            return a || b
        } finally {
            injecting = false
            down.recycle()
            up.recycle()
        }
    }

    /** Native key press, e.g. KEYCODE_ENTER with META_CTRL_ON. */
    fun key(keyCode: Int, meta: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        injecting = true
        try {
            val a = webView.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            val b = webView.dispatchKeyEvent(KeyEvent(t, t + TAP_MS, KeyEvent.ACTION_UP, keyCode, 0, meta))
            return a || b
        } finally {
            injecting = false
        }
    }

    /** Visible area as a bitmap, null when the view is not laid out (R2). */
    fun capture(): Bitmap? =
        if (webView.isAttachedToWindow && webView.width > 0 && webView.height > 0) runCatching { webView.drawToBitmap() }.getOrNull() else null

    /**
     * SC-13 preview: the element at [rect] (CSS px, viewport of [innerWidth]) with a margin, from the visible
     * area. Null when not laid out or off screen. Main thread.
     */
    fun captureElement(rect: CssRect, innerWidth: Float): Bitmap? {
        if (innerWidth <= 0f) return null
        val box = TabRules.cropBox(rect, webView.width / innerWidth, webView.width, webView.height) ?: return null
        val full = capture() ?: return null
        return Bitmap.createBitmap(full, box[0], box[1], box[2], box[3]).also { if (it !== full) full.recycle() }
    }

    /** Downscaled last view of the page for the tab switcher; refreshed after load and when parked. */
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

    // ---------- prompts

    fun answerDialog(accept: Boolean, text: String?) {
        val r = dialogResult ?: return
        val d = _dialog.value
        dialogResult = null
        _dialog.value = null
        val answer = if (d?.kind == DialogKind.PROMPT && accept) text ?: d.defaultText.orEmpty() else null
        if (!accept) r.cancel() else if (r is JsPromptResult && answer != null) r.confirm(answer) else r.confirm()
        d?.let {
            log(DialogNote(it.kind, it.message, accept, answer, SystemClock.uptimeMillis()))
            events.onPromptDone(it.id)
        }
    }

    internal fun answerFileChooser(promptId: Long, uris: List<Uri>?) {
        fileChoosers.remove(promptId)?.onReceiveValue(uris?.toTypedArray())
        events.onPromptDone(promptId)
    }

    internal fun answerPermission(promptId: Long, grant: Boolean) {
        permissions.remove(promptId)?.let { if (grant) it.grant(it.resources) else it.deny() }
        events.onPromptDone(promptId)
    }

    internal fun answerDownload(promptId: Long, allow: Boolean) {
        val d = downloads.remove(promptId)
        events.onPromptDone(promptId)
        if (d == null || !allow) return
        val req = DownloadManager.Request(Uri.parse(d.url))
            .setMimeType(d.mime)
            .addRequestHeader("User-Agent", webView.settings.userAgentString)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, d.fileName)
        CookieManager.getInstance().getCookie(d.url)?.let { req.addRequestHeader("Cookie", it) }
        runCatching { contextWrapper.getSystemService(DownloadManager::class.java).enqueue(req) }
    }

    /** Cancels everything the page still waits on; called before destroy. */
    internal fun cancelPrompts() {
        answerDialog(false, null)
        fileChoosers.values.forEach { it.onReceiveValue(null) }
        permissions.values.forEach { it.deny() }
        (fileChoosers.keys + permissions.keys + downloads.keys).forEach(events::onPromptDone)
        fileChoosers.clear()
        permissions.clear()
        downloads.clear()
    }

    private fun log(n: DialogNote) {
        if (dialogLog.size >= DIALOG_LOG) dialogLog.removeFirst()
        dialogLog.addLast(n)
    }

    private fun hold(kind: DialogKind, message: String?, default: String?, result: JsResult): Boolean {
        val now = SystemClock.uptimeMillis()
        if (dialogResult != null) { // one at a time
            result.cancel()
            log(DialogNote(kind, message.orEmpty(), accepted = false, text = null, at = now, extra = true))
            return true
        }
        // tools-redesign 2-4: an alert on a tab an AI tool is operating right now is confirmed by the app and reported
        // in the tool result; between tool calls the user sees it (B-m8).
        if (kind == DialogKind.ALERT && !hidden && events.aiOperating(this)) {
            result.confirm()
            log(DialogNote(kind, message.orEmpty(), accepted = true, text = null, at = now))
            return true
        }
        val d = BrowserPrompt.Dialog(nextPromptId(), id, kind, message.orEmpty(), default, url)
        dialogResult = result
        _dialog.value = d
        events.onPrompt(d)
        return true
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, startUrl: String, favicon: Bitmap?) {
            if (stale(startUrl)) return
            pageStartedAt = SystemClock.uptimeMillis()
            requested(startUrl)
            _loading.value = true
            url = startUrl
            events.onChanged(this@BrowserTab)
        }

        override fun onPageFinished(view: WebView, finishedUrl: String) {
            if (stale(finishedUrl)) return
            committedAt = SystemClock.uptimeMillis()
            url = view.url ?: finishedUrl
            title = view.title.orEmpty()
            _loading.value = false
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
            // tel:, mailto:, intent:, market: ... only a user tab may leave the app, never an AI tab.
            if (owner == Owner.User && request.hasGesture() && scheme != "intent") events.onExternalLink(this@BrowserTab, request.url)
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
            if (navErrors.size >= DIALOG_LOG) navErrors.removeFirst()
            navErrors.addLast(e)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            events.onRendererGone(this@BrowserTab)
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

        override fun onJsAlert(view: WebView, u: String, message: String?, result: JsResult) = hold(DialogKind.ALERT, message, null, result)
        override fun onJsConfirm(view: WebView, u: String, message: String?, result: JsResult) = hold(DialogKind.CONFIRM, message, null, result)
        override fun onJsPrompt(view: WebView, u: String, message: String?, default: String?, result: JsPromptResult) =
            hold(DialogKind.PROMPT, message, default, result)

        // "Leave this page?": an AI tool's navigation asked to leave, so it leaves; otherwise the user answers (B note).
        override fun onJsBeforeUnload(view: WebView, u: String, message: String?, result: JsResult): Boolean {
            if (hidden || events.aiOperating(this@BrowserTab)) {
                result.confirm()
                return true
            }
            return hold(DialogKind.CONFIRM, message?.takeIf { it.isNotBlank() } ?: LEAVE_PAGE, null, result)
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val p = BrowserPrompt.FileChooser(nextPromptId(), id, params.acceptTypes.filter { it.isNotBlank() },
                params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            fileChoosers[p.id] = callback
            events.onPrompt(p)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val p = BrowserPrompt.Permission(nextPromptId(), id, request.origin.toString(), request.resources.toList())
            permissions[p.id] = request
            events.onPrompt(p)
        }

        // The app holds no location permission.
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }

    companion object {
        const val THUMB_WIDTH = 240
        private const val DIALOG_LOG = 5
        private const val MAIN_REQUESTS = 20
        private const val LEAVE_PAGE = "Leave this page? Changes you made may not be saved."

        /** The settle phase shows "waiting for the page to finish" only after this long (no flicker on quick pages). */
        private const val SETTLING_SHOWN_MS = 1_000L

        /** Start of [evaluate]'s timeout message. */
        const val NOT_ANSWERED = "The page did not answer within"

        /** Pages keep painting after onPageFinished (fonts, images). */
        private const val THUMB_DELAY_MS = 800L
        private const val TAP_MS = 50L
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")
        private val promptIds = AtomicLong()
        internal fun nextPromptId() = promptIds.incrementAndGet()

        fun dialogNote(d: BrowserPrompt.Dialog): String =
            "A JavaScript ${d.kind.name.lowercase()} dialog is open: \"${d.message.take(300)}\". The page is waiting for the user to answer a dialog."
    }
}

/** A dialog the page showed: [accepted] OK/Cancel, [text] a prompt's answer, [extra] = cancelled because another was open. */
data class DialogNote(val kind: DialogKind, val message: String, val accepted: Boolean, val text: String?, val at: Long, val extra: Boolean = false)

/** A download a hidden tab refused (uptime [at]). */
data class DownloadNote(val at: Long, val url: String, val mime: String?, val fileName: String)
