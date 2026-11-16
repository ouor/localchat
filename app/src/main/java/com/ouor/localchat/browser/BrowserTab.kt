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
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * One WebView with its client callbacks, console buffer and held dialogs (architecture 7.1). Created and used
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
    }

    internal val contextWrapper = MutableContextWrapper(appContext)
    val webView: WebView = WebViewFactory.create(contextWrapper)

    var owner: Owner = owner
        internal set
    var control: Control = Control.AI
        internal set
    var url: String = ""
        private set
    var title: String = ""
        private set

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val console = ArrayDeque<String>()

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
        markLoading(target)
        webView.loadUrl(target)
    }

    fun goBack() { if (webView.canGoBack()) { _loading.value = true; webView.goBack() } }
    fun goForward() { if (webView.canGoForward()) { _loading.value = true; webView.goForward() } }
    fun reload() { _loading.value = true; webView.reload() }

    /** Set before loadUrl so a waiter never sees the previous page's "loaded" state. */
    private fun markLoading(target: String) {
        _loading.value = true
        url = target
        events.onChanged(this)
    }

    /** Waits until the main frame finished loading; false on timeout. */
    suspend fun awaitLoaded(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { _loading.first { !it } } != null

    // ---------- script

    /**
     * evaluateJavascript with a timeout. A JS dialog blocks the page's script thread, so the call also returns
     * early when one opens (architecture 7.4 dialog_handle).
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
        } ?: throw PageError("The page did not answer within ${timeoutMs / 1000}s.")
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
            return webView.dispatchTouchEvent(down) and webView.dispatchTouchEvent(up)
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

    // ---------- console

    fun consoleLines(): List<String> = console.toList()

    // ---------- prompts

    fun answerDialog(accept: Boolean, text: String?) {
        val r = dialogResult ?: return
        val d = _dialog.value
        dialogResult = null
        _dialog.value = null
        if (!accept) r.cancel() else if (r is JsPromptResult && d?.kind == DialogKind.PROMPT) r.confirm(text ?: d.defaultText.orEmpty()) else r.confirm()
        d?.let { events.onPromptDone(it.id) }
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

    private fun hold(kind: DialogKind, message: String?, default: String?, result: JsResult): Boolean {
        if (dialogResult != null) { result.cancel(); return true } // one at a time
        val d = BrowserPrompt.Dialog(nextPromptId(), id, kind, message.orEmpty(), default, url)
        dialogResult = result
        _dialog.value = d
        events.onPrompt(d)
        return true
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, startUrl: String, favicon: Bitmap?) {
            console.clear() // SE 6: a previous page's logs (maybe a sensitive site during handoff) must not reach console_logs
            _loading.value = true
            url = startUrl
            events.onChanged(this@BrowserTab)
        }

        override fun onPageFinished(view: WebView, finishedUrl: String) {
            url = view.url ?: finishedUrl
            title = view.title.orEmpty()
            _loading.value = false
            events.onChanged(this@BrowserTab)
            view.postDelayed({ if (!_loading.value && refreshThumbnail()) events.onChanged(this@BrowserTab) }, THUMB_DELAY_MS)
        }

        override fun doUpdateVisitedHistory(view: WebView, newUrl: String, isReload: Boolean) {
            url = newUrl
            events.onChanged(this@BrowserTab)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme in WEB_SCHEMES) return false
            // tel:, mailto:, intent:, market: ... only a user tab may leave the app, never an AI tab.
            if (owner == Owner.User && request.hasGesture() && scheme != "intent") events.onExternalLink(this@BrowserTab, request.url)
            return true
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

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (console.size >= CONSOLE_MAX) console.removeFirst()
            console.addLast("[${m.messageLevel().name.lowercase()}] ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
            return true
        }

        override fun onJsAlert(view: WebView, u: String, message: String?, result: JsResult) = hold(DialogKind.ALERT, message, null, result)
        override fun onJsConfirm(view: WebView, u: String, message: String?, result: JsResult) = hold(DialogKind.CONFIRM, message, null, result)
        override fun onJsPrompt(view: WebView, u: String, message: String?, default: String?, result: JsPromptResult) =
            hold(DialogKind.PROMPT, message, default, result)

        // "Leave this page?" would block every navigation; leaving is what the navigation asked for.
        override fun onJsBeforeUnload(view: WebView, u: String, message: String?, result: JsResult): Boolean {
            result.confirm()
            return true
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
        const val CONSOLE_MAX = 200
        const val THUMB_WIDTH = 240
        /** Pages keep painting after onPageFinished (fonts, images). */
        private const val THUMB_DELAY_MS = 800L
        private const val TAP_MS = 50L
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")
        private val promptIds = AtomicLong()
        internal fun nextPromptId() = promptIds.incrementAndGet()

        fun dialogNote(d: BrowserPrompt.Dialog): String =
            "A JavaScript ${d.kind.name.lowercase()} dialog is open: \"${d.message.take(300)}\". Answer it with dialog_handle."
    }
}
