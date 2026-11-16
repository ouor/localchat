package com.ouor.localchat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ouor.localchat.R
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme

// ---------------- UserMessage (.u-msg + .bubble) ----------------

/** Right-aligned bubble (max 82%), tail corner bottom-end. Long press → copy/edit menu (owned by the screen). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(
    text: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    attachments: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments?.invoke()
        if (text.isNotEmpty()) {
            Box(Modifier.fillMaxWidth(0.82f), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text,
                    style = LcTheme.text.bodyLarge, color = c.textPrimary,
                    modifier = Modifier.clip(LcRadius.bubble).background(c.surfaceRaised).border(1.dp, c.border, LcRadius.bubble)
                        .combinedClickable(enabled = onLongClick != null, onClick = {}, onLongClick = onLongClick)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        footer?.invoke()
    }
}

/** Inline edit of a user message (M-04) with cancel/save. */
@Composable
fun UserMessageEditor(text: String, onTextChange: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth().clip(LcRadius.lg).background(c.surfaceRaised).border(1.5.dp, c.borderStrong, LcRadius.lg).padding(12.dp)) {
        BasicTextField(text, onTextChange, Modifier.fillMaxWidth(), textStyle = LcTheme.text.bodyLarge.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accent))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            LcButton(stringResource(R.string.ui_cancel), onCancel, variant = ButtonVariant.Text, size = ButtonSize.Sm)
            LcButton(stringResource(R.string.ui_save_send), onSave, size = ButtonSize.Sm, enabled = text.isNotBlank())
        }
    }
}

/** Assistant reply: blocks stacked with 10dp gaps (steps → thinking → markdown → sources → actions / error). */
@Composable
fun AssistantMessage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

// ---------------- StepList / StepItem (.steps / .step) ----------------

enum class StepState { Running, Done, Failed, AwaitingApproval, Denied, Skipped, Stopped }
enum class StepListState { Running, Done, FailedSome, Paused, Stopped }

/** One tool step in human words. [thumbnail] = Coil model of a screenshot (expanded view). */
data class StepItemData(
    val icon: ImageVector,
    val label: String,
    val state: StepState,
    val arg: String? = null,
    val result: String? = null,
    val agent: Boolean = false,
    val thumbnail: Any? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * A-04 step card. Header (status icon · title · caption · chevron); when [expanded] the timeline,
 * otherwise only the live step (running) is shown under the header.
 */
@Composable
fun StepList(
    state: StepListState,
    title: String,
    steps: List<StepItemData>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val c = LcTheme.colors
    val live = steps.lastOrNull()?.takeIf { it.state == StepState.Running || it.state == StepState.AwaitingApproval }
    Column(modifier.fillMaxWidth().clip(LcRadius.lg).background(c.surfaceRaised).border(1.dp, c.border, LcRadius.lg).padding(vertical = 4.dp)) {
        val toggleDesc = stringResource(if (expanded) R.string.ui_collapse else R.string.ui_expand)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).combinedClickable(onClick = onToggle).semantics { role = Role.Button; contentDescription = "$title, $toggleDesc" }
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                StepListState.Running -> LcSpinner(size = 14.dp, color = if (steps.any { it.agent }) c.agent else c.accent)
                StepListState.Done -> Icon(Icons.Rounded.CheckCircleOutline, null, Modifier.size(18.dp), tint = c.iconPrimary)
                StepListState.FailedSome -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = c.iconPrimary)
                StepListState.Paused -> Icon(Icons.Outlined.PauseCircle, null, Modifier.size(18.dp), tint = c.warning)
                StepListState.Stopped -> Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp), tint = c.iconSecondary)
            }
            Text(title, style = LcTheme.text.bodyStrong, color = c.textPrimary)
            if (caption != null) Text(caption, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, Modifier.size(20.dp), tint = c.iconSecondary)
        }
        AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column {
                steps.forEachIndexed { i, s -> StepItem(s, first = i == 0, last = i == steps.lastIndex) }
            }
        }
        if (!expanded && live != null) StepItem(live, first = true, last = true)
    }
}

