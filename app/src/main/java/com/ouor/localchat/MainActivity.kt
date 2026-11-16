package com.ouor.localchat

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
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
        // TODO(ui): AppScaffold; after a share, navigate to the new chat (I-02).
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {}
            }
        }
        // Tab WebViews live at full size under the Compose content (Phase 0 spike (b), track-c.md 5).
        val webViewHost = FrameLayout(this)
        findViewById<FrameLayout>(android.R.id.content).addView(webViewHost, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
