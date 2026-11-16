package com.ouor.localchat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ouor.localchat.R
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme

// ---------------- SessionListItem (.sess) ----------------

/** C-05 row: title (query match bold+accent), running spinner "응답 중", save-failed warning dot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionListItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    running: Boolean = false,
    saveFailed: Boolean = false,
    query: String = "",
    onLongClick: (() -> Unit)? = null,
) {
    val c = LcTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 48.dp).clip(LcRadius.md)
            .background(if (selected) c.stateSelected else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            highlightMatch(title, query, c.accentText), style = LcTheme.text.body.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LcSpinner(size = 12.dp)
                Text(stringResource(R.string.ui_responding), style = LcTheme.text.caption, color = c.accentText)
            }
        }
        if (saveFailed) {
            val d = stringResource(R.string.ui_save_failed)
            Box(Modifier.size(8.dp).background(c.warning, CircleShape).semantics { contentDescription = d })
        }
    }
}

private fun highlightMatch(text: String, query: String, color: Color): AnnotatedString {
    val i = if (query.isBlank()) -1 else text.indexOf(query, ignoreCase = true)
    if (i < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, i))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(text.substring(i, i + query.length)) }
        append(text.substring(i + query.length))
    }
}

// ---------------- ModelRow + CapabilityIcons (.mrow / .caps) ----------------

/** Tool calling · image input · reasoning; off ones are dimmed (never hidden) and announced. */
@Composable
fun CapabilityIcons(tools: Boolean, image: Boolean, reasoning: Boolean, modifier: Modifier = Modifier, labeled: Boolean = false) {
    val c = LcTheme.colors
    val names = listOf(stringResource(R.string.ui_cap_tools), stringResource(R.string.ui_cap_image), stringResource(R.string.ui_cap_reasoning))
    val flags = listOf(tools, image, reasoning)
    val desc = names.filterIndexed { i, _ -> flags[i] }.joinToString()
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Icons.Outlined.Build, Icons.Outlined.Image, Icons.Outlined.Lightbulb).forEachIndexed { i, icon ->
            val on = flags[i]
            // Welcome (SC-04) shows only the capabilities the model has, each with its name.
            if (labeled && !on) return@forEachIndexed
            Row(
                Modifier.height(24.dp).background(if (on) c.surfaceSunken else Color.Transparent, LcRadius.xs).padding(horizontal = if (labeled) 6.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(icon, null, Modifier.size(14.dp), tint = if (on) c.textSecondary else c.textDisabled)
                if (labeled) Text(names[i], style = LcTheme.text.caption, color = c.textSecondary)
            }
        }
    }
}

@Composable
fun ModelRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    notListed: Boolean = false,
    caps: @Composable () -> Unit = {},
) {
    val c = LcTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (selected) c.accentContainer else Color.Transparent)
            .combinedClickable(enabled = enabled, onClick = onClick).padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = LcTheme.text.bodyStrong, color = if (enabled) c.textPrimary else c.textDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (notListed) LcBadge(stringResource(R.string.ui_not_listed), variant = BadgeVariant.Warn)
            }
            if (subtitle != null) Text(subtitle, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        caps()
        if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.ui_selected), Modifier.size(18.dp), tint = c.onAccentContainer)
    }
}

/**
 * FB-3 group header: dot + name + [selected check] + count + chevron.
 * With [onToggle] the whole row toggles [expanded]; [dotColor] null = no dot (provider detail "모델").
 */
@Composable
fun EndpointGroupHeader(
    name: String,
    dotColor: Color?,
    count: Int,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    hasSelected: Boolean = false,
) {
    val c = LcTheme.colors
    val cd = stringResource(if (hasSelected) R.string.fb_e3_group_cd_selected else R.string.fb_e3_group_cd, name, count)
    val state = stringResource(if (expanded) R.string.fb_e3_group_expanded else R.string.fb_e3_group_collapsed)
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "chevron")
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onToggle != null) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
            .clearAndSetSemantics {
                contentDescription = cd
                if (onToggle != null) stateDescription = state
            }
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotColor != null) { EndpointDot(dotColor); Spacer(Modifier.width(10.dp)) }
        Text(name, style = LcTheme.text.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
        if (hasSelected) { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = c.accentText); Spacer(Modifier.width(8.dp)) }
        Text("$count", style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, softWrap = false)
        if (onToggle != null) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp).rotate(turn), tint = c.iconSecondary)
        }
    }
}

/** SC-01 provider preset: letter logo + name + base URL. */
@Composable
fun PresetRow(name: String, url: String, logoColor: Color, onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = false) {
    SettingsRow(name, modifier, subtitle = url, divider = divider, onClick = onClick, leading = {
        Box(Modifier.size(32.dp).background(logoColor, LcRadius.sm), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }, trailing = { SettingsChevron() })
}

// ---------------- ConnectionStatus ----------------

sealed interface ConnectionUi {
    data object Checking : ConnectionUi
    data class Ok(val modelCount: Int) : ConnectionUi
    data class Error(val reason: String) : ConnectionUi
}

/** SC-02 connection check line with recovery actions on error. */
@Composable
fun ConnectionStatus(state: ConnectionUi, onRecheck: () -> Unit, onSaveAnyway: () -> Unit, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    when (state) {
        ConnectionUi.Checking -> Row(modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcSpinner(size = 14.dp)
            Text(stringResource(R.string.ui_conn_checking), style = LcTheme.text.body, color = c.textSecondary)
        }
        is ConnectionUi.Ok -> Row(modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.CheckCircleOutline, null, Modifier.size(18.dp), tint = c.success)
            Text(stringResource(R.string.ui_conn_ok, state.modelCount), style = LcTheme.text.body, color = c.textPrimary)
        }
        is ConnectionUi.Error -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp), tint = c.danger)
                Text(state.reason, style = LcTheme.text.body, color = c.dangerText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LcButton(stringResource(R.string.ui_conn_recheck), onRecheck, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                LcButton(stringResource(R.string.ui_conn_save_anyway), onSaveAnyway, variant = ButtonVariant.Text, size = ButtonSize.Sm)
            }
        }
    }
}

/** SC-02a value source badge. */
@Composable
fun SourceBadge(source: CapSource, modifier: Modifier = Modifier) {
    val (text, variant) = when (source) {
        CapSource.SERVER -> R.string.ui_src_server to BadgeVariant.Accent
        CapSource.DEFAULT -> R.string.ui_src_default to BadgeVariant.Neutral
        CapSource.USER -> R.string.ui_src_user to BadgeVariant.Ok
        CapSource.UNKNOWN -> R.string.ui_src_unknown to BadgeVariant.Warn
    }
    LcBadge(stringResource(text), modifier, variant)
}

/** SC-18 2×2 numbers (label above value). */
@Composable
fun SummaryTiles(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f).background(c.surfaceSunken, LcRadius.md).padding(12.dp)) {
                        Text(label, style = LcTheme.text.caption, color = c.textTertiary)
                        Text(value, style = LcTheme.text.titleLarge, color = c.textPrimary)
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

