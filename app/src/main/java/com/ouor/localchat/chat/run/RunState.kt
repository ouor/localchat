package com.ouor.localchat.chat.run

import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.tools.ApprovalRequest

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
    data class RunningTool(val callId: String, val toolName: String, val tabId: Int?) : RunPhase
    data class Paused(val pause: Pause) : RunPhase
}

sealed interface Pause {
    /** SC-13. Answer with [Resolution.ApprovalAnswer]. */
    data class Approval(val id: String, val request: ApprovalRequest) : Pause

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
