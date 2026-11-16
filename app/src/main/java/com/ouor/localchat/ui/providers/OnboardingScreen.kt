package com.ouor.localchat.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ouor.localchat.R
import com.ouor.localchat.core.model.PresetId
import com.ouor.localchat.provider.Presets
import com.ouor.localchat.ui.components.ButtonVariant
import com.ouor.localchat.ui.components.LcButton
import com.ouor.localchat.ui.components.PresetRow
import com.ouor.localchat.ui.components.SectionLabel
import com.ouor.localchat.ui.components.SettingsGroup
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.endpointDotColor

/** SC-01 (P-01): hero, preset list, "직접 입력", "나중에". Centered card up to 480dp on wide screens. */
@Composable
fun OnboardingScreen(onPreset: (PresetId) -> Unit, onCustom: () -> Unit, onLater: () -> Unit) {
    val c = LcTheme.colors
    Box(Modifier.fillMaxSize().background(c.surface).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.size(48.dp).background(c.surfaceRaised, LcRadius.lg), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(26.dp), tint = c.accent)
                }
                Text(stringResource(R.string.ui_onboard_title), style = LcTheme.text.display, color = c.textPrimary, textAlign = TextAlign.Center)
                Text(stringResource(R.string.ui_onboard_privacy), style = LcTheme.text.body, color = c.textSecondary, textAlign = TextAlign.Center)
            }
            SectionLabel(stringResource(R.string.ui_pick_provider))
            SettingsGroup {
                Presets.ALL.forEachIndexed { i, p -> PresetRow(p.name, p.baseUrl, endpointDotColor(p.name), { onPreset(p.id) }, divider = i > 0) }
                PresetRow(stringResource(R.string.ui_custom_endpoint), stringResource(R.string.ui_custom_endpoint_sub), c.textTertiary, onCustom, divider = true)
            }
            LcButton(stringResource(R.string.ui_later), onLater, Modifier.align(Alignment.CenterHorizontally).padding(16.dp), variant = ButtonVariant.Text)
        }
    }
}
