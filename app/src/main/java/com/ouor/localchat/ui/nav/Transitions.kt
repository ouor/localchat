package com.ouor.localchat.ui.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.ouor.localchat.ui.components.reducedMotion

/** FB2-3: how a back-stack change moves. Replace = the whole stack swapped (new chat, session from the drawer). */
internal enum class NavMove { Forward, Back, Replace }

/** Offsets are signed toward the layout end edge (+ = end side); all zero = instant. */
internal data class NavMotionSpec(
    val enterFromDp: Int, val exitToDp: Int, val slideMs: Int,
    val fadeOutMs: Int, val fadeInDelayMs: Int, val fadeInMs: Int,
)

internal val INSTANT = NavMotionSpec(0, 0, 0, 0, 0, 0)

/** Material shared axis X (300ms, 30dp) for push/pop; a 150ms fade through for same-level chat swaps. */
internal fun navMotionSpec(move: NavMove, reduced: Boolean): NavMotionSpec = when {
    reduced -> INSTANT
    move == NavMove.Forward -> NavMotionSpec(30, -30, 300, 90, 90, 210)
    move == NavMove.Back -> NavMotionSpec(-30, 30, 300, 90, 90, 210)
    else -> NavMotionSpec(0, 0, 0, 60, 60, 90)
}

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

internal fun NavMotionSpec.toTransform(dpPx: Float, rtl: Boolean): ContentTransform {
    if (this == INSTANT) return ContentTransform(EnterTransition.None, ExitTransition.None)
    val sign = if (rtl) -1 else 1
    var enter = fadeIn(tween(fadeInMs, fadeInDelayMs, LinearEasing))
    var exit = fadeOut(tween(fadeOutMs, easing = LinearEasing))
    if (slideMs > 0) {
        enter += slideInHorizontally(tween(slideMs, easing = EmphasizedDecelerate)) { (sign * enterFromDp * dpPx).toInt() }
        exit += slideOutHorizontally(tween(slideMs, easing = EmphasizedAccelerate)) { (sign * exitToDp * dpPx).toInt() }
    }
    return enter togetherWith exit
}

/** The app's [NavDisplay] with FB2-3 transitions instead of Nav3's default scale + fade. */
@Composable
fun <T : Any> LcNavDisplay(
    backStack: List<T>,
    onBack: () -> Unit,
    entryDecorators: List<NavEntryDecorator<T>>,
    modifier: Modifier = Modifier,
    entryProvider: (T) -> NavEntry<T>,
) {
    val reduced = reducedMotion()
    val dpPx = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun t(move: NavMove) = navMotionSpec(move, reduced).toTransform(dpPx, rtl)
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = onBack,
        entryDecorators = entryDecorators,
        // A push onto an empty history is a stack swap (showChat / openSession), not a deeper level.
        transitionSpec = { t(if (targetState.previousEntries.isEmpty()) NavMove.Replace else NavMove.Forward) },
        popTransitionSpec = { t(NavMove.Back) },
        // Seeked by the gesture: the same axis as pop, no scale.
        predictivePopTransitionSpec = { t(NavMove.Back) },
        entryProvider = entryProvider,
    )
}
