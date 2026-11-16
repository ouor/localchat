package com.ouor.localchat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Semantic color tokens of design-system.md 1 (1:1 with mockups/tokens.css).
 * Components read these (or the mapped M3 ColorScheme), never raw hex.
 */
@Immutable
data class LcColors(
    val isDark: Boolean,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val surfaceNav: Color,
    val surfaceInverse: Color,
    val onInverse: Color,
    val scrim: Color,
    val statePressed: Color,
    val stateSelected: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val iconPrimary: Color,
    val iconSecondary: Color,
    val border: Color,
    val borderStrong: Color,
    val focusRing: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val agent: Color,
    val onAgent: Color,
    val agentText: Color,
    val agentContainer: Color,
    val onAgentContainer: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val danger: Color,
    val dangerText: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val codeBg: Color,
    val codeHeader: Color,
    val synKeyword: Color,
    val synString: Color,
    val synFn: Color,
    val synComment: Color,
    /** Dark-theme outline of floating surfaces (e2/e3), design-system 5. */
    val floatingBorder: Color,
)

val LightLcColors = LcColors(
    isDark = false,
    surface = Color(0xFFF7F7F7),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEFEFEF),
    surfaceNav = Color(0xFFFFFFFF),
    surfaceInverse = Color(0xFF262626),
    onInverse = Color(0xFFF5F5F5),
    scrim = Color.Black.copy(alpha = 0.40f),
    statePressed = Color.Black.copy(alpha = 0.08f),
    stateSelected = Color.Black.copy(alpha = 0.06f),
    textPrimary = Color(0xFF0A0A0A),
    textSecondary = Color(0xFF525252),
    textTertiary = Color(0xFF6E6E6E),
    textDisabled = Color(0xFFA3A3A3),
    iconPrimary = Color(0xFF1F1F1F),
    iconSecondary = Color(0xFF6E6E6E),
    border = Color(0xFFE5E5E5),
    borderStrong = Color(0xFFD4D4D4),
    focusRing = Color(0xFF2563EB),
    accent = Color(0xFF2563EB),
    onAccent = Color.White,
    accentText = Color(0xFF1D4ED8),
    accentContainer = Color(0xFFEAF0FF),
    onAccentContainer = Color(0xFF1E3A8A),
    agent = Color(0xFF7C3AED),
    onAgent = Color.White,
    agentText = Color(0xFF6D28D9),
    agentContainer = Color(0xFFF2ECFF),
    onAgentContainer = Color(0xFF4C1D95),
    success = Color(0xFF15803D),
    successContainer = Color(0xFFE8F6EC),
    onSuccessContainer = Color(0xFF14532D),
    warning = Color(0xFFB45309),
    warningContainer = Color(0xFFFFF4E0),
    onWarningContainer = Color(0xFF78350F),
    danger = Color(0xFFDC2626),
    dangerText = Color(0xFFB91C1C),
    dangerContainer = Color(0xFFFDECEC),
    onDangerContainer = Color(0xFF7F1D1D),
    codeBg = Color(0xFFFFFFFF),
    codeHeader = Color(0xFFF7F7F7),
    synKeyword = Color(0xFF7C3AED),
    synString = Color(0xFF15803D),
    synFn = Color(0xFF1D4ED8),
    synComment = Color(0xFF8A8A8A),
    floatingBorder = Color.Transparent,
)

val DarkLcColors = LcColors(
    isDark = true,
    surface = Color(0xFF121212),
    surfaceRaised = Color(0xFF1C1C1C),
    surfaceSunken = Color(0xFF262626),
    surfaceNav = Color(0xFF171717),
    surfaceInverse = Color(0xFFE5E5E5),
    onInverse = Color(0xFF171717),
    scrim = Color.Black.copy(alpha = 0.60f),
    statePressed = Color.White.copy(alpha = 0.10f),
    stateSelected = Color.White.copy(alpha = 0.08f),
    textPrimary = Color(0xFFF5F5F5),
    textSecondary = Color(0xFFB5B5B5),
    textTertiary = Color(0xFF8F8F8F),
    textDisabled = Color(0xFF5C5C5C),
    iconPrimary = Color(0xFFEDEDED),
    iconSecondary = Color(0xFF9A9A9A),
    border = Color(0xFF2A2A2A),
    borderStrong = Color(0xFF3A3A3A),
    focusRing = Color(0xFF7FA6FF),
    accent = Color(0xFF2F6BF0),
    onAccent = Color.White,
    accentText = Color(0xFF8EB2FF),
    accentContainer = Color(0xFF1A2647),
    onAccentContainer = Color(0xFFC7D7FF),
    agent = Color(0xFF7C4DF0),
    onAgent = Color.White,
    agentText = Color(0xFFC4B0FF),
    agentContainer = Color(0xFF2A2045),
    onAgentContainer = Color(0xFFE3D8FF),
    success = Color(0xFF4ADE80),
    successContainer = Color(0xFF13301F),
    onSuccessContainer = Color(0xFFBBF7D0),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF3A2A0C),
    onWarningContainer = Color(0xFFFDE68A),
    danger = Color(0xFFF87171),
    dangerText = Color(0xFFFCA5A5),
    dangerContainer = Color(0xFF3B1515),
    onDangerContainer = Color(0xFFFECACA),
    codeBg = Color(0xFF171717),
    codeHeader = Color(0xFF1F1F1F),
    synKeyword = Color(0xFFC4B0FF),
    synString = Color(0xFF86EFAC),
    synFn = Color(0xFF93C5FD),
    synComment = Color(0xFF7A7A7A),
    floatingBorder = Color(0xFF2E2E2E),
)

/** Endpoint dot (design-system 1.2): known providers by name, others hashed into 8 colors. Always shown with the name. */
fun endpointDotColor(name: String): Color {
    val n = name.lowercase()
    return when {
        "deepseek" in n -> Color(0xFF4D6BFE)
        "openai" in n -> Color(0xFF10A37F)
        "ollama" in n || "lm studio" in n || "lmstudio" in n -> Color(0xFFD97706)
        "openrouter" in n -> Color(0xFF64748B)
        else -> EndpointPalette[Math.floorMod(n.hashCode(), EndpointPalette.size)]
    }
}

private val EndpointPalette = listOf(
    Color(0xFF4D6BFE), Color(0xFF10A37F), Color(0xFFD97706), Color(0xFF64748B),
    Color(0xFFDB2777), Color(0xFF0891B2), Color(0xFF65A30D), Color(0xFF9333EA),
)
