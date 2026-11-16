package com.ouor.localchat.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.ui.components.EndpointGroupHeader
import com.ouor.localchat.ui.theme.LcRadius
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
import com.ouor.localchat.ui.components.EndAnchoredMenu
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
        if (s.isNew) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
            Spacer(Modifier.height(16.dp))
        } else {
            // FB-3: one "모델 N" group, collapsed whenever the screen opens. Lazy so 300+ models expand smoothly.
            var modelsOpen by remember { mutableStateOf(false) }
            val gap = Modifier.padding(top = 12.dp)
            LazyColumn(Modifier.weight(1f)) {
                item(key = "key") { Column(gap, verticalArrangement = Arrangement.spacedBy(12.dp)) { KeySection(s, vm, models.size) } }
                providerModelGroup(
                    models, modelsOpen, { modelsOpen = !modelsOpen }, s.refreshing, vm::refresh, { adding = true },
                    { m -> onModel(m.endpointId, m.modelId) }, { m -> vm.setHidden(m, !m.hidden) },
                )
                item(key = "conn") {
                    Column(gap, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.ui_connection), style = LcTheme.text.label, color = LcTheme.colors.textTertiary, modifier = pad.padding(start = 4.dp, top = 4.dp))
                        SettingsGroup {
                            SettingsRow(stringResource(R.string.ui_max_retries), subtitle = stringResource(R.string.ui_max_retries_sub)) {
                                Stepper(s.maxRetries.toString(), if (s.maxRetries > 0) ({ vm.retries(-1) }) else null, if (s.maxRetries < 10) ({ vm.retries(1) }) else null)
                            }
                            SettingsRow(stringResource(R.string.ui_enabled), divider = true) { LcSwitch(s.enabled, vm::setEnabled) }
                        }
                        LcButton(stringResource(R.string.ui_delete_provider), { deleting = true }, Modifier.align(Alignment.CenterHorizontally), variant = ButtonVariant.Text)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
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

/** Provider detail "모델 N" header (+ refresh/add) and, when [open], one lazy item per model. */
internal fun LazyListScope.providerModelGroup(
    models: List<ModelInfo>, open: Boolean, onToggle: () -> Unit, refreshing: Boolean, onRefresh: () -> Unit, onAdd: () -> Unit,
    onOpen: (ModelInfo) -> Unit, onToggleHidden: (ModelInfo) -> Unit,
) {
    item(key = "models") {
        Row(Modifier.padding(end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            EndpointGroupHeader(stringResource(R.string.ui_models), null, models.size, Modifier.weight(1f), expanded = open, onToggle = onToggle)
            LcIconButton(Icons.Rounded.Refresh, stringResource(R.string.ui_refresh_models), onRefresh, size = IconButtonSize.Sm, enabled = !refreshing)
            LcIconButton(Icons.Rounded.Add, stringResource(R.string.ui_add_model), onAdd, size = IconButtonSize.Sm)
        }
    }
    if (!open) return
    if (models.isEmpty()) item(key = "empty") {
        Text(stringResource(R.string.fb_e3_group_empty), Modifier.groupSlice(first = true, last = true).padding(16.dp), style = LcTheme.text.caption, color = LcTheme.colors.textTertiary)
    }
    itemsIndexed(models, key = { _, m -> m.modelId }) { i, m ->
        ModelLine(m, i > 0, { onOpen(m) }, { onToggleHidden(m) }, Modifier.groupSlice(i == 0, i == models.lastIndex))
    }
}

/**
 * One lazy item drawn as a slice of a [SettingsGroup] (16dp inset, 1dp border, 16dp corners on the ends),
 * so a long group can live in a LazyColumn.
 */
@Composable
private fun Modifier.groupSlice(first: Boolean, last: Boolean): Modifier {
    val c = LcTheme.colors
    val r = 16.dp
    val outer = RoundedCornerShape(topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp, bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp)
    val ri = r - 1.dp
    val inner = RoundedCornerShape(topStart = if (first) ri else 0.dp, topEnd = if (first) ri else 0.dp, bottomStart = if (last) ri else 0.dp, bottomEnd = if (last) ri else 0.dp)
    return padding(horizontal = 16.dp).fillMaxWidth().clip(outer).background(c.border)
        .padding(start = 1.dp, end = 1.dp, top = if (first) 1.dp else 0.dp, bottom = if (last) 1.dp else 0.dp)
        .clip(inner).background(c.surfaceRaised)
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
internal fun UrlField(s: EndpointUiState, vm: EndpointFieldActions) {
    val (helper, tone) = when {
        s.baseUrl.isNotBlank() && !s.urlValid -> stringResource(R.string.ui_url_invalid) to HelperTone.Error
        s.isHttp -> stringResource(R.string.ui_http_warning) to HelperTone.Warn
        else -> stringResource(R.string.fb3_c_url_hint) to HelperTone.Normal
    }
    // P-STABLE (M7): the helper line and the http row are always there (the row disabled unless http), so the
    // fields and buttons below do not jump while the URL is typed.
    LcTextField(s.baseUrl, vm::onUrl, label = "Base URL", kind = TextFieldKind.Url, helper = helper, helperTone = tone)
    Row(verticalAlignment = Alignment.CenterVertically) {
        LcCheckbox(s.allowHttp && s.isHttp, vm::onAllowHttp, enabled = s.isHttp)
        Text(stringResource(R.string.ui_allow_http), style = LcTheme.text.body, color = if (s.isHttp) LcTheme.colors.textPrimary else LcTheme.colors.textDisabled)
    }
}

@Composable
private fun Check(s: EndpointUiState, vm: EndpointFieldActions, onSaveAnyway: (() -> Unit)?, storedModels: Int? = null) {
    val ui = when (val ch = s.check) {
        null -> null
        CheckUi.Checking -> ConnectionUi.Checking
        // Saved endpoint: the stored count, the same number the "모델 N" header shows.
        is CheckUi.Ok -> ConnectionUi.Ok(storedModels ?: ch.models)
        is CheckUi.Failed -> ConnectionUi.Error((ch.error.httpStatus?.let { "$it · " } ?: "") + stringResource(errorPresentation(ch.error.kind).title))
    }
    ConnectionStatus(ui, vm::check, onSaveAnyway, enabled = s.urlValid)
}

@Composable
internal fun KeySection(s: EndpointUiState, vm: EndpointFieldActions, storedModels: Int) {
    val pad = Modifier.padding(horizontal = 16.dp)
    if (s.keyHint == null) Column(pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LcTextField(s.key, vm::onKey, label = stringResource(R.string.ui_api_key), kind = TextFieldKind.Secret, helper = stringResource(R.string.ui_key_helper))
        LcButton(stringResource(R.string.ui_save), vm::saveKey, size = ButtonSize.Sm, enabled = s.key.isNotBlank())
    } else {
        SettingsGroup {
            SettingsRow("•••• ${s.keyHint}") {
                LcButton(stringResource(R.string.ui_replace_key), vm::editKey, variant = ButtonVariant.Text, size = ButtonSize.Sm)
            }
        }
        Column(pad) { Check(s, vm, onSaveAnyway = null, storedModels) }
        // P-STABLE (L12): the new key is typed in a dialog, so the check area below does not move. The dialog
        // window inherits FLAG_SECURE from this screen (SecureFlagPolicy.Inherit).
        if (s.editingKey) LcDialog(
            stringResource(R.string.ui_replace_key), vm::cancelEditKey, stringResource(R.string.ui_save), vm::saveKey, confirmEnabled = s.key.isNotBlank(),
        ) {
            LcTextField(s.key, vm::onKey, label = stringResource(R.string.ui_api_key), kind = TextFieldKind.Secret, helper = stringResource(R.string.ui_key_helper))
        }
    }
}

@Composable
private fun ModelLine(m: ModelInfo, divider: Boolean, onOpen: () -> Unit, onToggleHidden: () -> Unit, modifier: Modifier = Modifier) {
    val caps = buildList {
        if (m.caps.toolCalling.value) add(stringResource(R.string.ui_cap_tools))
        if (m.caps.imageInput.value) add(stringResource(R.string.ui_cap_image))
        if (m.caps.reasoning.value != null) add(stringResource(R.string.ui_cap_reasoning))
        m.caps.contextWindow.value?.let { add(limitLabel(it, CONTEXT_PRESETS)) }
    }.joinToString(" · ")
    SettingsRow(
        m.displayName, modifier, subtitle = if (m.hidden) stringResource(R.string.ui_hidden) else caps, divider = divider, enabled = !m.hidden,
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
                val ctx = m.caps.contextWindow
                LimitRow(stringResource(R.string.ui_context_window), ctx, s.autoContext, CONTEXT_PRESETS, CONTEXT_RANGE, null, false) {
                    vm.edit { copy(contextWindow = it) }
                }
                LimitRow(
                    stringResource(R.string.ui_max_output), m.caps.maxOutputTokens, s.autoOutput, OUTPUT_PRESETS, OUTPUT_RANGE,
                    ctx.value?.let { limitLabel(it, CONTEXT_PRESETS) to it }, true,
                ) { vm.edit { copy(maxOutputTokens = it) } }
            }
            LcButton(stringResource(R.string.ui_reset_defaults), { vm.reset() }, Modifier.fillMaxWidth().padding(16.dp), variant = ButtonVariant.Outline, icon = Icons.Rounded.RestartAlt)
            Text(stringResource(R.string.ui_cap_priority), Modifier.fillMaxWidth(), style = LcTheme.text.caption, color = LcTheme.colors.textTertiary, textAlign = TextAlign.Center)
        }
    }
}

/**
 * FB-6: limit row → menu (자동 / presets / 직접 입력…) → number dialog. [onSet] null = drop the override (자동).
 * [context] = (label, value) of the context window, only for the max-output row's warning.
 */
@Composable
internal fun LimitRow(
    title: String, cap: Sourced<Int?>, auto: Sourced<Int?>, presets: List<Int>, range: IntRange,
    context: Pair<String, Int>?, divider: Boolean, onSet: (Int?) -> Unit,
) {
    val c = LcTheme.colors
    var menu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val v = cap.value.takeIf { cap.source == CapSource.USER }
    val autoValue = auto.value
    val autoText = when {
        autoValue == null -> stringResource(R.string.fb_e3_limit_auto_unknown)
        auto.source == CapSource.SERVER -> stringResource(R.string.fb_e3_limit_auto_server, limitLabel(autoValue, presets))
        else -> stringResource(R.string.fb_e3_limit_auto_builtin, limitLabel(autoValue, presets))
    }
    val warn = limitWarning(v, context, auto, presets)?.let { warningText(it) }
    // P-STABLE (L6): the warning is an icon in the row (its text in the menu and for TalkBack), not a caption
    // line under the row that pushed the next row down.
    Box {
        // Value goes in the FB-2 value slot so the label keeps priority at 200% font.
        SettingsRow(title, value = cap.value?.let { limitLabel(it, presets) } ?: "—", divider = divider, onClick = { menu = true }) {
            if (warn != null) Icon(Icons.Outlined.WarningAmber, warn, Modifier.size(18.dp), tint = c.warning)
            SourceBadge(cap.source)
            Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(20.dp), tint = c.iconSecondary)
        }
        // FB2-1: the menu opens under (or above) the value/▾ with its end edge at the row's.
        EndAnchoredMenu {
            DropdownMenu(
                menu, { menu = false }, Modifier.widthIn(min = 220.dp).heightIn(max = 360.dp),
                shape = LcRadius.sm, containerColor = c.surfaceRaised,
            ) {
                if (warn != null) {
                    Text(warn, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = LcTheme.text.caption, color = c.warning)
                    HorizontalDivider(color = c.border)
                }
                LimitItem(autoText, null, null, v == null) { menu = false; onSet(null) }
                HorizontalDivider(color = c.border)
                presets.forEach { p ->
                    val label = limitLabel(p, presets)
                    val exact = "%,d".format(p)
                    LimitItem(label, exact, stringResource(R.string.fb_e3_limit_item_cd, label, exact), v == p) { menu = false; onSet(p) }
                }
                HorizontalDivider(color = c.border)
                val outside = v != null && v !in presets
                LimitItem(
                    if (outside) stringResource(R.string.fb_e3_limit_custom_n, limitLabel(v!!, presets)) else stringResource(R.string.fb_e3_limit_custom),
                    null, null, outside,
                ) { menu = false; custom = true }
            }
        }
    }
    if (custom) {
        var text by remember { mutableStateOf(v?.toString().orEmpty()) }
        val parsed = parseLimit(text, range)
        val min = "%,d".format(range.first)
        val max = "%,d".format(range.last)
        val typedWarn = (parsed as? LimitInput.Ok)?.let { limitWarning(it.value, context, auto, presets) }
        val (helper, tone) = when {
            parsed == LimitInput.NotNumber -> stringResource(R.string.fb_e3_limit_err_nan) to HelperTone.Error
            parsed == LimitInput.OutOfRange -> stringResource(R.string.fb_e3_limit_err_range, min, max) to HelperTone.Error
            typedWarn != null -> warningText(typedWarn) to HelperTone.Warn
            else -> stringResource(R.string.fb_e3_limit_range, min, max) to HelperTone.Normal
        }
        LcDialog(
            title, { custom = false }, stringResource(R.string.ui_save), { (parsed as? LimitInput.Ok)?.let { onSet(it.value) }; custom = false },
            confirmEnabled = parsed is LimitInput.Ok,
        ) {
            // L6: two helper lines reserved, so a longer message does not shift the centred dialog's buttons.
            LcTextField(text, { text = it }, helper = helper, helperTone = tone, kind = TextFieldKind.Number, suffix = stringResource(R.string.fb_e3_unit_tokens), helperMinLines = 2)
        }
    }
}

@Composable
private fun warningText(w: LimitWarning) = when (w) {
    is LimitWarning.AboveContext -> stringResource(R.string.fb_e3_limit_warn_gt_ctx, w.context)
    is LimitWarning.AboveServer -> stringResource(R.string.fb_e3_limit_warn_gt_server, w.server)
}

@Composable
private fun LimitItem(label: String, hint: String?, cd: String?, selected: Boolean, onClick: () -> Unit) {
    val c = LcTheme.colors
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f, fill = false), style = LcTheme.text.body, maxLines = 1, softWrap = false)
                if (hint != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(hint, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, softWrap = false)
                }
            }
        },
        onClick = onClick,
        leadingIcon = { Box(Modifier.size(24.dp)) { if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = c.accentText) } },
        modifier = if (cd != null) Modifier.semantics { contentDescription = cd; this.selected = selected } else Modifier.semantics { this.selected = selected },
    )
}

@Composable
private fun CapRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, source: CapSource, value: Boolean, divider: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(title, subtitle = sub, leadingIcon = icon, divider = divider) {
        SourceBadge(source)
        LcSwitch(value, onChange)
    }
}

