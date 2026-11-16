package com.ouor.localchat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ouor.localchat.R
import com.ouor.localchat.ui.theme.LcElevation
import com.ouor.localchat.ui.theme.LcMotion
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme
import kotlinx.coroutines.launch

// ---------------- Progress ----------------

@Composable
fun LcSpinner(modifier: Modifier = Modifier, size: Dp = 14.dp, color: Color = LcTheme.colors.accent) {
    CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = 2.dp, trackColor = LcTheme.colors.borderStrong, strokeCap = StrokeCap.Round)
}

/** Linear 6dp bar; [progress] null = indeterminate. */
@Composable
fun LcLinearProgress(progress: Float?, modifier: Modifier = Modifier, color: Color = LcTheme.colors.accent) {
    val c = LcTheme.colors
    val m = modifier.fillMaxWidth().height(6.dp).clip(LcRadius.full)
    if (progress == null) LinearProgressIndicator(m, color = color, trackColor = c.surfaceSunken, gapSize = 0.dp)
    else LinearProgressIndicator({ progress.coerceIn(0f, 1f) }, m, color = color, trackColor = c.surfaceSunken, gapSize = 0.dp, drawStopIndicator = {})
}

// ---------------- ContextGauge (.gauge) ----------------

/** Ring of [fraction] (0..1) with gauge color by level. */
@Composable
fun GaugeRing(fraction: Float, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp, stroke: Dp = 3.dp) {
    val track = LcTheme.colors.borderStrong
    Canvas(modifier.size(size)) {
        val s = Stroke(stroke.toPx(), cap = StrokeCap.Butt)
        val inset = stroke.toPx() / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke.toPx(), this.size.height - stroke.toPx())
        val tl = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, false, tl, arcSize, style = s)
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, tl, arcSize, style = s)
    }
}

@Composable
private fun gaugeColor(level: GaugeLevel) = when (level) {
    GaugeLevel.Normal -> LcTheme.colors.iconSecondary
    GaugeLevel.Warn -> LcTheme.colors.warning
    GaugeLevel.High -> LcTheme.colors.danger
}

/**
 * A-07 rail gauge. [percent] null = window unknown → shows [tokensText] only.
 * [estimate] prefixes "~" (token count estimated before the server reports usage).
 */
@Composable
fun ContextGauge(percent: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, estimate: Boolean = false, tokensText: String? = null, enabled: Boolean = true) {
    val c = LcTheme.colors
    val level = gaugeLevel(percent)
    val text = gaugeText(percent, estimate, tokensText)
    val desc = stringResource(R.string.ui_gauge_desc, text)
    ChipSurface(
        onClick = onClick, container = c.surfaceSunken, content = if (level == GaugeLevel.High) c.dangerText else c.textSecondary,
        border = null, height = 28.dp, modifier = modifier.semantics { contentDescription = desc }, enabled = enabled,
        horizontalPadding = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, end = 9.dp),
    ) {
        GaugeRing((percent ?: 0) / 100f, gaugeColor(level))
        Text(text, style = LcTheme.text.caption.copy(fontWeight = FontWeight.Medium), maxLines = 1, softWrap = false)
    }
}

/** Context usage detail card (A-07 tap target). Texts are pre-formatted by the caller. */
@Composable
fun ContextUsageCard(
    percent: Int,
    breakdown: String,
    limit: String,
    note: String,
    onSummarize: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = LcTheme.colors
    val level = gaugeLevel(percent)
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.height(28.dp).background(c.surfaceSunken, LcRadius.full).padding(start = 6.dp, end = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                GaugeRing(percent / 100f, gaugeColor(level))
                Text("$percent%", style = LcTheme.text.caption, color = if (level == GaugeLevel.High) c.dangerText else c.textSecondary)
            }
            Text(stringResource(R.string.ui_context_usage), style = LcTheme.text.titleSmall, color = c.textPrimary)
        }
        LcLinearProgress(percent / 100f, color = gaugeColor(level))
        Row {
            Text(breakdown, style = LcTheme.text.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
            Text(limit, style = LcTheme.text.caption, color = c.textSecondary)
        }
        Text(note, style = LcTheme.text.caption, color = c.textTertiary)
        if (onSummarize != null) {
            LcButton(stringResource(R.string.ui_summarize_now), onSummarize, Modifier.align(Alignment.End), ButtonVariant.Tonal, ButtonSize.Sm)
        }
    }
}

