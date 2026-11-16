package com.ouor.localchat.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ouor.localchat.R
import com.ouor.localchat.ui.theme.LcElevation
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme
import kotlin.math.roundToInt

enum class BrowserOwner { Ai, Me, Waiting }

// ---------------- OwnershipBar (.agentbar) ----------------

/**
 * Always visible under the browser toolbar (B-03): who is operating.
 * Ai = purple "AI 조작 중 · {tool}" + [직접 조작]; Me = "나" + [AI에게 맡기기]; Waiting = warning (handoff, AI waits).
 */
@Composable
fun OwnershipBar(owner: BrowserOwner, modifier: Modifier = Modifier, toolName: String? = null, onTakeOver: () -> Unit = {}, onGiveBack: () -> Unit = {}) {
    val c = LcTheme.colors
    val (bg, fg) = when (owner) {
        BrowserOwner.Ai -> c.agentContainer to c.onAgentContainer
        BrowserOwner.Me -> c.surfaceSunken to c.textPrimary
        BrowserOwner.Waiting -> c.surfaceSunken to c.textPrimary
    }
    Row(
        modifier.fillMaxWidth().height(40.dp).background(bg).drawBehind {
            if (owner == BrowserOwner.Ai) drawRect(c.agent, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width * 0.35f, 2.dp.toPx()))
        }.padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (owner) {
            BrowserOwner.Ai -> {
                LcBadge(stringResource(R.string.ui_ai), variant = BadgeVariant.Ai)
                Text(stringResource(R.string.ui_ai_operating) + (toolName?.let { " · " } ?: ""), style = LcTheme.text.label, color = fg)
                if (toolName != null) Text(toolName, style = LcTheme.text.label.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.offset(x = (-8).dp))
                Box(Modifier.weight(1f))
                BarButton(stringResource(R.string.ui_take_over), Icons.Outlined.FrontHand, onTakeOver)
            }
            BrowserOwner.Me -> {
                Icon(Icons.Outlined.Person, null, Modifier.size(16.dp), tint = fg)
                Text(stringResource(R.string.ui_me), style = LcTheme.text.label, color = fg)
                Box(Modifier.weight(1f))
                BarButton(stringResource(R.string.ui_give_to_ai), Icons.Rounded.AutoAwesome, onGiveBack)
            }
            BrowserOwner.Waiting -> {
                Icon(Icons.Outlined.Person, null, Modifier.size(16.dp), tint = fg)
                Text(stringResource(R.string.ui_me), style = LcTheme.text.label, color = fg)
                Text(stringResource(R.string.ui_manual_ai_waiting), style = LcTheme.text.label, color = c.textSecondary)
                Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BarButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    val c = LcTheme.colors
    Surface(onClick = onClick, shape = LcRadius.full, color = c.surfaceRaised, contentColor = c.textPrimary, border = BorderStroke(1.dp, c.border)) {
        Row(Modifier.height(30.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(16.dp))
            Text(text, style = LcTheme.text.label)
        }
    }
}

// ---------------- HandoffBanner (.handoff) ----------------

/** B-05: reason + explanation + [작업 중지] [AI에게 돌려주기]. */
@Composable
fun HandoffBanner(title: String, body: String, onStop: () -> Unit, onGiveBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp).background(c.warningContainer, LcRadius.lg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.FrontHand, null, Modifier.size(20.dp), tint = c.onWarningContainer)
            Column {
                Text(title, style = LcTheme.text.bodyStrong, color = c.onWarningContainer)
                Text(body, style = LcTheme.text.body, color = c.onWarningContainer.copy(alpha = 0.85f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LcButton(stringResource(R.string.ui_stop_task), onStop, variant = ButtonVariant.Text, size = ButtonSize.Sm)
            Box(Modifier.weight(1f))
            LcButton(stringResource(R.string.ui_give_back_to_ai), onGiveBack, variant = ButtonVariant.Agent, icon = Icons.Rounded.AutoAwesome)
        }
    }
}

// ---------------- BrowserPeekCard (.peek) ----------------

/** Collapsed browser preview above the composer: thumbnail · owner + action · URL · [직접 조작] · expand. */
@Composable
fun BrowserPeekCard(
    owner: BrowserOwner,
    action: String,
    url: String,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onTakeOver: (() -> Unit)? = null,
    thumbnail: @Composable () -> Unit = {},
) {
    val c = LcTheme.colors
    Surface(
        onClick = onExpand, modifier = modifier.fillMaxWidth().heightIn(min = 72.dp), shape = LcRadius.lg, color = c.surfaceRaised,
        shadowElevation = LcElevation.e2, border = if (c.isDark) BorderStroke(1.dp, c.floatingBorder) else null,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(88.dp, 56.dp).clip(LcRadius.sm).background(Color.White).border(1.dp, c.border, LcRadius.sm)) { thumbnail() }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (owner == BrowserOwner.Ai) LcBadge(stringResource(R.string.ui_ai), variant = BadgeVariant.Ai)
                    Text(action, style = LcTheme.text.bodyStrong, color = c.textPrimary, maxLines = 1)
                }
                Text(url, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onTakeOver != null) LcButton(stringResource(R.string.ui_take_over), onTakeOver, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.ui_expand), Modifier.size(20.dp), tint = c.iconSecondary)
        }
    }
}

