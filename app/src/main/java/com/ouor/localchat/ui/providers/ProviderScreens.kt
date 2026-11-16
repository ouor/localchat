package com.ouor.localchat.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ouor.localchat.R
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.provider.Presets
import com.ouor.localchat.ui.components.ButtonSize
import com.ouor.localchat.ui.components.ButtonVariant
import com.ouor.localchat.ui.components.ChipVariant
import com.ouor.localchat.ui.components.ConnectionStatus
import com.ouor.localchat.ui.components.ConnectionUi
import com.ouor.localchat.ui.components.DialogKind
import com.ouor.localchat.ui.components.EndpointDot
import com.ouor.localchat.ui.components.HelperTone
import com.ouor.localchat.ui.components.IconButtonSize
import com.ouor.localchat.ui.components.LcBadge
import com.ouor.localchat.ui.components.BadgeVariant
import com.ouor.localchat.ui.components.LcButton
import com.ouor.localchat.ui.components.LcCheckbox
import com.ouor.localchat.ui.components.LcChip
import com.ouor.localchat.ui.components.LcDialog
import com.ouor.localchat.ui.components.LcIconButton
import com.ouor.localchat.ui.components.LcSwitch
import com.ouor.localchat.ui.components.LcTextField
import com.ouor.localchat.ui.components.LcTopBar
import com.ouor.localchat.ui.components.PresetRow
import com.ouor.localchat.ui.components.SectionLabel
import com.ouor.localchat.ui.components.SettingsChevron
import com.ouor.localchat.ui.components.SettingsGroup
import com.ouor.localchat.ui.components.SettingsRow
import com.ouor.localchat.ui.components.SettingsValue
import com.ouor.localchat.ui.components.SourceBadge
import com.ouor.localchat.ui.components.Stepper
import com.ouor.localchat.ui.components.TextFieldKind
import com.ouor.localchat.ui.components.errorPresentation
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.endpointDotColor

@Composable
private fun Back(onBack: () -> Unit) = LcIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), onBack)

/** Provider list; with no providers it doubles as SC-01 (preset list). */
@Composable
fun ProvidersRoute(vm: ProvidersViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val endpoints by vm.endpoints.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        LcTopBar(stringResource(R.string.ui_providers), navigationIcon = { Back(onBack) }, actions = {
            LcIconButton(Icons.Rounded.Add, stringResource(R.string.ui_add_provider), onAdd)
        })
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val list = endpoints ?: return@Column
            if (list.isNotEmpty()) SettingsGroup {
                list.forEachIndexed { i, e ->
                    SettingsRow(
                        e.name, subtitle = e.baseUrl.removePrefix("https://").removePrefix("http://"), divider = i > 0, onClick = { onOpen(e.id) },
                        leading = { EndpointDot(endpointDotColor(e.name)) },
                    ) {
                        if (!e.enabled) LcBadge(stringResource(R.string.ui_disabled), variant = BadgeVariant.Neutral)
                        if (!e.hasKey && e.preset?.let { Presets.of(it).baseUrl.startsWith("https") } != false) LcBadge(stringResource(R.string.ui_no_key), variant = BadgeVariant.Warn)
                        SettingsChevron()
                    }
                }
            }
        }
    }
}