@Composable
fun StepItem(step: StepItemData, modifier: Modifier = Modifier, first: Boolean = true, last: Boolean = true) {
    val c = LcTheme.colors
    val live = step.state == StepState.Running || step.state == StepState.AwaitingApproval
    val line = c.border
    val rowMod = if (live) {
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().clip(LcRadius.md).background(c.surface)
            .border(1.dp, c.borderStrong, LcRadius.md).padding(horizontal = 6.dp, vertical = 10.dp)
    } else {
        Modifier.fillMaxWidth().drawBehind {
            val x = 24.dp.toPx()
            val top = if (first) 16.dp.toPx() else 0f
            val bottom = if (last) 16.dp.toPx() else size.height
            if (bottom > top) drawLine(line, Offset(x, top), Offset(x, bottom), 1.5.dp.toPx())
        }.padding(horizontal = 14.dp, vertical = 7.dp)
    }
    Row(
        modifier.then(rowMod).then(if (step.onClick != null) Modifier.combinedClickable(onClick = step.onClick) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp).background(if (live) c.surface else c.surfaceRaised, CircleShape), contentAlignment = Alignment.Center) {
            when (step.state) {
                StepState.Running -> LcSpinner(size = 14.dp, color = if (step.agent) c.agent else c.accent)
                StepState.Done -> Icon(step.icon, null, Modifier.size(16.dp), tint = c.success)
                StepState.Failed -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = c.danger)
                StepState.AwaitingApproval -> Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = c.warning)
                StepState.Denied -> Icon(Icons.Rounded.Block, null, Modifier.size(16.dp), tint = c.danger)
                StepState.Skipped -> Icon(Icons.Rounded.SkipNext, null, Modifier.size(16.dp), tint = c.iconSecondary)
                StepState.Stopped -> Icon(Icons.Outlined.StopCircle, null, Modifier.size(16.dp), tint = c.iconSecondary)
            }
        }
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(step.label, style = LcTheme.text.body.copy(fontWeight = if (live) FontWeight.SemiBold else FontWeight.Medium), color = c.textPrimary)
                if (step.arg != null) ArgChip(step.arg)
            }
            if (step.result != null) {
                Text(
                    step.result, style = LcTheme.text.caption, modifier = Modifier.padding(top = 2.dp),
                    color = if (step.state == StepState.Failed || step.state == StepState.Denied) c.dangerText else c.textTertiary,
                )
            }
            if (step.thumbnail != null) {
                AsyncImage(step.thumbnail, null, Modifier.padding(top = 6.dp).size(96.dp, 60.dp).clip(LcRadius.sm).border(1.dp, c.border, LcRadius.sm), contentScale = ContentScale.Crop)
            }
        }
    }
}

/** URL / selector / query argument chip inside a step. */
@Composable
fun ArgChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.widthIn(max = 210.dp).background(LcTheme.colors.surfaceSunken, LcRadius.xs).padding(horizontal = 6.dp),
        style = LcTheme.text.caption.copy(fontWeight = FontWeight.Medium, lineHeight = 18.sp), color = LcTheme.colors.textSecondary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

// ---------------- ThinkingBlock (.think) ----------------

enum class ThinkingState { Thinking, Done, Stopped }

/**
 * M-08 reasoning. Thinking = 3×3 dot grid + shimmer "생각 중" + timer; Done = "n초 동안 생각함" (collapsed by default);
 * expanded shows [text] indented behind a 2dp line.
 */
