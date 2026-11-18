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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
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
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme
import kotlin.math.roundToInt

// ---------------- ActivityBar (.agentbar) ----------------

/**
 * Always under the browser toolbar, one fixed height (P-STABLE): [tool] is what an AI runs on the shown tab right now
 * (browser-layers 6-2), with [chat] naming the chat when it is another one; [onStop] stops that chat's run. Idle = an
 * empty bar of the same height.
 */
@Composable
fun ActivityBar(tool: String?, modifier: Modifier = Modifier, chat: String? = null, onStop: () -> Unit = {}) {
    val c = LcTheme.colors
    val bg = if (tool != null) c.agentContainer else c.surfaceSunken
    Row(
        modifier.fillMaxWidth().height(40.dp).background(bg).drawBehind {
            if (tool != null) drawRect(c.agent, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width * 0.35f, 2.dp.toPx()))
        }.padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tool == null) return@Row
        LcBadge(stringResource(R.string.ui_ai), variant = BadgeVariant.Ai)
        Text(
            listOfNotNull(tool, chat).joinToString(" · "), style = LcTheme.text.label.copy(fontWeight = FontWeight.SemiBold),
            color = c.onAgentContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        BarButton(stringResource(R.string.ui_stop), Icons.Rounded.Stop, onStop)
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

// ---------------- BrowserStatusStrip (FB-1c) ----------------

/** One-line "AI is working" strip above the composer = the browser panel's Peek state (FB-1c). Body tap or an upward drag = [onOpen]. */
@Composable
fun BrowserStatusStrip(title: String, host: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    val desc = stringResource(R.string.fb_e4_strip_cd, title)
    val density = LocalDensity.current
    val dragged = remember { floatArrayOf(0f) }
    val drag = rememberDraggableState { dragged[0] += it }
    Surface(
        onClick = onOpen, shape = LcRadius.full, color = c.agentContainer, contentColor = c.onAgentContainer,
        modifier = modifier.fillMaxWidth()
            .draggable(drag, Orientation.Vertical, onDragStarted = { dragged[0] = 0f }, onDragStopped = { v ->
                // shortcut: opens on release (24dp up or a 125dp/s fling) instead of following the finger; the strip and the panel share no drag state.
                if (dragged[0] < -with(density) { 24.dp.toPx() } || v < -with(density) { 125.dp.toPx() }) onOpen()
            })
            .semantics { contentDescription = desc; liveRegion = LiveRegionMode.Polite },
    ) {
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LcBadge(stringResource(R.string.ui_ai), variant = BadgeVariant.Ai)
                BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    val hostMax = maxWidth * 0.4f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = LcTheme.text.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(" · $host", style = LcTheme.text.caption, color = c.onAgentContainer.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = hostMax))
                    }
                }
            }
            WorkingBar(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp), color = c.agent)
        }
    }
}

// ---------------- BrowserToolbar (.btool) ----------------

/** 52dp: [collapse] · back · forward · URL pill (lock/warning · url · reload) · tab count; [progress] = loading bar. */
@Composable
fun BrowserToolbar(
    url: String,
    tabCount: Int,
    /** Null hides the chevron (FB-5: the panel collapses by dragging its handle). */
    onCollapse: (() -> Unit)?,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    /** FB5 B2: edits [fullUrl] in place and goes to what the user typed (IME Go); null = the address is not editable. */
    onNavigate: ((String) -> Unit)?,
    /** The address to edit (scheme included); [url] is only the short form shown. */
    fullUrl: String,
    onTabs: () -> Unit,
    modifier: Modifier = Modifier,
    secure: Boolean = true,
    canGoBack: Boolean = true,
    canGoForward: Boolean = false,
    progress: Float? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = LcTheme.colors
    // A new address, or the right to edit taken away (the AI took control), ends an edit; it never comes back by itself.
    var editing by remember(fullUrl, onNavigate != null) { mutableStateOf(false) }
    // P-STABLE L2: the loading bar overlays the toolbar's bottom edge, so nothing below moves when it comes and goes.
    Box(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = if (onCollapse == null) 8.dp else 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (onCollapse != null) LcIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.ui_collapse), onCollapse, size = IconButtonSize.Sm)
            LcIconButton(Icons.Rounded.ArrowBack, stringResource(R.string.ui_back), onBack, size = IconButtonSize.Sm, enabled = canGoBack)
            LcIconButton(Icons.Rounded.ArrowForward, stringResource(R.string.ui_forward), onForward, size = IconButtonSize.Sm, enabled = canGoForward)
            // Not editable: not a button either (no ripple, not read as one); the look is the same (P-STABLE).
            Surface(onClick = { editing = true }, enabled = onNavigate != null, modifier = Modifier.weight(1f).height(36.dp), shape = LcRadius.full, color = c.surfaceSunken, contentColor = c.textPrimary) {
                Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (secure) Icon(Icons.Outlined.Lock, stringResource(R.string.ui_secure), Modifier.size(14.dp))
                    else Icon(Icons.Outlined.WarningAmber, stringResource(R.string.ui_insecure), Modifier.size(14.dp), tint = c.warning)
                    if (editing && onNavigate != null) UrlEditor(fullUrl, { editing = false; onNavigate(it) }, { editing = false }, Modifier.weight(1f))
                    else Text(url, style = LcTheme.text.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    LcIconButton(Icons.Rounded.Refresh, stringResource(R.string.ui_reload), onReload, size = IconButtonSize.Xs)
                }
            }
            val tabsDesc = stringResource(R.string.ui_tabs_desc, tabCount)
            Box(Modifier.size(48.dp).clip(LcRadius.full).combinedClickableCompat(onTabs).semantics { contentDescription = tabsDesc }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp).border(1.8.dp, c.iconPrimary, LcRadius.xs).testTag(TAB_COUNT_TAG), contentAlignment = Alignment.Center) {
                    // FB5 B3: a line exactly as tall as the digits, trimmed, so they sit in the box's middle (not the default line's).
                    Text("$tabCount", color = c.iconPrimary, style = LocalTextStyle.current.merge(TextStyle(fontSize = 11.sp, lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))))
                }
            }
            trailing()
        }
        if (progress != null) LinearProgressIndicator({ progress }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp), color = c.accent, trackColor = Color.Transparent, gapSize = 0.dp, drawStopIndicator = {})
    }
}