/** SC-02. New: preset chips + name/URL/key + check + save. Existing: key, models, connection, delete. */
@Composable
fun EndpointRoute(vm: EndpointEditViewModel, onBack: () -> Unit, onSaved: (String) -> Unit, onModel: (String, String) -> Unit) {
    SecureWindow()
    val s by vm.state.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.effects.collect { e -> when (e) { is EndpointEffect.Saved -> onSaved(e.endpointId); EndpointEffect.Deleted -> onBack() } }
    }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LcTopBar(if (s.isNew) stringResource(R.string.ui_add_provider) else s.name, navigationIcon = { Back(onBack) })
        // SettingsGroup insets itself 16dp; everything else gets the 16dp gutter from this column (detail) or `pad`.
        val pad = Modifier.padding(horizontal = 16.dp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.isNew) Column(pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Presets.ALL.forEach { p -> LcChip(p.name, { vm.pickPreset(p) }, variant = ChipVariant.Preset, selected = s.preset == p.id) }
                }
                LcTextField(s.name, vm::onName, label = stringResource(R.string.ui_name))
                UrlField(s, vm)
                LcTextField(
                    s.key, vm::onKey, label = stringResource(R.string.ui_api_key_optional), kind = TextFieldKind.Secret,
                    helper = stringResource(R.string.ui_key_helper),
                )
                Check(s, vm, onSaveAnyway = vm::save)
            } else {
                KeySection(s, vm)
                Row(pad.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_models), style = LcTheme.text.label, color = LcTheme.colors.textTertiary, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    LcIconButton(Icons.Rounded.Refresh, stringResource(R.string.ui_refresh_models), vm::refresh, size = IconButtonSize.Sm, enabled = !s.refreshing)
                    LcIconButton(Icons.Rounded.Add, stringResource(R.string.ui_add_model), { adding = true }, size = IconButtonSize.Sm)
                }
                SettingsGroup {
                    models.forEachIndexed { i, m -> ModelLine(m, i > 0, { onModel(m.endpointId, m.modelId) }, { vm.setHidden(m, !m.hidden) }) }
                }
                Text(stringResource(R.string.ui_connection), style = LcTheme.text.label, color = LcTheme.colors.textTertiary, modifier = pad.padding(start = 4.dp, top = 4.dp))
                SettingsGroup {
                    SettingsRow(stringResource(R.string.ui_max_retries), subtitle = stringResource(R.string.ui_max_retries_sub)) {
                        Stepper(s.maxRetries.toString(), if (s.maxRetries > 0) ({ vm.retries(-1) }) else null, if (s.maxRetries < 10) ({ vm.retries(1) }) else null)
                    }
                    SettingsRow(stringResource(R.string.ui_enabled), divider = true) { LcSwitch(s.enabled, vm::setEnabled) }
                }
                LcButton(stringResource(R.string.ui_delete_provider), { deleting = true }, Modifier.align(Alignment.CenterHorizontally), variant = ButtonVariant.Text)
            }
            Spacer(Modifier.height(16.dp))
        }
        if (s.isNew) {
            LcButton(stringResource(R.string.ui_save), vm::save, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), size = ButtonSize.Lg, enabled = s.canSave)
        }
    }
    if (deleting) LcDialog(
        stringResource(R.string.ui_delete_provider_title), { deleting = false }, stringResource(R.string.ui_delete), { deleting = false; vm.delete() },
        text = stringResource(R.string.ui_delete_provider_body), kind = DialogKind.Destructive,
    )
    if (s.confirmKeyHost) LcDialog(
        stringResource(R.string.ui_key_host_title), { vm.confirmKeyHost(false) }, stringResource(R.string.ui_key_host_send), { vm.confirmKeyHost(true) },
        dismissLabel = stringResource(R.string.ui_key_host_reenter),
    )
    if (adding) {
        var id by remember { mutableStateOf("") }
        LcDialog(stringResource(R.string.ui_add_model), { adding = false }, stringResource(R.string.ui_save), { vm.addModel(id.trim()); adding = false }, confirmEnabled = id.isNotBlank()) {
            LcTextField(id, { id = it }, placeholder = "model-id")
        }
    }
}

