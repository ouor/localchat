package com.ouor.localchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ouor.localchat.ui.theme.LcColors
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme

// ---------------- LcIconButton (.ib) ----------------

enum class IconButtonVariant { Ghost, Secondary, Tonal, Raised, Filled, Inverse, Agent }
enum class IconButtonSize(val visual: Dp, val icon: Dp) { Md(40.dp, 20.dp), Sm(32.dp, 18.dp), Xs(28.dp, 16.dp) }

/** Visual 28–40dp, touch target always 48dp (M3 IconButton applies minimumInteractiveComponentSize). */
@Composable
fun LcIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: IconButtonVariant = IconButtonVariant.Ghost,
    size: IconButtonSize = IconButtonSize.Md,
    enabled: Boolean = true,
    badge: Boolean = false,
) {
    val c = LcTheme.colors
    val (container, content) = when (variant) {
        IconButtonVariant.Ghost -> Color.Transparent to c.iconPrimary
        IconButtonVariant.Secondary -> Color.Transparent to c.iconSecondary
        IconButtonVariant.Tonal -> c.surfaceSunken to c.iconPrimary
        IconButtonVariant.Raised -> c.surfaceRaised to c.iconPrimary
        IconButtonVariant.Filled -> c.accent to c.onAccent
        IconButtonVariant.Inverse -> c.textPrimary to c.surfaceRaised
        IconButtonVariant.Agent -> c.agent to c.onAgent
    }
    val filledLike = variant == IconButtonVariant.Filled || variant == IconButtonVariant.Inverse
    Box(modifier, contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(size.visual)
                .then(if (variant == IconButtonVariant.Raised) Modifier.border(1.dp, c.border, CircleShape) else Modifier),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = container,
                contentColor = content,
                disabledContainerColor = if (filledLike || variant == IconButtonVariant.Tonal) c.surfaceSunken else Color.Transparent,
                disabledContentColor = c.textDisabled,
            ),
        ) { Icon(icon, contentDescription, Modifier.size(size.icon)) }
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(10.dp)
                    .background(c.surface, CircleShape).padding(1.dp).background(c.agent, CircleShape),
            )
        }
    }
}

// ---------------- LcButton (.btn) ----------------

enum class ButtonVariant { Primary, Tonal, Outline, Text, Danger, Agent }
enum class ButtonSize(val height: Dp, val hPad: Dp) { Sm(32.dp, 12.dp), Md(40.dp, 16.dp), Lg(48.dp, 20.dp) }

@Composable
fun LcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Md,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = LcTheme.colors
    val (container, content) = when (variant) {
        ButtonVariant.Primary -> c.accent to c.onAccent
        ButtonVariant.Tonal -> c.surfaceSunken to c.textPrimary
        ButtonVariant.Outline -> c.surfaceRaised to c.textPrimary
        ButtonVariant.Text -> Color.Transparent to c.accentText
        ButtonVariant.Danger -> c.danger to Color.White
        ButtonVariant.Agent -> c.agent to c.onAgent
    }
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(size.height),
        shape = LcRadius.full,
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = if (variant == ButtonVariant.Text) Color.Transparent else c.surfaceSunken,
            disabledContentColor = if (loading) content else c.textDisabled,
        ),
        border = if (variant == ButtonVariant.Outline) BorderStroke(1.dp, c.borderStrong) else null,
        contentPadding = PaddingValues(horizontal = if (variant == ButtonVariant.Text) 8.dp else size.hPad),
        elevation = null,
    ) {
        if (loading) {
            LcSpinner(size = 16.dp, color = content)
            Spacer(Modifier.size(6.dp))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, style = if (size == ButtonSize.Sm) LcTheme.text.label else LcTheme.text.bodyStrong, maxLines = 1)
    }
}

// ---------------- LcChip / ToggleChip shared surface (.chip) ----------------

