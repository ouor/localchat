package com.ouor.localchat.core.model

/**
 * What a running tool is waiting for, shown in its step line (tools-redesign 2-7). In core because tools set it
 * (ToolContext) and ui reads it (RunPhase), and neither may import the other.
 */
enum class ToolStatus { OPENING, SETTLING, RETRYING, OTHER_ENGINE, PAGE_DIALOG }