/** SEC-2 / SE 27: no screenshots or recents preview while an API key can be on screen. */
@Composable
private fun SecureWindow() {
    val window = (androidx.compose.ui.platform.LocalContext.current as? android.app.Activity)?.window ?: return
    androidx.compose.runtime.DisposableEffect(window) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun UrlField(s: EndpointUiState, vm: EndpointEditViewModel) {
    val (helper, tone) = when {
        s.baseUrl.isNotBlank() && !s.urlValid -> stringResource(R.string.ui_url_invalid) to HelperTone.Error
        s.isHttp -> stringResource(R.string.ui_http_warning) to HelperTone.Warn
        else -> null to HelperTone.Normal
    }
    LcTextField(s.baseUrl, vm::onUrl, label = "Base URL", kind = TextFieldKind.Url, helper = helper, helperTone = tone)
    if (s.isHttp) Row(verticalAlignment = Alignment.CenterVertically) {
        LcCheckbox(s.allowHttp, vm::onAllowHttp)
        Text(stringResource(R.string.ui_allow_http), style = LcTheme.text.body, color = LcTheme.colors.textPrimary)
    }
}

@Composable
private fun Check(s: EndpointUiState, vm: EndpointEditViewModel, onSaveAnyway: () -> Unit) {
    when (val ch = s.check) {
        null -> LcButton(stringResource(R.string.ui_check_connection), vm::check, variant = ButtonVariant.Outline, size = ButtonSize.Sm, enabled = s.urlValid)
        CheckUi.Checking -> ConnectionStatus(ConnectionUi.Checking, vm::check, onSaveAnyway)
        is CheckUi.Ok -> ConnectionStatus(ConnectionUi.Ok(ch.models), vm::check, onSaveAnyway)
        is CheckUi.Failed -> ConnectionStatus(
            ConnectionUi.Error((ch.error.httpStatus?.let { "$it · " } ?: "") + stringResource(errorPresentation(ch.error.kind).title)),
            vm::check, onSaveAnyway,
        )
    }
}

@Composable
private fun KeySection(s: EndpointUiState, vm: EndpointEditViewModel) {
    val pad = Modifier.padding(horizontal = 16.dp)
    if (s.editingKey || s.keyHint == null) Column(pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LcTextField(s.key, vm::onKey, label = stringResource(R.string.ui_api_key), kind = TextFieldKind.Secret, helper = stringResource(R.string.ui_key_helper))
        LcButton(stringResource(R.string.ui_save), vm::saveKey, size = ButtonSize.Sm, enabled = s.key.isNotBlank())
    } else {
        SettingsGroup {
            SettingsRow("•••• ${s.keyHint}") {
                LcButton(stringResource(R.string.ui_replace_key), vm::editKey, variant = ButtonVariant.Text, size = ButtonSize.Sm)
            }
        }
        Column(pad) { Check(s, vm, onSaveAnyway = {}) }
    }
}

@Composable
private fun ModelLine(m: ModelInfo, divider: Boolean, onOpen: () -> Unit, onToggleHidden: () -> Unit) {
    val caps = buildList {
        if (m.caps.toolCalling.value) add(stringResource(R.string.ui_cap_tools))
        if (m.caps.imageInput.value) add(stringResource(R.string.ui_cap_image))
        if (m.caps.reasoning.value != null) add(stringResource(R.string.ui_cap_reasoning))
        m.caps.contextWindow.value?.let { add(com.ouor.localchat.ui.chat.compact(it)) }
    }.joinToString(" · ")
    SettingsRow(
        m.displayName, subtitle = if (m.hidden) stringResource(R.string.ui_hidden) else caps, divider = divider, enabled = !m.hidden,
        onClick = if (m.hidden) null else onOpen,
    ) {
        if (!m.inServerList) LcBadge(stringResource(R.string.ui_not_listed), variant = BadgeVariant.Warn)
        LcIconButton(if (m.hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (m.hidden) R.string.ui_show else R.string.ui_hide), onToggleHidden, size = IconButtonSize.Sm)
    }
}

/** SC-02a. */
@Composable
fun ModelDetailRoute(vm: ModelDetailViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val m = s.model
    Column(Modifier.fillMaxSize().imePadding()) {
        LcTopBar(m?.modelId.orEmpty(), subtitle = s.endpointName, navigationIcon = { Back(onBack) })
        if (m == null) return@Column
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var name by remember(m.modelId) { mutableStateOf(s.overrides.displayName ?: m.displayName) }
            LcTextField(name, { name = it; vm.edit { copy(displayName = it.trim().ifEmpty { null }) } }, Modifier.padding(horizontal = 16.dp), label = stringResource(R.string.ui_display_name))
            SectionLabel(stringResource(R.string.ui_capabilities))
            SettingsGroup {
                CapRow(Icons.Rounded.Build, stringResource(R.string.ui_cap_tools), stringResource(R.string.ui_cap_tools_sub), m.caps.toolCalling.source, m.caps.toolCalling.value, false) {
                    vm.edit { copy(toolCalling = it) }
                }
                CapRow(Icons.Outlined.Image, stringResource(R.string.ui_cap_image), null, m.caps.imageInput.source, m.caps.imageInput.value, true) { vm.edit { copy(imageInput = it) } }
                val r = m.caps.reasoning.value
                val def = stringResource(R.string.ui_default)
                CapRow(
                    Icons.Outlined.Lightbulb, stringResource(R.string.ui_cap_reasoning),
                    r?.efforts?.takeIf { it.isNotEmpty() }?.joinToString(" · ") { e -> if (e == r.defaultEffort) "$e($def)" else e },
                    m.caps.reasoning.source, r != null, true,
                ) { vm.edit { copy(reasoning = it) } }
            }
            SectionLabel(stringResource(R.string.ui_limits))
            SettingsGroup {
                SettingsRow(stringResource(R.string.ui_context_window)) {
                    SourceBadge(m.caps.contextWindow.source); SettingsValue(m.caps.contextWindow.value?.let { "%,d".format(it) } ?: "—")
                }
                SettingsRow(stringResource(R.string.ui_max_output), divider = true) {
                    SourceBadge(m.caps.maxOutputTokens.source); SettingsValue(m.caps.maxOutputTokens.value?.let { "%,d".format(it) } ?: "—")
                }
            }
            LcButton(stringResource(R.string.ui_reset_defaults), { vm.reset() }, Modifier.fillMaxWidth().padding(16.dp), variant = ButtonVariant.Outline, icon = Icons.Rounded.RestartAlt)
            Text(stringResource(R.string.ui_cap_priority), Modifier.fillMaxWidth(), style = LcTheme.text.caption, color = LcTheme.colors.textTertiary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CapRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, source: CapSource, value: Boolean, divider: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(title, subtitle = sub, leadingIcon = icon, divider = divider) {
        SourceBadge(source)
        LcSwitch(value, onChange)
    }
}

