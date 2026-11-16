package com.ouor.localchat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ouor.localchat.R
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme

// ---------------- ComposerPanel (.composer) ----------------

enum class ComposerMode { Idle, Running, Voice, DragOver }

/**
 * Input panel (I-01): [attachments] strip → text field (max 6 lines) → controls row
 * [attach · toggles · ⟶ · mic · send/stop]. [toggles] holds three [ToggleChip]s.
 * [voice] replaces field + controls while recording. [enabled] = false for blocked / no provider.
 */
@Composable
fun ComposerPanel(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
    mode: ComposerMode = ComposerMode.Idle,
    canSend: Boolean = text.isNotBlank(),
    enabled: Boolean = true,
    placeholder: String = stringResource(R.string.ui_composer_placeholder),
    dropHint: String = stringResource(R.string.ui_drop_hint),
    attachments: (@Composable RowScope.() -> Unit)? = null,
    voice: (@Composable () -> Unit)? = null,
    toggles: @Composable RowScope.() -> Unit,
) {
    val c = LcTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val drag = mode == ComposerMode.DragOver
    Box(
        modifier.fillMaxWidth()
            .clip(LcRadius.xl)
            .background(if (drag) c.accentContainer else c.surfaceRaised)
            .border(if (drag) 2.dp else if (focused) 1.5.dp else 1.dp, if (drag) c.accent else if (focused) c.borderStrong else c.border, LcRadius.xl),
    ) {
        if (drag) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.FileUpload, null, Modifier.size(24.dp), tint = c.onAccentContainer)
                Text(stringResource(R.string.ui_drop_here), style = LcTheme.text.bodyStrong, color = c.onAccentContainer, modifier = Modifier.padding(top = 6.dp))
                Text(dropHint, style = LcTheme.text.caption, color = c.onAccentContainer.copy(alpha = 0.8f))
            }
            return@Box
        }
        Column(Modifier.padding(6.dp)) {
            if (attachments != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), content = attachments,
                )
            }
            if (mode == ComposerMode.Voice && voice != null) {
                voice()
            } else {
                BasicTextField(
                    value = text, onValueChange = onTextChange, enabled = enabled,
                    textStyle = LcTheme.text.bodyLarge.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.accent), maxLines = 6, interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)) {
                            if (text.isEmpty()) Text(placeholder, style = LcTheme.text.bodyLarge, color = c.textTertiary)
                            inner()
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    LcIconButton(Icons.Rounded.Add, stringResource(R.string.ui_attach), onAttach, variant = IconButtonVariant.Tonal, size = IconButtonSize.Sm, enabled = enabled)
                    toggles()
                    Box(Modifier.weight(1f))
                    LcIconButton(Icons.Outlined.Mic, stringResource(R.string.ui_voice_input), onMic, size = IconButtonSize.Sm, enabled = enabled)
                    if (mode == ComposerMode.Running) {
                        LcIconButton(Icons.Rounded.Stop, stringResource(R.string.ui_stop), onStop, variant = IconButtonVariant.Inverse, size = IconButtonSize.Sm)
                    } else {
                        LcIconButton(Icons.Rounded.ArrowUpward, stringResource(R.string.ui_send), onSend, variant = IconButtonVariant.Filled, size = IconButtonSize.Sm, enabled = enabled && canSend)
                    }
                }
            }
        }
        if (mode == ComposerMode.Running) WorkingBar(Modifier.align(Alignment.TopStart))
    }
}

/** 2dp sweeping accent bar on top of the composer while a reply runs (1.2s loop). */
@Composable
fun WorkingBar(modifier: Modifier = Modifier, color: Color = LcTheme.colors.accent) {
    val t = rememberInfiniteTransition(label = "working")
    val x by t.animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Box(
        modifier.fillMaxWidth().height(2.dp).drawBehind {
            val w = size.width * 0.4f
            val left = size.width * x
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent), startX = left, endX = left + w), Offset(left, 0f), Size(w, size.height))
        },
    )
}

/** Row under the composer: model chip (start, shrinks first) · gauge (end, never shrinks). */
@Composable
fun ComposerRail(modifier: Modifier = Modifier, start: @Composable RowScope.() -> Unit, end: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = start)
        end()
    }
}

// ---------------- AttachmentTile (.att) ----------------

enum class TileKind { Image, Document, Code }
enum class TileState { Preparing, Failed, Ready, Missing }

/**
 * 56dp tile. [thumbnail] is any Coil model (Uri/File) for images. Preparing shows a progress ring + %.
 * Failed: tap = [onRetry]. Missing = imported data without the original (dimmed).
 */
