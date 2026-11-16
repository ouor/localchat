package com.ouor.localchat.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- Typography (design-system 2) ----------

private fun style(size: Int, line: Int, weight: Int, family: FontFamily = FontFamily.Default) = TextStyle(
    fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight),
)

/** Extra text tokens with no M3 slot. */
@Immutable
data class LcTextStyles(
    val display: TextStyle = style(26, 34, 600),
    val titleLarge: TextStyle = style(20, 28, 600),
    val title: TextStyle = style(17, 24, 600),
    val titleSmall: TextStyle = style(15, 22, 600),
    val bodyLarge: TextStyle = style(16, 26, 400),
    val body: TextStyle = style(14, 20, 400),
    val bodyStrong: TextStyle = style(14, 20, 500),
    val label: TextStyle = style(13, 18, 500),
    val caption: TextStyle = style(12, 16, 400),
    val micro: TextStyle = style(11, 14, 600),
    // shortcut: platform monospace instead of bundled JetBrains Mono (design-system 2 allows Roboto Mono fallback).
    val code: TextStyle = style(13, 20, 400, FontFamily.Monospace),
    val mdH1: TextStyle = style(22, 30, 700),
    val mdH2: TextStyle = style(19, 28, 700),
    val mdH3: TextStyle = style(17, 26, 600),
)

private fun LcTextStyles.toM3() = Typography(
    headlineMedium = display,
    titleLarge = titleLarge,
    titleMedium = title,
    titleSmall = titleSmall,
    bodyLarge = bodyLarge,
    bodyMedium = body,
    bodySmall = caption,
    labelLarge = bodyStrong,
    labelMedium = label,
    labelSmall = micro,
)

// ---------- Space, radius, motion (design-system 3, 4, 6) ----------

object LcSpace {
    val xxs = 2.dp; val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp
    val xl = 20.dp; val xxl = 24.dp; val x3 = 32.dp; val x4 = 40.dp
    val gutter = 16.dp
    val gutterWide = 24.dp
    val messageGap = 16.dp
    val blockGap = 10.dp
    val chatMaxWidth = 720.dp
    val touch = 48.dp
}

object LcRadius {
    val xs = RoundedCornerShape(6.dp)
    val sm = RoundedCornerShape(8.dp)
    val md = RoundedCornerShape(12.dp)
    val lg = RoundedCornerShape(16.dp)
    val xl = RoundedCornerShape(24.dp)
    val dialog = RoundedCornerShape(28.dp)
    val full = CircleShape
    val sheetTop = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    /** User bubble: tail corner (bottom end) is 6dp. */
    val bubble = RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp)
}

object LcMotion {
    const val FAST = 120
    const val BASE = 200
    const val SLOW = 300
    const val SHIMMER = 1600
    const val WORKING_BAR = 1200
    const val STREAM_THROTTLE_MS = 100L
    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/** Shadow approximations of design-system 5 (dark adds [LcColors.floatingBorder]). */
object LcElevation {
    val e2 = 3.dp
    val e3 = 8.dp
}

// ---------- ColorScheme mapping (design-system 8) ----------

private fun LcColors.toScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accentContainer, onPrimaryContainer = onAccentContainer,
        // secondary mirrors primary so stock components never pick an off-palette color.
        secondary = accent, onSecondary = onAccent, secondaryContainer = accentContainer, onSecondaryContainer = onAccentContainer,
        tertiary = agent, onTertiary = onAgent, tertiaryContainer = agentContainer, onTertiaryContainer = onAgentContainer,
        background = surface, onBackground = textPrimary, surface = surface, onSurface = textPrimary,
        surfaceVariant = surfaceSunken, onSurfaceVariant = textSecondary,
        surfaceContainerLowest = surfaceRaised,
        surfaceContainerLow = if (isDark) surfaceRaised else surfaceNav,
        surfaceContainer = surfaceRaised,
        surfaceContainerHigh = surfaceSunken,
        surfaceContainerHighest = surfaceSunken,
        surfaceTint = surface,
        outline = borderStrong, outlineVariant = border,
        error = danger, onError = Color.White, errorContainer = dangerContainer, onErrorContainer = onDangerContainer,
        inverseSurface = surfaceInverse, inverseOnSurface = onInverse, inversePrimary = accentText,
        scrim = scrim,
    )
}

// ---------- Theme ----------

private val LocalLcColors = staticCompositionLocalOf { LightLcColors }
private val LocalLcText = staticCompositionLocalOf { LcTextStyles() }

/** Accessors for the extended tokens: `LcTheme.colors.agent`, `LcTheme.text.code`. */
object LcTheme {
    val colors: LcColors @Composable @ReadOnlyComposable get() = LocalLcColors.current
    val text: LcTextStyles @Composable @ReadOnlyComposable get() = LocalLcText.current
}

private val LcShapes = Shapes(
    extraSmall = LcRadius.xs, small = LcRadius.sm, medium = LcRadius.md, large = LcRadius.lg, extraLarge = LcRadius.xl,
)

/** App theme. Dynamic color is always off (G-02). */
@Composable
fun LocalchatTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkLcColors else LightLcColors
    val text = LcTextStyles()
    CompositionLocalProvider(LocalLcColors provides colors, LocalLcText provides text) {
        MaterialTheme(colorScheme = colors.toScheme(), typography = text.toM3(), shapes = LcShapes, content = content)
    }
}
