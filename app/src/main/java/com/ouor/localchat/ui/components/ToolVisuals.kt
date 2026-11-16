package com.ouor.localchat.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector
import com.ouor.localchat.R

/** Icon + human label for a tool step (design-system 7). Unknown tools fall back to a wrench + raw name. */
data class ToolVisual(val icon: ImageVector, @StringRes val label: Int?, val agent: Boolean)

fun toolVisual(toolName: String): ToolVisual = when (toolName) {
    "web_search" -> ToolVisual(Icons.Outlined.Search, R.string.ui_tool_web_search, false)
    "open_tab" -> ToolVisual(Icons.Outlined.Tab, R.string.ui_tool_open_tab, true)
    "close_tab" -> ToolVisual(Icons.Rounded.Close, R.string.ui_tool_close_tab, true)
    "get_tabs" -> ToolVisual(Icons.Outlined.Tab, R.string.ui_tool_get_tabs, true)
    "navigate" -> ToolVisual(Icons.Rounded.ArrowForward, R.string.ui_tool_navigate, true)
    "snapshot" -> ToolVisual(Icons.Outlined.AccountTree, R.string.ui_tool_snapshot, true)
    "page_text" -> ToolVisual(Icons.Outlined.Article, R.string.ui_tool_page_text, true)
    "screenshot" -> ToolVisual(Icons.Outlined.Screenshot, R.string.ui_tool_screenshot, true)
    "click" -> ToolVisual(Icons.Outlined.AdsClick, R.string.ui_tool_click, true)
    "type" -> ToolVisual(Icons.Outlined.Keyboard, R.string.ui_tool_type, true)
    "keyboard" -> ToolVisual(Icons.Outlined.Keyboard, R.string.ui_tool_keyboard, true)
    "scroll" -> ToolVisual(Icons.Rounded.SwapVert, R.string.ui_tool_scroll, true)
    "hover" -> ToolVisual(Icons.Outlined.TouchApp, R.string.ui_tool_hover, true)
    "select_option" -> ToolVisual(Icons.Rounded.List, R.string.ui_tool_select, true)
    "wait_for_selector", "wait_for_navigation" -> ToolVisual(Icons.Outlined.HourglassEmpty, R.string.ui_tool_wait, true)
    "execute" -> ToolVisual(Icons.Outlined.Code, R.string.ui_tool_execute, true)
    "dialog_handle" -> ToolVisual(Icons.Outlined.ChatBubbleOutline, R.string.ui_tool_dialog, true)
    "console_logs" -> ToolVisual(Icons.Outlined.Terminal, R.string.ui_tool_console, true)
    "print_to_pdf" -> ToolVisual(Icons.Outlined.PictureAsPdf, R.string.ui_tool_pdf, true)
    "request_handoff" -> ToolVisual(Icons.Outlined.FrontHand, R.string.ui_tool_handoff, true)
    else -> ToolVisual(Icons.Outlined.Build, null, false)
}