/** The tab count's box (FB5 B3 test). */
internal const val TAB_COUNT_TAG = "tab-count"

/**
 * FB5 B2: the address edited where it is shown (no dialog): the whole URL, all selected; Go navigates, Back or leaving
 * the field cancels. Same text style as the shown address, so the bar keeps its size (P-STABLE). The first Back closes
 * the keyboard, not the app's handler, so a keyboard that closes also cancels: one Back ends the edit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UrlEditor(url: String, onGo: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var value by remember { mutableStateOf(TextFieldValue(url, TextRange(0, url.length))) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val desc = stringResource(R.string.ui_edit_url)
    BackHandler(onBack = onCancel) // no soft keyboard (hardware keyboard)
    val imeUp = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) { if (imeUp) imeSeen = true else if (imeSeen) onCancel() }
    BasicTextField(
        value, { value = it },
        modifier.focusRequester(focus).semantics { contentDescription = desc }
            .onFocusChanged { if (focused && !it.isFocused) onCancel(); focused = it.isFocused },
        textStyle = LcTheme.text.label.copy(color = LcTheme.colors.textPrimary), singleLine = true,
        cursorBrush = SolidColor(LcTheme.colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { onGo(value.text) }),
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = combinedClickable(onClick = onClick)

/**
 * Browser panel container (phone sheet body / tablet pane): toolbar, activity bar, page. P-STABLE M5 (FB3 ui B1): the
 * optional [banner] sits in the flow under the page, so the toolbar, activity bar and page top never move and only the
 * page's bottom edge rises; nothing of the page is covered.
 */
@Composable
fun BrowserPanelFrame(
    toolbar: @Composable () -> Unit,
    activity: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    banner: (@Composable () -> Unit)? = null,
    page: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().background(LcTheme.colors.surfaceRaised)) {
        toolbar()
        activity()
        Box(Modifier.weight(1f).fillMaxWidth()) { page() }
        banner?.invoke()
    }
}

// ---------------- TabCard / TabGrid (.tabcard) ----------------

/**
 * A tab in the list (6-1): no owner. [activity] = the tool an AI runs on it now (AI border and its name), [asleep] dims
 * the thumbnail, [badges] are policies' BADGE notices. [enabled] false = a placeholder slot (no tap, no focus).
 */
@Composable
fun TabCard(
    title: String, url: String, onClick: () -> Unit, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null,
    activity: String? = null, asleep: Boolean = false, badges: List<String> = emptyList(), enabled: Boolean = true,
    thumbnail: @Composable () -> Unit = {},
) {
    val c = LcTheme.colors
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.alpha(if (enabled) 1f else 0.55f), shape = LcRadius.lg, color = c.surfaceRaised,
        border = if (activity != null) BorderStroke(2.dp, c.agent) else BorderStroke(1.dp, c.border),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(120.dp).background(Color.White)) {
                Box(Modifier.fillMaxSize().alpha(if (asleep) 0.4f else 1f)) { thumbnail() }
                if (asleep) Icon(Icons.Outlined.Bedtime, stringResource(R.string.ui_tab_asleep), Modifier.align(Alignment.Center).size(24.dp), tint = c.textSecondary)
                if (onClose != null && enabled) {
                    LcIconButton(Icons.Rounded.Close, stringResource(R.string.ui_close_tab), onClose, Modifier.align(Alignment.TopEnd), variant = IconButtonVariant.Raised, size = IconButtonSize.Xs)
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (activity != null) LcBadge(activity, variant = BadgeVariant.Ai)
                    badges.forEach { LcBadge(it, variant = BadgeVariant.Neutral) }
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
 * 2dp agent outline + 6dp halo + label to the right (above/below when no room). Fades 0→1 120ms, holds ~1s, 1→0 200ms per [key].
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
        val gap = with(density) { 8.dp.toPx() }
        // Mockup B-09: label beside the element; above/below only when ~96dp does not fit on the right.
        val right = rect.right + gap + with(density) { 96.dp.toPx() } <= constraints.maxWidth
        val above = rect.top >= labelH
        val pos = when {
            right -> IntOffset((rect.right + gap).roundToInt(), (rect.center.y - with(density) { 10.dp.toPx() }).roundToInt())
            above -> IntOffset(rect.left.roundToInt(), (rect.top - labelH).roundToInt())
            else -> IntOffset(rect.left.roundToInt(), (rect.bottom + with(density) { 4.dp.toPx() }).roundToInt())
        }
        Row(
            Modifier.offset { pos }
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
    /** SC-13 crop around the target element; null hides it. */
    preview: androidx.compose.ui.graphics.ImageBitmap? = null,
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
        if (preview != null) {
            androidx.compose.foundation.Image(
                preview, stringResource(R.string.ui_approval_preview),
                Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(LcRadius.md).border(1.dp, c.border, LcRadius.md),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
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

