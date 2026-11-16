package com.ouor.localchat.ui.components

/** Pure display rules of the design system (JVM-tested in ComponentRulesTest). */

enum class GaugeLevel { Normal, Warn, High }

/** A-07 percent of [window] used, rounded up: with 1M-token windows a real chat is well under 1% and must not read 0%. */
fun gaugePercent(usedTokens: Int?, window: Int?): Int? =
    if (usedTokens == null || window == null || window <= 0) null
    else ((usedTokens * 100L + window - 1) / window).toInt().coerceIn(0, 100)

/** A-07: <60 normal, 60–80 warn, >80 high. */
fun gaugeLevel(percent: Int?): GaugeLevel = when {
    percent == null || percent < 60 -> GaugeLevel.Normal
    percent <= 80 -> GaugeLevel.Warn
    else -> GaugeLevel.High
}

fun gaugeText(percent: Int?, estimate: Boolean, tokensText: String?): String =
    if (percent == null) tokensText.orEmpty() else (if (estimate) "~" else "") + "$percent%"

/** Reasoning toggle shows the chosen server level ("high"); longer than 6 chars falls back to the generic label. */
fun reasoningChipLabel(level: String?, generic: String): String =
    if (level.isNullOrBlank() || level.length > 6) generic else level

/** SegmentedControl only for 2–5 options with labels of at most 6 chars; otherwise a radio menu. */
fun segmentedFits(labels: List<String>): Boolean = labels.size in 2..5 && labels.all { it.length <= 6 }
