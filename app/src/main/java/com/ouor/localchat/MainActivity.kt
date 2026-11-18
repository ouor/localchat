package com.ouor.localchat

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.ouor.localchat.ui.browser.WebViewHoleRoot
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.chat.DraftRepository
import com.ouor.localchat.chat.SharedContent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var drafts: DraftRepository

    @Inject lateinit var browserEngine: BrowserEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receiveShare(intent)
        setContent { WebViewHoleRoot { com.ouor.localchat.ui.AppScaffold() } }
        // Tab WebViews live under the Compose content: parked at full size, the panel's one shown under its page area
        // (Phase 0 spike (b), track-c.md 5; FB5 B4).
        val content = findViewById<FrameLayout>(android.R.id.content)
        val compose = content.getChildAt(0).apply { if (id == android.view.View.NO_ID) id = android.view.View.generateViewId() }
        val webViewHost = FrameLayout(this)
        webViewHost.accessibilityTraversalAfter = compose.id // the engine copies it to each shown WebView (FB5 M3)
        content.addView(webViewHost, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        browserEngine.attachHost(this, webViewHost)
    }

    override fun onDestroy() {
        browserEngine.detachHost(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
    }

    /** I-02: shared files and text go into the new-chat draft. */
    private fun receiveShare(intent: Intent) {
        val (uris, text) = SharedContent.from(intent) ?: return
        lifecycleScope.launch { drafts.addShared(uris, text) }
    }
}