// ---------------- BrowserToolbar (.btool) ----------------

/** 52dp: collapse · back · forward · URL pill (lock/warning · url · reload) · tab count; [progress] = loading bar. */
@Composable
fun BrowserToolbar(
    url: String,
    tabCount: Int,
    onCollapse: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onUrlClick: () -> Unit,
    onTabs: () -> Unit,
    modifier: Modifier = Modifier,
    secure: Boolean = true,
    canGoBack: Boolean = true,
    canGoForward: Boolean = false,
    progress: Float? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            LcIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.ui_collapse), onCollapse, size = IconButtonSize.Sm)
            LcIconButton(Icons.Rounded.ArrowBack, stringResource(R.string.ui_back), onBack, size = IconButtonSize.Sm, enabled = canGoBack)
            LcIconButton(Icons.Rounded.ArrowForward, stringResource(R.string.ui_forward), onForward, size = IconButtonSize.Sm, enabled = canGoForward)
            Surface(onClick = onUrlClick, modifier = Modifier.weight(1f).height(36.dp), shape = LcRadius.full, color = c.surfaceSunken, contentColor = c.textPrimary) {
                Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (secure) Icon(Icons.Outlined.Lock, stringResource(R.string.ui_secure), Modifier.size(14.dp))
                    else Icon(Icons.Outlined.WarningAmber, stringResource(R.string.ui_insecure), Modifier.size(14.dp), tint = c.warning)
                    Text(url, style = LcTheme.text.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    LcIconButton(Icons.Rounded.Refresh, stringResource(R.string.ui_reload), onReload, size = IconButtonSize.Xs)
                }
            }
            val tabsDesc = stringResource(R.string.ui_tabs_desc, tabCount)
            Box(Modifier.size(48.dp).clip(LcRadius.full).combinedClickableCompat(onTabs).semantics { contentDescription = tabsDesc }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp).border(1.8.dp, c.iconPrimary, LcRadius.xs), contentAlignment = Alignment.Center) {
                    Text("$tabCount", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.iconPrimary)
                }
            }
            trailing()
        }
        if (progress != null) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = Color.Transparent, gapSize = 0.dp, drawStopIndicator = {})
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = combinedClickable(onClick = onClick)

/** Browser panel container (phone sheet body / tablet pane): toolbar, ownership bar, optional banner, page. */
@Composable
fun BrowserPanelFrame(
    toolbar: @Composable () -> Unit,
    ownership: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    banner: (@Composable () -> Unit)? = null,
    page: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().background(LcTheme.colors.surfaceRaised)) {
        toolbar()
        if (banner != null) banner() else ownership()
        Box(Modifier.weight(1f).fillMaxWidth()) { page() }
        if (banner != null) ownership()
    }
}

// ---------------- TabCard / TabGrid (.tabcard) ----------------

enum class TabOwner { Ai, Me, OtherChat }