@Composable
fun ThinkingBlock(
    state: ThinkingState,
    timer: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    body: (@Composable () -> Unit)? = null,
) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.heightIn(min = 36.dp).clip(LcRadius.sm).combinedClickable(onClick = onToggle).padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                ThinkingState.Thinking -> {
                    DotGrid()
                    ShimmerText(stringResource(R.string.ui_thinking))
                    Text(timer, style = LcTheme.text.code.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = c.textTertiary)
                }
                ThinkingState.Done, ThinkingState.Stopped -> {
                    Icon(Icons.Outlined.Lightbulb, null, Modifier.size(18.dp), tint = c.iconSecondary)
                    Text(
                        if (state == ThinkingState.Done) stringResource(R.string.ui_thought_for, timer) else stringResource(R.string.ui_thinking_stopped),
                        style = LcTheme.text.body, color = c.textSecondary,
                    )
                    Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = c.iconSecondary)
                }
            }
        }
        AnimatedVisibility(expanded && text.isNotEmpty(), enter = expandVertically(), exit = shrinkVertically()) {
            Box(
                Modifier.padding(start = 9.dp, top = 2.dp, bottom = 2.dp).drawBehind { drawLine(c.border, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx()) }
                    .padding(start = 14.dp),
            ) {
                if (body != null) body() else Text(text, style = LcTheme.text.body, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun DotGrid() {
    val c = LcTheme.colors
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { col -> Box(Modifier.size(3.dp).background(if ((r * 3 + col) % 2 == 0) c.textPrimary else c.textTertiary, RoundedCornerShape1)) }
            }
        }
    }
}

private val RoundedCornerShape1 = androidx.compose.foundation.shape.RoundedCornerShape(1.dp)

/** 1.6s linear shimmer over text (static when animations are disabled by the system scale). */
@Composable
fun ShimmerText(text: String, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Text(
        text, modifier,
        style = LcTheme.text.body.copy(
            fontWeight = FontWeight.Medium,
            brush = Brush.linearGradient(
                0f to c.textTertiary, 0.5f to c.textPrimary, 1f to c.textTertiary,
                start = Offset(x * 200f - 100f, 0f), end = Offset(x * 200f + 100f, 0f),
            ),
        ),
    )
}

// ---------------- CitationBadge / SourceStack / SourceCard ----------------

/** S-04 `[n]` badge (18dp, xs radius). Pressed/active fills with accent. */
@Composable
fun CitationBadge(n: Int, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val c = LcTheme.colors
    val desc = stringResource(R.string.ui_citation_desc, n)
    Box(
        modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(LcRadius.xs)
            .background(if (active) c.accent else c.surfaceSunken)
            .combinedClickable(onClick = onClick).semantics { contentDescription = desc; role = Role.Button }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = LcTheme.text.micro.copy(lineHeight = 18.sp), color = if (active) c.onAccent else c.textSecondary)
    }
}

/** Site of a source as shown in stacks and cards. [iconUrl] null or failing → initial / globe. */
data class SourceSite(val name: String, val iconUrl: String? = null)

