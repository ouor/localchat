package com.ouor.localchat.browser.script

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * execute's syntax check that runs nothing (B-m2): the code is compiled by `new Function` in an app-owned blank
 * WebView, where no page's Content-Security-Policy applies, and the function is never called. The code goes in as a
 * string literal, so it cannot break out of the check.
 */
internal object JsSyntax {
    private var view: WebView? = null

    /** True when [body] parses as a function body; null when the checker did not answer. */
    suspend fun parses(context: Context, body: String): Boolean? = withContext(Dispatchers.Main.immediate) {
        val v = view ?: WebView(context.applicationContext).also {
            it.settings.javaScriptEnabled = true
            it.loadUrl("about:blank")
            view = it
        }
        val done = CompletableDeferred<String?>()
        v.evaluateJavascript("(function(){try{new Function(${JsonPrimitive(body)});return 1}catch(e){return 0}})()") { done.complete(it) }
        when (withTimeoutOrNull(5_000) { done.await() }) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }
}