@Composable
fun TabCard(title: String, url: String, owner: TabOwner, onClick: () -> Unit, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, thumbnail: @Composable () -> Unit = {}) {
    val c = LcTheme.colors
    val other = owner == TabOwner.OtherChat
    Surface(
        onClick = onClick, enabled = !other, modifier = modifier.alpha(if (other) 0.55f else 1f), shape = LcRadius.lg, color = c.surfaceRaised,
        border = if (owner == TabOwner.Ai) BorderStroke(2.dp, c.agent) else BorderStroke(1.dp, c.border),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(120.dp).background(Color.White)) {
                thumbnail()
                if (onClose != null && !other) {
                    LcIconButton(Icons.Rounded.Close, stringResource(R.string.ui_close_tab), onClose, Modifier.align(Alignment.TopEnd), variant = IconButtonVariant.Raised, size = IconButtonSize.Xs)
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (owner) {
                        TabOwner.Ai -> LcBadge(stringResource(R.string.ui_ai), variant = BadgeVariant.Ai)
                        TabOwner.Me -> LcBadge(stringResource(R.string.ui_me), variant = BadgeVariant.Me)
                        TabOwner.OtherChat -> LcBadge(stringResource(R.string.ui_other_chat), variant = BadgeVariant.Neutral)
                    }
                    Text(title, style = LcTheme.text.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(url, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Two-column grid of [TabCard]s (SC-12). */
@Composable
fun <T> TabGrid(tabs: List<T>, modifier: Modifier = Modifier, card: @Composable (T) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(2), modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tabs) { card(it) }
    }
}

// ---------------- ElementHighlight (.hl) ----------------

/**
 * B-09 overlay layer over the page. [rect] is in this layer's px (caller scales CSS px by view.width / innerWidth).
 * 2dp agent outline + 6dp halo + label above (below when no room). Fades 0→1 120ms, holds ~1s, 1→0 200ms per [key].
 * Pointer events pass through (no input modifiers).
 */
@Composable
fun ElementHighlightLayer(rect: Rect?, label: String, icon: ImageVector?, key: Any?, modifier: Modifier = Modifier, holdMillis: Int = 1000) {
    val c = LcTheme.colors
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(key, rect) {
        if (rect == null) { alpha.snapTo(0f); return@LaunchedEffect }
        alpha.animateTo(1f, tween(120))
        kotlinx.coroutines.delay(holdMillis.toLong())
        alpha.animateTo(0f, tween(200))
    }
    if (rect == null) return
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().alpha(alpha.value)) {
        val halo = with(density) { 6.dp.toPx() }
        val stroke = with(density) { 2.dp.toPx() }
        Box(
            Modifier.fillMaxSize().drawBehind {
                val r = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                drawRoundRect(c.agent.copy(alpha = 0.22f), rect.topLeft - androidx.compose.ui.geometry.Offset(halo, halo), rect.size.copy(rect.width + halo * 2, rect.height + halo * 2), r, style = androidx.compose.ui.graphics.drawscope.Stroke(halo - stroke))
                drawRoundRect(c.agent, rect.topLeft, rect.size, r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            },
        )
        val labelH = with(density) { 24.dp.toPx() }
        val above = rect.top >= labelH
        Row(
            Modifier.offset { IntOffset(rect.left.roundToInt(), if (above) (rect.top - labelH).roundToInt() else (rect.bottom + with(density) { 4.dp.toPx() }).roundToInt()) }
                .height(20.dp).background(c.agent, LcRadius.xs).padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(12.dp), tint = Color.White)
            Text(label, style = LcTheme.text.micro, color = Color.White, maxLines = 1)
        }
    }
}

// ---------------- ApprovalSheet content (.appr) ----------------

/**
 * SC-13 body: icon + title + description + target host + optional full code (execute) + [거부] [허용].
 * Put inside `LcBottomSheet(locked = true)`.
 */
@Composable
fun ApprovalContent(
    icon: ImageVector,
    title: String,
    description: String,
    target: String,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
    code: String? = null,
    allowLabel: String = stringResource(R.string.ui_allow),
) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(c.warningContainer, LcRadius.md), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = c.onWarningContainer)
            }
            Column {
                Text(title, style = LcTheme.text.titleSmall, color = c.textPrimary)
                Text(target, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(description, style = LcTheme.text.body, color = c.textSecondary)
        if (code != null) {
            SelectionContainer {
                Text(
                    code, style = LcTheme.text.code, color = c.textPrimary,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(LcRadius.md).background(c.codeBg).border(1.dp, c.border, LcRadius.md)
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcButton(stringResource(R.string.ui_deny), onDeny, Modifier.weight(1f), ButtonVariant.Tonal, ButtonSize.Lg)
            LcButton(allowLabel, onAllow, Modifier.weight(1f), ButtonVariant.Primary, ButtonSize.Lg)
        }
    }
}

