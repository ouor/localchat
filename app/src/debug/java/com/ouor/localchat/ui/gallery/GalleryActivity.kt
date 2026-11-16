package com.ouor.localchat.ui.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ouor.localchat.ui.components.ChipVariant
import com.ouor.localchat.ui.components.LcChip
import com.ouor.localchat.ui.components.LcSwitch
import com.ouor.localchat.ui.markdown.JsRenderEngine
import com.ouor.localchat.ui.markdown.LocalJsRenderEngine
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.LocalchatTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Debug-only design system gallery. Every component/state, light or dark, laid out like the mockup boards
 * (docs/dev/design/mockups/shots) so screenshots compare 1:1.
 * adb shell am start -n com.ouor.localchat/.ui.gallery.GalleryActivity --es section chat --ez dark true
 */
@AndroidEntryPoint
class GalleryActivity : ComponentActivity() {
    @Inject lateinit var engine: JsRenderEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startSection = GallerySection.entries.firstOrNull { it.name.equals(intent.getStringExtra("section"), true) } ?: GallerySection.Basics
        val startDark = intent.getBooleanExtra("dark", false)
        setContent {
            var section by rememberSaveable { mutableStateOf(startSection) }
            var dark by rememberSaveable { mutableStateOf(startDark) }
            LocalchatTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalJsRenderEngine provides engine) {
                    Column(Modifier.fillMaxSize().background(LcTheme.colors.surface).safeDrawingPadding()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            GallerySection.entries.forEach { s ->
                                LcChip(s.title, { section = s }, variant = ChipVariant.Preset, selected = s == section)
                            }
                            LcChip(if (dark) "Dark" else "Light", { dark = !dark }, variant = ChipVariant.Tonal)
                            LcSwitch(dark, { dark = it })
                        }
                        section.content()
                    }
                }
            }
        }
    }
}