@Composable
fun AttachmentTile(
    name: String,
    kind: TileKind,
    state: TileState,
    modifier: Modifier = Modifier,
    thumbnail: Any? = null,
    progress: Int? = null,
    size: Dp = 56.dp,
    onRemove: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = LcTheme.colors
    val failed = state == TileState.Failed
    val desc = stringResource(R.string.ui_attachment_desc, name)
    Box(modifier.size(size + 6.dp).semantics { contentDescription = desc }) {
        Box(
            Modifier.align(Alignment.BottomStart).size(size).clip(LcRadius.md)
                .background(if (failed) c.dangerContainer else c.surfaceSunken)
                .then(if (failed) Modifier.border(1.dp, c.danger, LcRadius.md) else Modifier)
                .alpha(if (state == TileState.Missing) 0.55f else 1f)
                .clickable(enabled = (failed && onRetry != null) || onClick != null) { if (failed) onRetry?.invoke() else onClick?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            when {
                failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp), tint = c.onDangerContainer)
                    Text(stringResource(R.string.ui_retry), fontSize = 9.sp, color = c.onDangerContainer)
                }
                kind == TileKind.Image && thumbnail != null ->
                    AsyncImage(thumbnail, null, Modifier.size(size), contentScale = ContentScale.Crop)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(if (kind == TileKind.Code) Icons.Outlined.Code else Icons.Outlined.Description, null, Modifier.size(18.dp), tint = c.textSecondary)
                    Text(name, fontSize = 9.sp, lineHeight = 11.sp, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(size - 6.dp), textAlign = TextAlign.Center)
                }
            }
            if (state == TileState.Preparing) {
                val p = (progress ?: 0) / 100f
                Box(
                    Modifier.size(size).drawBehind {
                        val sw = 2.dp.toPx()
                        drawRoundRect(c.borderStrong, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()), style = Stroke(sw), topLeft = Offset(sw / 2, sw / 2), size = Size(size.toPx() - sw, size.toPx() - sw))
                        drawArc(c.accent, -90f, 360f * p, false, style = Stroke(sw), topLeft = Offset(sw, sw), size = Size(this.size.width - 2 * sw, this.size.height - 2 * sw))
                    },
                )
                if (progress != null) Text("$progress%", Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 3.dp), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = c.accentText)
            }
        }
        if (onRemove != null) {
            Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(c.surfaceRaised).padding(1.dp)
                    .clip(CircleShape).background(c.textPrimary).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, stringResource(R.string.ui_remove_attachment, name), Modifier.size(14.dp), tint = c.surfaceRaised) }
        }
    }
}

// ---------------- FileCard (.filecard) ----------------

/** File inside a message or a PDF artifact (B-08). [ext] is the short type label ("PDF"). */
@Composable
fun FileCard(name: String, meta: String, ext: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = LcTheme.colors
    Row(
        modifier.clip(LcRadius.md).background(c.surfaceRaised).border(1.dp, c.border, LcRadius.md).rowClick(onClick)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).background(c.dangerContainer, LcRadius.sm), contentAlignment = Alignment.Center) {
            if (ext.isBlank()) Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(18.dp), tint = c.dangerText)
            else Text(ext.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.dangerText)
        }
        Column {
            Text(name, style = LcTheme.text.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = LcTheme.text.caption, color = c.textTertiary)
        }
    }
}

// ---------------- VoiceRecorderRow (.voice) ----------------

/** I-08: rec dot · timer · RMS spectrum, then [cancel] ... status · done. [levels] are 0..1. */
@Composable
fun VoiceRecorderRow(elapsed: String, levels: List<Float>, status: String, onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, error: String? = null) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).background(c.danger, CircleShape))
            Text(elapsed, style = LcTheme.text.code.copy(fontWeight = FontWeight.Medium), color = c.textPrimary)
            Row(Modifier.weight(1f).height(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                levels.forEach { l -> Box(Modifier.weight(1f).height((24 * l.coerceIn(0.12f, 1f)).dp).background(c.accent, RoundedCornerShape2)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onCancel) { Text(stringResource(R.string.ui_cancel), style = LcTheme.text.body, color = c.textSecondary) }
            Box(Modifier.weight(1f))
            Text(error ?: status, style = LcTheme.text.caption, color = if (error != null) c.dangerText else c.textTertiary)
            Spacer(Modifier.width(8.dp))
            LcIconButton(Icons.Rounded.Check, stringResource(R.string.ui_done), onDone, variant = IconButtonVariant.Filled, size = IconButtonSize.Sm)
        }
    }
}

private val RoundedCornerShape2 = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)

