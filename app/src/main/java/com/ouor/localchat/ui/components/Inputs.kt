package com.ouor.localchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ouor.localchat.R
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme

// ---------------- ToggleChip (.chip.on/off/dis/agent-on) ----------------

enum class ToggleKind(val icon: ImageVector) { Reasoning(Icons.Outlined.Lightbulb), Search(Icons.Outlined.Language), Browser(Icons.Outlined.Web) }

/**
 * Composer toggle (reasoning / search / browser). Always icon + label unless [iconOnly] (narrow widths, off state).
 * Browser "on" uses agent purple. [locked] = ALWAYS_ON: shown on + lock, still clickable to explain.
 * When disabled, a tap shows [disabledReason] in a tooltip with an optional action (P-05).
 */
@Composable
fun ToggleChip(
    kind: ToggleKind,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    locked: Boolean = false,
    iconOnly: Boolean = false,
    disabledReason: String? = null,
    reasonActionLabel: String? = null,
    onReasonAction: (() -> Unit)? = null,
) {
    val c = LcTheme.colors
    val on = checked || locked
    val agent = kind == ToggleKind.Browser
    val (container, content, border) = when {
        !enabled -> Triple(Color.Transparent, c.textDisabled, c.border)
        on && agent -> Triple(c.agentContainer, c.onAgentContainer, c.agent.copy(alpha = 0.35f))
        on -> Triple(c.accentContainer, c.onAccentContainer, c.accent.copy(alpha = 0.35f))
        else -> Triple(Color.Transparent, c.textSecondary, c.border)
    }
    val stateText = stringResource(
        when {
            !enabled -> R.string.ui_state_unavailable
            on -> R.string.ui_state_on
            else -> R.string.ui_state_off
        },
    )
    LcTooltip(
        text = disabledReason.takeIf { !enabled },
        actionLabel = reasonActionLabel,
        onAction = onReasonAction,
    ) { showTip ->
        ChipSurface(
            onClick = { if (enabled) onClick() else showTip() },
            container = container, content = content, border = border,
            modifier = modifier.semantics {
                role = Role.Switch
                stateDescription = stateText
                if (iconOnly) contentDescription = label
            },
            horizontalPadding = PaddingValues(horizontal = if (iconOnly) 8.dp else 10.dp),
        ) {
            Icon(kind.icon, null, Modifier.size(16.dp))
            if (!iconOnly) Text(label, style = LcTheme.text.label, maxLines = 1)
            if (locked) Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp))
        }
    }
}

// ---------------- ModelChip (.chip.model) ----------------

enum class ModelChipVariant { Rail, Hero }
enum class ModelChipState { Normal, Missing, Loading }

@Composable
fun ModelChip(
    endpointName: String,
    modelName: String,
    dotColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ModelChipVariant = ModelChipVariant.Rail,
    state: ModelChipState = ModelChipState.Normal,
) {
    val c = LcTheme.colors
    val hero = variant == ModelChipVariant.Hero
    val fg = if (state == ModelChipState.Missing) c.warning else c.textPrimary
    ChipSurface(
        onClick = onClick,
        container = if (hero) c.surfaceRaised else Color.Transparent,
        content = fg,
        border = if (hero) c.border else null,
        modifier = modifier,
        height = if (hero) 40.dp else 28.dp,
        horizontalPadding = PaddingValues(start = if (hero) 14.dp else 8.dp, end = if (hero) 10.dp else 6.dp),
    ) {
        when (state) {
            ModelChipState.Loading -> LcSpinner(size = 12.dp)
            ModelChipState.Missing -> Icon(Icons.Outlined.WarningAmber, null, Modifier.size(16.dp))
            ModelChipState.Normal -> EndpointDot(dotColor)
        }
        val text = if (state == ModelChipState.Missing) stringResource(R.string.ui_model_missing) else if (endpointName.isEmpty()) modelName else "$endpointName · $modelName"
        Text(
            text, style = (if (hero) LcTheme.text.bodyStrong else LcTheme.text.label).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp))
    }
}

// ---------------- LcTextField (.tf) ----------------

enum class TextFieldKind { Text, Url, Secret, Password, Multiline }
enum class HelperTone { Normal, Error, Warn }

/**
 * Filled field (sunken, 12dp radius, focus ring 2dp accent). Secret = masked + show/hide + optional paste.
 */
