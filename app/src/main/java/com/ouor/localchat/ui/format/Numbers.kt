package com.ouor.localchat.ui.format

import com.ouor.localchat.attach.PrepFailure

// Number wording shared by the chat (gauge, context card, attachments) and the provider screens (limit rows), so both say the same.

// FB-6 limit menu: K = 1024, 1M = 1,048,576.
val CONTEXT_PRESETS = listOf(4, 8, 16, 32, 64, 128, 200, 256).map { it * 1024 } + 1_048_576
val OUTPUT_PRESETS = listOf(1, 2, 4, 8, 16, 32, 64, 128).map { it * 1024 }

/** Preset values read as "128K" / "1M"; anything else as the exact number "128,000". */
fun limitLabel(n: Int, presets: List<Int>): String = when {
    n !in presets -> "%,d".format(n)
    n % 1_048_576 == 0 -> "${n / 1_048_576}M"
    else -> "${n / 1024}K"
}

/** 851234 → "851.2k". */
fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, n / 1_000_000f).replace(".0M", "M")
    n >= 1_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1_000f).replace(".0k", "k")
    else -> n.toString()
}

/** The per-file attachment limit, "64MB" in every language. */
val MAX_FILE_SIZE = "${PrepFailure.TOO_LARGE_BYTES / (1024 * 1024)}MB"
