package com.ouor.localchat.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.ouor.localchat.R

/** Icon + human label for a tool step (design-system 7). Unknown tools (old chats' calls too) fall back to a wrench + raw name. */
data class ToolVisual(val icon: ImageVector, @StringRes val label: Int?, val agent: Boolean)

fun toolVisual(toolName: String): ToolVisual = when (toolName) {
    "web_search" -> ToolVisual(Icons.Outlined.Search, R.string.ui_tool_web_search, false)
    "open" -> ToolVisual(Icons.Outlined.Language, R.string.tool_open, true)
    "navigate" -> ToolVisual(Icons.Outlined.History, R.string.tool_navigate, true)
    "read" -> ToolVisual(Icons.Outlined.Article, R.string.ui_tool_read, false)
    "look" -> ToolVisual(Icons.Outlined.Visibility, R.string.tool_look, true)
    "click" -> ToolVisual(Icons.Outlined.AdsClick, R.string.ui_tool_click, true)
    "type" -> ToolVisual(Icons.Outlined.Keyboard, R.string.ui_tool_type, true)
    "scroll" -> ToolVisual(Icons.Outlined.SwapVert, R.string.tool_scroll, true)
    "execute" -> ToolVisual(Icons.Outlined.Code, R.string.ui_tool_execute, true)
    "tabs" -> ToolVisual(Icons.Outlined.Tab, R.string.tool_tabs, true)
    "close_tab" -> ToolVisual(Icons.Outlined.Close, R.string.tool_close_tab, true)
    "ask_user" -> ToolVisual(Icons.Outlined.FrontHand, R.string.tool_ask_user, true)
    else -> ToolVisual(Icons.Outlined.Build, null, false)
}
