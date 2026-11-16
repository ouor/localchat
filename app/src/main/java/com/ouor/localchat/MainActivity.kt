package com.ouor.localchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // TODO(ui): AppScaffold. TODO(track B): ACTION_SEND/SEND_MULTIPLE -> DraftRepository.addShared (I-02).
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {}
            }
        }
    }
}
