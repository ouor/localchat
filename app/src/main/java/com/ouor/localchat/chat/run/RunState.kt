package com.ouor.localchat.chat.run

import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.browser.ToolStatus
import com.ouor.localchat.policy.ApprovalRequest

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
    /** [tabId] = the tab the call acts on; [status] = what it waits for ([engine] for OTHER_ENGINE). */
    data class RunningTool(val callId: String, val toolName: String, val tabId: Int?, val status: ToolStatus? = null, val engine: String? = null) : RunPhase
    data class Paused(val pause: Pause) : RunPhase
}

sealed interface Pause {
    /** SC-13: a policy asks the user before the call goes on. Answer with [Resolution.ApprovalAnswer]. */
    data class Approval(val id: String, val request: ApprovalRequest) : Pause

    /** SC-14. Answer with [Resolution.StepLimitAnswer]. */
    data class StepLimit(val reached: Int) : Pause

    /** ask_user: the user does [task] in [tabId] and taps Done ([Resolution.UserStepDone]). */
    data class UserStep(val tabId: Int, val task: String) : Pause
}

/** ChatEngine.resolve(sessionId, resolution). */
sealed interface Resolution {
    data class ApprovalAnswer(val id: String, val allow: Boolean) : Resolution
    data class StepLimitAnswer(val continueRun: Boolean) : Resolution
    data object UserStepDone : Resolution
}
