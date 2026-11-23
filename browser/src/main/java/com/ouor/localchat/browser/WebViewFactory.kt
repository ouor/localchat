package com.ouor.localchat.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * B-S7 WebView settings (architecture 7.2). No addJavascriptInterface / addWebMessageListener anywhere:
 * the page has no channel into the app.
 */
object WebViewFactory {
    fun create(context: Context): WebView = WebView(context).apply {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            // target=_blank and window.open open a new tab (BrowserTab.onCreateWindow), window.opener included (S17);
            // without a gesture they are blocked, like Chrome's popup blocker.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        // W-m6: back/forward keeps the page (scroll, loaded items) where WebView supports it; else an HTTP-cache reload.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE)) WebSettingsCompat.setBackForwardCacheEnabled(settings, true)
        // B-07: one cookie jar for every tab; third-party cookies for sign-in flows.
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }
}
