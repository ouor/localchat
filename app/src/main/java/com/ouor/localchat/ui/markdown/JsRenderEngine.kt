package com.ouor.localchat.ui.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One hidden WebView (created lazily on first use) that turns Mermaid source into a self-contained SVG
 * (`htmlLabels:false`, so AndroidSVG can draw it — no `<foreignObject>`). Assets only, no network.
 * Results are cached by source hash + theme. Never call pauseTimers() on it (shared with browser tabs, R2).
 */
@Singleton
class JsRenderEngine @Inject constructor(@ApplicationContext private val context: Context) {

    class RenderException(message: String) : Exception(message)

    private val cache = LruCache<String, String>(48)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Result<String>>>()
    private val ids = AtomicInteger()
    private var webView: WebView? = null
    private var ready = CompletableDeferred<Boolean>()

    /** False when the Mermaid bundle is not packaged (diagrams then show their source). */
    val available: Boolean by lazy {
        runCatching { context.assets.open(MERMAID_ASSET).close() }.isSuccess
    }

    suspend fun mermaidToSvg(source: String, dark: Boolean): Result<String> {
        val key = "${source.hashCode()}:${source.length}:$dark"
        cache.get(key)?.let { return Result.success(it) }
        if (!available) return Result.failure(RenderException("mermaid asset missing"))
        withContext(Dispatchers.Main) { ensureWebView() }
        if (withTimeoutOrNull(TIMEOUT_MS) { ready.await() } != true) return Result.failure(RenderException("render page not ready"))
        val id = ids.incrementAndGet()
        val result = CompletableDeferred<Result<String>>()
        pending[id] = result
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.lcRender($id, ${JSONObject.quote(source)}, $dark)", null)
        }
        val r = withTimeoutOrNull(TIMEOUT_MS) { result.await() } ?: Result.failure(RenderException("timeout"))
        pending.remove(id)
        r.onSuccess { cache.put(key, it) }
        return r
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null) return
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // Real layout size so Mermaid can measure text (the view is never attached).
            measure(VIEW_SPEC, VIEW_SPEC)
            layout(0, 0, VIEW_PX, VIEW_PX)
            addJavascriptInterface(Bridge(), "LcBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    loader.shouldInterceptRequest(request.url)
                        ?: WebResourceResponse("text/plain", "utf-8", 403, "blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            loadUrl("https://appassets.androidplatform.net/assets/render/index.html")
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun ready() { ready.complete(true) }

        @JavascriptInterface fun done(id: Int, svg: String?, error: String?) {
            pending[id]?.complete(if (svg != null) Result.success(svg) else Result.failure(RenderException(error ?: "error")))
        }
    }

    private companion object {
        const val MERMAID_ASSET = "render/mermaid/mermaid.min.js"
        const val TIMEOUT_MS = 10_000L
        const val VIEW_PX = 1024
        val VIEW_SPEC = android.view.View.MeasureSpec.makeMeasureSpec(VIEW_PX, android.view.View.MeasureSpec.EXACTLY)
    }
}
