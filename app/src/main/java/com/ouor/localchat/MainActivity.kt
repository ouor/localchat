package com.ouor.localchat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.ouor.localchat.chat.DraftRepository
import com.ouor.localchat.chat.SharedContent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var drafts: DraftRepository

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