// ---------------- Banner (.banner) ----------------

enum class BannerVariant { Info, Warn, Err, Agent, Success }

data class BannerAction(val label: String, val onClick: () -> Unit)

/** One component for all inline warnings/errors/notices (design-system 9.6). Error in the reply slot uses ErrorCard. */
@Composable
fun Banner(
    text: String,
    variant: BannerVariant,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actions: List<BannerAction> = emptyList(),
    stackedActions: Boolean = false,
) {
    val c = LcTheme.colors
    val (bg, fg) = when (variant) {
        BannerVariant.Info -> c.accentContainer to c.onAccentContainer
        BannerVariant.Warn -> c.warningContainer to c.onWarningContainer
        BannerVariant.Err -> c.dangerContainer to c.onDangerContainer
        BannerVariant.Agent -> c.agentContainer to c.onAgentContainer
        BannerVariant.Success -> c.successContainer to c.onSuccessContainer
    }
    val ic = icon ?: when (variant) {
        BannerVariant.Info -> Icons.Outlined.Info
        BannerVariant.Warn -> Icons.Outlined.WarningAmber
        BannerVariant.Err -> Icons.Outlined.ErrorOutline
        BannerVariant.Agent -> Icons.Rounded.AutoAwesome
        BannerVariant.Success -> Icons.Rounded.CheckCircleOutline
    }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val buttonStyle = LcTheme.text.bodyStrong
    BoxWithConstraints(modifier.fillMaxWidth().background(bg, LcRadius.md).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
        // FB-2: buttons sit beside the text only while they take ≤ 40% of the row; otherwise they drop below, right-aligned.
        val buttonsPx = actions.sumOf { measurer.measure(it.label, buttonStyle).size.width } + with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.roundToPx() } * actions.size
        val inline = !stackedActions && buttonsPx <= constraints.maxWidth * 0.4f
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ic, null, Modifier.size(20.dp), tint = fg)
                Spacer(Modifier.width(10.dp))
                Text(text, style = LcTheme.text.body, color = fg, modifier = Modifier.weight(1f))
                if (inline) actions.forEach { a -> BannerButton(a, fg) }
            }
            if (!inline && !stackedActions && actions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions.forEach { a -> BannerButton(a, fg) } }
            }
            if (stackedActions && actions.isNotEmpty()) {
                Row(Modifier.padding(start = 24.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    actions.forEachIndexed { i, a ->
                        if (i == 0) LcButton(a.label, a.onClick, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                        else BannerButton(a, fg)
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerButton(a: BannerAction, fg: Color) {
    TextButton(onClick = a.onClick, shape = LcRadius.full) { Text(a.label, style = LcTheme.text.bodyStrong, color = fg) }
}

// ---------------- Snackbar (.snack) ----------------

@Composable
fun LcSnackbar(message: String, modifier: Modifier = Modifier, icon: ImageVector? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val c = LcTheme.colors
    Surface(modifier.fillMaxWidth(), shape = LcRadius.md, color = c.surfaceInverse, contentColor = c.onInverse, shadowElevation = LcElevation.e2) {
        Row(Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
            Text(message, style = LcTheme.text.body, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                // Inverse surface: use the opposite theme's accent text for contrast.
                TextButton(onAction) { Text(actionLabel, style = LcTheme.text.bodyStrong, color = if (c.isDark) Color(0xFF1D4ED8) else Color(0xFF9DBBFF)) }
            }
        }
    }
}

/** Drop-in SnackbarHost rendering [LcSnackbar]. */
@Composable
fun LcSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier.padding(horizontal = 12.dp)) { d: SnackbarData ->
        LcSnackbar(d.visuals.message, actionLabel = d.visuals.actionLabel, onAction = { d.performAction() })
    }
}

// ---------------- Tooltip (.tip) ----------------

/**
 * Reason tooltip (P-05). [text] null = no tooltip; the content still renders.
 * The content gets `show` to open it (disabled taps), long-press also opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LcTooltip(
    text: String?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable (show: () -> Unit) -> Unit,
) {
    if (text == null) { content {}; return }
    val c = LcTheme.colors
    val state = rememberTooltipState(isPersistent = actionLabel != null)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
        tooltip = {
            RichTooltip(
                colors = TooltipDefaults.richTooltipColors(containerColor = c.surfaceInverse, contentColor = c.onInverse, actionContentColor = if (c.isDark) Color(0xFF1D4ED8) else Color(0xFF9DBBFF)),
                shape = LcRadius.sm,
                action = if (actionLabel != null && onAction != null) {
                    { TextButton({ state.dismiss(); onAction() }) { Text(actionLabel, style = LcTheme.text.label) } }
                } else null,
            ) { Text(text, style = LcTheme.text.label) }
        },
        state = state,
    ) { content { scope.launch { state.show() } } }
}

// ---------------- EmptyState ----------------

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String?, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(56.dp).background(c.surfaceSunken, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = c.iconSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = LcTheme.text.titleSmall, color = c.textPrimary)
        if (body != null) Text(body, style = LcTheme.text.body, color = c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            LcButton(actionLabel, onAction)
        }
    }
}

// ---------------- RetryStatus / ReadingBars / ScrollToBottom ----------------

/** P-09 rail badge "재시도 중 (n/5)". */
@Composable
fun RetryStatus(attempt: Int, max: Int, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    Row(
        modifier.height(22.dp).background(c.warningContainer, LcRadius.full).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LcSpinner(size = 10.dp, color = c.warning)
        Text(stringResource(R.string.ui_retrying, attempt, max), style = LcTheme.text.micro, color = c.onWarningContainer)
    }
}

/** M-09 "reading aloud" bars. Static when not [playing] or animations are off. */
@Composable
fun ReadingBars(playing: Boolean, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    val t = rememberInfiniteTransition(label = "reading")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    Row(modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(0.5f, 1f, 0.7f).forEachIndexed { i, base ->
            val h = if (playing) (0.3f + 0.7f * ((phase + i * 0.33f) % 1f)) else base
            Box(Modifier.width(3.dp).height(14.dp * h).background(c.accent, RoundedBar))
        }
    }
}

private val RoundedBar = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)

/** Diameter of [ScrollToBottomButton]; the chat's snackbar sits above this slot (P-STABLE L3). */
val ScrollToBottomSize = 40.dp

/** M-10 floating "scroll to bottom" (e2), dot when new content arrived. */
@Composable
fun ScrollToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier, hasNew: Boolean = false) {
    val c = LcTheme.colors
    Box(modifier) {
        Surface(
            onClick = onClick, shape = CircleShape, color = c.surfaceRaised, contentColor = c.iconPrimary, shadowElevation = LcElevation.e2,
            modifier = Modifier.size(ScrollToBottomSize).then(if (c.isDark) Modifier.border(1.dp, c.floatingBorder, CircleShape) else Modifier),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.ui_scroll_bottom), Modifier.size(20.dp)) }
        }
        if (hasNew) Box(Modifier.align(Alignment.TopEnd).size(10.dp).background(c.accent, CircleShape).border(2.dp, c.surface, CircleShape))
    }
}

/** Shared modifier for "pressed" tint-free clickable rows. */
internal fun Modifier.rowClick(onClick: (() -> Unit)?): Modifier = if (onClick == null) this else clickable(onClick = onClick)
