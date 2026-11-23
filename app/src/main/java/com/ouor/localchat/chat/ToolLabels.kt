package com.ouor.localchat.chat

import com.ouor.localchat.R

/** The app's words for what the browser tools do; the layers carry names, not string resources (browser-modules 3-5). */
object ToolLabels {
    /** The drawer's label on a Highlight, by its action. */
    fun highlight(action: String): Int = when (action) {
        "type" -> R.string.browser_highlight_type
        "select" -> R.string.browser_highlight_select
        else -> R.string.browser_highlight_click
    }
}
