package com.ouor.localchat.ui.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.PresetId
import com.ouor.localchat.provider.EndpointDraft
import com.ouor.localchat.provider.Presets
import com.ouor.localchat.provider.ProviderRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug only: registers the DeepSeek preset with a key passed as an extra, so screens can be checked with real
 * data before the provider screens exist. Never logs the key.
 * adb shell am start -n com.ouor.localchat/.ui.gallery.DebugSeedActivity --es key "$DEEPSEEK_API_KEY"
 */
@AndroidEntryPoint
class DebugSeedActivity : ComponentActivity() {
    @Inject lateinit var providers: ProviderRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("key")?.trim().orEmpty()
        lifecycleScope.launch {
            if (key.isNotEmpty() && providers.endpoints.first().none { it.preset == PresetId.DEEPSEEK }) {
                val p = Presets.of(PresetId.DEEPSEEK)
                val id = providers.add(EndpointDraft(p.name, p.baseUrl, ApiKey(key), p.id, allowHttp = false))
                providers.refreshModels(id)
            }
            finish()
        }
    }
}
