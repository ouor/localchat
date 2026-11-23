package com.ouor.localchat.chat

import com.ouor.localchat.R

/** The app's words for what the browser tools do; the layers carry names, not string resources (browser-modules 3-5). */
object ToolLabels {
    /** A step line's tool name; null for a tool the app has no words for. */
    fun of(tool: String): Int? = when (tool) {
        "web_search" -> R.string.tool_web_search
        "open" -> R.string.tool_open
        "navigate" -> R.string.tool_navigate
        "read" -> R.string.tool_read
        "look" -> R.string.tool_look
        "click" -> R.string.tool_click
        "type" -> R.string.tool_type
        "scroll" -> R.string.tool_scroll
        "execute" -> R.string.tool_execute
        "tabs" -> R.string.tool_tabs
        "close_tab" -> R.string.tool_close_tab
        "ask_user" -> R.string.tool_ask_user
        else -> null
    }

    /** The drawer's label on a Highlight, by its action. */
    fun highlight(action: String): Int = when (action) {
        "type" -> R.string.browser_highlight_type
        "select" -> R.string.browser_highlight_select
        else -> R.string.browser_highlight_click
    }
}
