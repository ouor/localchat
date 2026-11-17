package com.ouor.localchat.chat.run

import com.ouor.localchat.chat.context.NotePlacement
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.llm.LlmError

/**
 * FB4 arch M2: the flags that shape a run's requests once tools are off, and their one-time retries, as one value
 * with pure transitions. TurnRunner only applies what these return.
 *
 * - Audit #10 / FB3 QA M1: with no tool left on, or after [OFF_ONLY_STEPS] steps that called only turned-off ones,
 *   the request keeps the tool list but allows no call. A server that calls anyway ([noneIgnored]) gets no tools next.
 * - FB3 D1: while no call is possible, a run that has used tools asks for the answer in a hidden note; an empty
 *   reply is asked once more with the note ([emptyRetried]).
 * - FB4 E-1: a 400 to the note as a user message moves it to the system message for the rest of the run ([noteInSystem]).
 */
internal data class StepPolicy(
    val offOnly: Int = 0,
    val noneIgnored: Boolean = false,
    val emptyRetried: Boolean = false,
    val noteInSystem: Boolean = false,
    /** FB4 step limit: the user chose to stop at the limit; TurnRunner has turned every tool off for one last answer. */
    val wrapUp: Boolean = false,
) {
    /** One request's form: [sendTools] false = no tools at all; [callsBlocked] = this NEW-mode step cannot call. */
    data class Shape(
        val sendTools: Boolean,
        val noCalls: Boolean,
        val callsBlocked: Boolean,
        val note: NotePlacement,
        val noteText: String = SystemPrompt.TOOLS_OFF_NOTE,
    )

    /** [listed]: the run lists tools; [toolsLeft]: some are still on; [hasCalls]: the reply has called before. */
    fun shape(listed: Boolean, toolsLeft: Boolean, newMode: Boolean, hasCalls: Boolean): Shape {
        val noCalls = !noneIgnored && listed && (!toolsLeft || offOnly >= OFF_ONLY_STEPS)
        val callsBlocked = newMode && (noCalls || noneIgnored)
        val note = when {
            !callsBlocked || !(emptyRetried || hasCalls) -> NotePlacement.NONE
            noteInSystem -> NotePlacement.SYSTEM_END
            else -> NotePlacement.LAST_USER
        }
        val text = if (wrapUp) SystemPrompt.STEP_LIMIT_NOTE else SystemPrompt.TOOLS_OFF_NOTE
        return Shape(sendTools = !noneIgnored, noCalls = noCalls, callsBlocked = callsBlocked, note = note, noteText = text)
    }

    /** A failed request: the policy to send the same step again with, or null to end with the error. */
    fun onFailure(error: LlmError, shape: Shape): StepPolicy? = when {
        // Any 400: the wording differs by server (Mistral, HF chat templates, jinja errors), and a wrong guess costs one request.
        error is LlmError.BadRequest && shape.note == NotePlacement.LAST_USER -> copy(noteInSystem = true)
        error == LlmError.EmptyResponse && shape.callsBlocked && !emptyRetried -> copy(emptyRetried = true)
        else -> null
    }

    /** Calls and no answer text to a request that sent no tools: asked once more like an empty reply, or null to end. */
    fun onCallsWithoutTools(shape: Shape): StepPolicy? = if (shape.callsBlocked && !emptyRetried) copy(emptyRetried = true) else null

    /** After a step's calls ran; [allOff]: every call was to a turned-off tool. */
    fun afterCalls(allOff: Boolean, shape: Shape) =
        copy(offOnly = if (allOff) offOnly + 1 else 0, noneIgnored = noneIgnored || shape.noCalls)

    companion object {
        /** FB3 QA M1: steps in a row that called only turned-off tools before the request allows no call. */
        const val OFF_ONLY_STEPS = 2
    }
}
