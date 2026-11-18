package com.ouor.localchat.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.ouor.localchat.R

/** Icon + human label for a tool step (design-system 7). Unknown tools (old chats' calls too) fall back to a wrench + raw name. */
data class ToolVisual(val icon: ImageVector, @StringRes val label: Int?, val agent: Boolean)

fun toolVisual(toolName: String): ToolVisual = when (toolName) {
    "web_search" -> ToolVisual(Icons.Outlined.Search, R.string.ui_tool_web_search, false)
    "read" -> ToolVisual(Icons.Outlined.Article, R.string.ui_tool_read, false)
    "browse" -> ToolVisual(Icons.Outlined.Language, R.string.ui_tool_browse, true)
    "click" -> ToolVisual(Icons.Outlined.AdsClick, R.string.ui_tool_click, true)
    "type" -> ToolVisual(Icons.Outlined.Keyboard, R.string.ui_tool_type, true)
    "execute" -> ToolVisual(Icons.Outlined.Code, R.string.ui_tool_execute, true)
    "request_handoff" -> ToolVisual(Icons.Outlined.FrontHand, R.string.ui_tool_handoff, true)
    else -> ToolVisual(Icons.Outlined.Build, null, false)
}
