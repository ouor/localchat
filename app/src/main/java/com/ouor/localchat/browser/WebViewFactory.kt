package com.ouor.localchat.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * B-S7 WebView settings (architecture 7.2). No addJavascriptInterface / addWebMessageListener anywhere:
 * the page has no channel into the app.
 */
internal object WebViewFactory {
    fun create(context: Context): WebView = WebView(context).apply {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            // target=_blank and window.open load in the same tab; the engine owns every tab.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        // B-07: one cookie jar for every tab; third-party cookies for sign-in flows.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }
}