/** Shared chip container: 32dp (touch 48 via Surface), full radius, label 13/18. */
@Composable
internal fun ChipSurface(
    onClick: (() -> Unit)?,
    container: Color,
    content: Color,
    border: Color?,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    horizontalPadding: PaddingValues = PaddingValues(horizontal = 10.dp),
    enabled: Boolean = true,
    body: @Composable RowScope.() -> Unit,
) {
    val row: @Composable () -> Unit = {
        Row(
            Modifier.height(height).padding(horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = body,
        )
    }
    val b = border?.let { BorderStroke(1.dp, it) }
    if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = LcRadius.full, color = container,
            contentColor = content, border = b, content = row)
    } else {
        Surface(modifier = modifier, shape = LcRadius.full, color = container, contentColor = content, border = b, content = row)
    }
}

enum class ChipVariant { Outline, Tonal, Suggest, Preset }

/** Generic chip: suggestions, presets (selectable), tonal tags. */
@Composable
fun LcChip(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    variant: ChipVariant = ChipVariant.Outline,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val c = LcTheme.colors
    val (container, content, border) = when {
        !enabled -> Triple(Color.Transparent, c.textDisabled, c.border)
        variant == ChipVariant.Preset && selected -> Triple(c.accentContainer, c.onAccentContainer, c.accent.copy(alpha = 0.35f))
        variant == ChipVariant.Tonal -> Triple(c.surfaceSunken, c.textPrimary, null)
        variant == ChipVariant.Suggest -> Triple(c.surfaceRaised, c.textPrimary, c.border)
        else -> Triple(c.surfaceRaised, c.textPrimary, c.borderStrong)
    }
    val suggest = variant == ChipVariant.Suggest
    ChipSurface(
        onClick, container, content, border, modifier, enabled = enabled,
        height = if (suggest) 36.dp else 32.dp,
        horizontalPadding = PaddingValues(horizontal = if (suggest) 14.dp else 10.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp))
        Text(text, style = if (suggest) LcTheme.text.body else LcTheme.text.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- LcBadge (.badge) ----------------

enum class BadgeVariant { Neutral, Ai, AiSoft, Me, Ok, Warn, Err, Accent }

@Composable
fun LcBadge(text: String, modifier: Modifier = Modifier, variant: BadgeVariant = BadgeVariant.Neutral, icon: ImageVector? = null) {
    val (bg, fg) = badgeColors(LcTheme.colors, variant)
    Row(
        modifier.height(20.dp).background(bg, LcRadius.full).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(12.dp), tint = fg)
        Text(text, style = LcTheme.text.micro, color = fg, maxLines = 1)
    }
}

private fun badgeColors(c: LcColors, v: BadgeVariant): Pair<Color, Color> = when (v) {
    BadgeVariant.Neutral -> c.surfaceSunken to c.textSecondary
    BadgeVariant.Ai -> c.agent to c.onAgent
    BadgeVariant.AiSoft -> c.agentContainer to c.onAgentContainer
    BadgeVariant.Me -> c.surfaceSunken to c.textPrimary
    BadgeVariant.Ok -> c.successContainer to c.onSuccessContainer
    BadgeVariant.Warn -> c.warningContainer to c.onWarningContainer
    BadgeVariant.Err -> c.dangerContainer to c.onDangerContainer
    BadgeVariant.Accent -> c.accentContainer to c.onAccentContainer
}

/** 8dp endpoint dot; always shown next to the endpoint name (never color alone). */
@Composable
fun EndpointDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

// ---------------- SectionLabel / DividerLabel ----------------

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
        style = LcTheme.text.label, color = LcTheme.colors.textTertiary,
    )
}

/** "— 이전 대화 요약됨 —" (A-06). */
@Composable
fun DividerLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = LcTheme.colors.border)
        Text(text, style = LcTheme.text.caption, color = LcTheme.colors.textTertiary)
        HorizontalDivider(Modifier.weight(1f), color = LcTheme.colors.border)
    }
}