@Composable
private fun SiteLogo(site: SourceSite, ring: Color) {
    val color = com.ouor.localchat.ui.theme.endpointDotColor(site.name)
    Box(Modifier.size(16.dp).background(ring, CircleShape).padding(1.5.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(site.name.take(1).uppercase(), fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (site.iconUrl != null) AsyncImage(site.iconUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** S-04 footer (FB-1d): up to 3 overlapping 16dp logos + "출처 n", one line, 28dp. */
@Composable
fun SourceStack(sites: List<SourceSite>, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    val desc = stringResource(R.string.fb_e1_sources_cd, count)
    Surface(onClick = onClick, modifier = modifier.semantics(mergeDescendants = true) { contentDescription = desc }, shape = LcRadius.full, color = c.surfaceSunken, contentColor = c.textSecondary) {
        Row(Modifier.heightIn(min = 28.dp).padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) { sites.take(3).forEach { SiteLogo(it, c.surfaceSunken) } }
            Text(stringResource(R.string.fb_e1_sources_n, count), style = LcTheme.text.label, maxLines = 1, softWrap = false)
        }
    }
}

/** SC-09 source row: number, logo (globe fallback), title, site, snippet. */
@Composable
fun SourceCard(n: Int, title: String, site: SourceSite, url: String, onClick: () -> Unit, modifier: Modifier = Modifier, snippet: String? = null) {
    val c = LcTheme.colors
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 2.dp).size(28.dp).background(c.surfaceSunken, CircleShape), contentAlignment = Alignment.Center) {
            if (site.iconUrl != null) AsyncImage(site.iconUrl, null, Modifier.size(20.dp).clip(CircleShape))
            else Icon(Icons.Outlined.Language, null, Modifier.size(18.dp), tint = c.iconSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CitationBadge(n, onClick)
                Text(site.name, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(title, style = LcTheme.text.bodyStrong, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (snippet != null) Text(snippet, style = LcTheme.text.caption, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(url, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- MessageActionBar / BranchSwitcher ----------------

/** M-06 `‹ n/m ›`; arrows disable at the ends. */
@Composable
fun BranchSwitcher(index: Int, count: Int, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LcIconButton(Icons.Rounded.ChevronLeft, stringResource(R.string.ui_prev_version), onPrev, variant = IconButtonVariant.Secondary, size = IconButtonSize.Sm, enabled = index > 1)
        Text("$index/$count", style = LcTheme.text.caption.copy(fontWeight = FontWeight.Medium), color = LcTheme.colors.textSecondary)
        LcIconButton(Icons.Rounded.ChevronRight, stringResource(R.string.ui_next_version), onNext, variant = IconButtonVariant.Secondary, size = IconButtonSize.Sm, enabled = index < count)
    }
}

/**
 * M-03~M-11 actions under a reply. Null callbacks hide their item (refusal / imported rules live in the VM).
 * [modelName] long-press opens usage.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageActionBar(
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    onReadAloud: (() -> Unit)? = null,
    reading: Boolean = false,
    onRegenerate: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    branch: (@Composable () -> Unit)? = null,
    modelName: String? = null,
    onModelLongPress: (() -> Unit)? = null,
) {
    val c = LcTheme.colors
    Row(modifier.fillMaxWidth().offset(x = (-6).dp), verticalAlignment = Alignment.CenterVertically) {
        LcIconButton(Icons.Outlined.ContentCopy, stringResource(R.string.ui_copy), onCopy, variant = IconButtonVariant.Secondary, size = IconButtonSize.Sm)
        if (onReadAloud != null) {
            if (reading) {
                val stopDesc = stringResource(R.string.ui_stop_reading)
                Box(Modifier.size(48.dp).combinedClickable(onClick = onReadAloud).semantics { contentDescription = stopDesc }, contentAlignment = Alignment.Center) { ReadingBars(true) }
            } else {
                LcIconButton(Icons.Outlined.VolumeUp, stringResource(R.string.ui_read_aloud), onReadAloud, variant = IconButtonVariant.Secondary, size = IconButtonSize.Sm)
            }
        }
        if (onRegenerate != null) LcIconButton(Icons.Rounded.Refresh, stringResource(R.string.ui_regenerate), onRegenerate, variant = IconButtonVariant.Secondary, size = IconButtonSize.Sm)
        if (onContinue != null) LcIconButton(Icons.Rounded.PlayArrow, stringResource(R.string.ui_continue), onContinue, variant = IconButtonVariant.Secondary, size = IconButtonSize.Sm)
        if (onMore != null) LcIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.ui_more), onMore, variant = IconButtonVariant.Secondary, size = IconButtonSize.Sm)
        branch?.invoke()
        Box(Modifier.weight(1f))
        if (modelName != null) {
            Text(
                modelName, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onModelLongPress).padding(end = 2.dp, start = 4.dp),
            )
        }
    }
}

// ---------------- ErrorCard (.errcard) ----------------

/** Error in the reply slot (E-01~E-04, P-05). Use [ChatErrorCard] for stored [com.ouor.localchat.core.model.ChatError]s. */
@Composable
fun ErrorCard(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth().background(c.dangerContainer, LcRadius.lg).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = c.onDangerContainer)
            Column {
                Text(title, style = LcTheme.text.bodyStrong, color = c.onDangerContainer)
                if (detail != null) Text(detail, style = LcTheme.text.body, color = c.onDangerContainer.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (actionLabel != null && onAction != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LcButton(actionLabel, onAction, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                if (secondaryLabel != null && onSecondary != null) LcButton(secondaryLabel, onSecondary, variant = ButtonVariant.Text, size = ButtonSize.Sm)
            }
        }
    }
}

