package com.ouor.localchat.chat.run

import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.core.model.ToolStatus

/** A live run of one session (architecture 5.2). Exposed through ChatEngine.runs. */
data class RunState(
    val sessionId: String,
    val assistantId: String,
    val phase: RunPhase,
    val step: Int,
    val maxSteps: Int,
    /** Latest in-memory copy of the reply, overlaid on the DB copy by ui. */
    val live: MessageNode,
)

sealed interface RunPhase {
    data object Requesting : RunPhase
    data object Streaming : RunPhase
    data object Compacting : RunPhase
    data class Retrying(val attempt: Int, val max: Int, val delayMs: Long) : RunPhase
    /** [tabId] = the work tab the call acts on (the panel peeks); [status] = what it waits for ([engine] for OTHER_ENGINE). */
    data class RunningTool(val callId: String, val toolName: String, val tabId: Int?, val status: ToolStatus? = null, val engine: String? = null) : RunPhase
    data class Paused(val pause: Pause) : RunPhase
}

sealed interface Pause {
    /** SC-13. Answer with [Resolution.ApprovalAnswer]. */
    data class Approval(val id: String, val request: ApprovalInfo) : Pause

    /** SC-14. Answer with [Resolution.StepLimitAnswer]. */
    data class StepLimit(val reached: Int) : Pause

    /** B-05. Answer with [Resolution.HandoffReturned]. */
    data class Handoff(val tabId: Int, val reason: String) : Pause

    /** B-09 ③: an AI tool waits while the user controls the tab; released via BrowserEngine.setControl(AI). */
    data class UserControl(val tabId: Int) : Pause
}

/** ChatEngine.resolve(sessionId, resolution). */
sealed interface Resolution {
    data class ApprovalAnswer(val id: String, val allow: Boolean) : Resolution
    data class StepLimitAnswer(val continueRun: Boolean) : Resolution
    data object HandoffReturned : Resolution
}

/**
 * SC-13 contents for ui (which may not import tools). [tabId] and [url] name the tab the action
 * runs in (SE 9); [reason] is null for execute, which always asks.
 */
data class ApprovalInfo(
    val toolName: String,
    val tabId: Int?,
    val url: String?,
    val targetText: String?,
    val code: String? = null,
    val screenshotId: String? = null,
    val reason: IrreversibleAction.Reason? = null,
)