@Composable
fun LcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    helper: String? = null,
    helperTone: HelperTone = HelperTone.Normal,
    kind: TextFieldKind = TextFieldKind.Text,
    enabled: Boolean = true,
    onPaste: (() -> Unit)? = null,
    /** Overrides the keyboard [kind] picks (e.g. KeyboardType.Number for counts). */
    keyboardOptions: KeyboardOptions? = null,
    /** Unit shown after the text, e.g. "토큰". */
    suffix: String? = null,
) {
    val c = LcTheme.colors
    var focused by remember { mutableStateOf(false) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    val masked = (kind == TextFieldKind.Secret || kind == TextFieldKind.Password) && !reveal
    val error = helperTone == HelperTone.Error
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = LcTheme.text.label, color = c.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = kind != TextFieldKind.Multiline,
            minLines = if (kind == TextFieldKind.Multiline) 3 else 1,
            textStyle = LcTheme.text.bodyLarge.copy(color = if (enabled) c.textPrimary else c.textDisabled),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions ?: KeyboardOptions(
                keyboardType = when (kind) {
                    TextFieldKind.Url -> KeyboardType.Uri
                    TextFieldKind.Secret, TextFieldKind.Password -> KeyboardType.Password
                    else -> KeyboardType.Text
                },
                autoCorrectEnabled = kind == TextFieldKind.Text || kind == TextFieldKind.Multiline,
            ),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .background(if (focused) c.surfaceRaised else c.surfaceSunken, LcRadius.md)
                        .then(
                            when {
                                error -> Modifier.border(2.dp, c.danger, LcRadius.md)
                                focused -> Modifier.border(2.dp, c.accent, LcRadius.md)
                                else -> Modifier
                            },
                        )
                        .padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = LcTheme.text.bodyLarge, color = c.textTertiary, maxLines = 1)
                        }
                        inner()
                    }
                    if (suffix != null) Text(suffix, style = LcTheme.text.bodyLarge, color = c.textTertiary, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp, end = 8.dp))
                    if (kind == TextFieldKind.Secret && onPaste != null) {
                        LcIconButton(Icons.Outlined.ContentPaste, stringResource(R.string.ui_paste), onPaste,
                            variant = IconButtonVariant.Secondary, size = IconButtonSize.Sm)
                    }
                    if (kind == TextFieldKind.Secret || kind == TextFieldKind.Password) {
                        LcIconButton(
                            if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            stringResource(if (reveal) R.string.ui_hide else R.string.ui_show),
                            { reveal = !reveal }, variant = IconButtonVariant.Secondary, size = IconButtonSize.Sm,
                        )
                    }
                }
            },
        )
        if (helper != null) {
            Text(
                helper, style = LcTheme.text.caption,
                color = when (helperTone) {
                    HelperTone.Normal -> c.textTertiary
                    HelperTone.Error -> c.dangerText
                    HelperTone.Warn -> c.warning
                },
            )
        }
    }
}

/** Drawer search pill (.search). */
@Composable
fun SearchPill(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = LcTheme.text.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accent),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.height(44.dp).background(c.surfaceSunken, LcRadius.full).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = c.textTertiary)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = LcTheme.text.body, color = c.textTertiary)
                    inner()
                }
            }
        },
    )
}

// ---------------- Switch / Checkbox / Radio (M3 + accent) ----------------

@Composable
fun LcSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LcTheme.colors
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = c.borderStrong, uncheckedBorderColor = c.borderStrong,
            disabledCheckedTrackColor = c.accent.copy(alpha = 0.4f), disabledUncheckedTrackColor = c.borderStrong.copy(alpha = 0.4f),
            disabledUncheckedBorderColor = Color.Transparent, disabledCheckedThumbColor = Color.White,
            disabledUncheckedThumbColor = Color.White,
        ),
    )
}

@Composable
fun LcCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LcTheme.colors
    Checkbox(
        checked, onCheckedChange, modifier, enabled,
        colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderStrong, checkmarkColor = Color.White),
    )
}

@Composable
fun LcRadio(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LcTheme.colors
    RadioButton(selected, onClick, modifier, enabled, colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.borderStrong))
}

// ---------------- SegmentedControl (.seg) ----------------

/** Pill segmented control (M3 SegmentedButton re-skinned). Use [segmentedFits] first; otherwise [LcSelectMenu]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    SingleChoiceSegmentedButtonRow(
        modifier.fillMaxWidth().background(c.surfaceSunken, LcRadius.full).padding(3.dp),
        space = 2.dp,
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            SegmentedButton(
                selected = selected,
                onClick = { onSelect(i) },
                shape = LcRadius.full,
                modifier = Modifier.height(32.dp),
                border = BorderStroke(1.dp, if (selected) c.border else Color.Transparent),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.surfaceRaised, activeContentColor = c.textPrimary,
                    inactiveContainerColor = Color.Transparent, inactiveContentColor = c.textSecondary,
                ),
                icon = {},
                label = { Text(label, style = LcTheme.text.label, maxLines = 1) },
            )
        }
    }
}

data class MenuOption(val label: String, val hint: String? = null, val enabled: Boolean = true)

/**
 * Radio-style menu: reasoning effort (M-08, server levels + "off"), and the fallback for [SegmentedControl].
 * [lockedNote]: ALWAYS_ON explanation shown under a single locked option.
 */
@Composable
fun LcSelectMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<MenuOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    locked: Boolean = false,
    lockedNote: String? = null,
) {
    val c = LcTheme.colors
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        shape = LcRadius.md, containerColor = c.surfaceRaised,
        border = if (c.isDark) BorderStroke(1.dp, c.floatingBorder) else null,
        modifier = Modifier.width(220.dp),
    ) {
        options.forEachIndexed { i, o ->
            val sel = i == selectedIndex
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.label, style = if (sel) LcTheme.text.bodyStrong else LcTheme.text.body)
                        if (o.hint != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(o.hint, style = LcTheme.text.caption, color = c.textTertiary)
                        }
                    }
                },
                trailingIcon = when {
                    locked -> ({ Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp)) })
                    sel -> ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) })
                    else -> null
                },
                onClick = { if (!locked) onSelect(i) },
                enabled = o.enabled,
                modifier = Modifier.padding(horizontal = 6.dp).background(if (sel) c.accentContainer else Color.Transparent, LcRadius.sm),
                colors = MenuDefaults.itemColors(
                    textColor = if (sel) c.onAccentContainer else c.textPrimary,
                    trailingIconColor = if (sel) c.onAccentContainer else c.iconSecondary,
                ),
            )
        }
        if (lockedNote != null) {
            Text(lockedNote, style = LcTheme.text.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
