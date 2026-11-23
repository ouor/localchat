package com.ouor.localchat.browser

/**
 * What a running tool is waiting for, shown in its step line (tools-redesign 2-7). In layer 1 because
 * [BrowserTab.awaitReady] reports it; tools pass it on (ToolContext) and the app shows it.
 */
enum class ToolStatus { OPENING, SETTLING, RETRYING, OTHER_ENGINE, PAGE_DIALOG }
