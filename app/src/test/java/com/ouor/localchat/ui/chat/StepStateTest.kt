package com.ouor.localchat.ui.chat

import com.ouor.localchat.R
import com.ouor.localchat.chat.ToolStep
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.ui.components.StepState
import org.junit.Assert.assertEquals
import org.junit.Test

class StepStateTest {

    private val call = ToolCallBlock("c", "click", "{}", 0, ToolCallState.FAILED, 0)
    private val result = ToolResultBlock("c", 0, "x", isError = true)

    @Test fun `a turned-off tool is skipped, not failed`() {
        // FB3 QA m2: it showed in the danger colour as "failed with an error".
        assertEquals(StepState.Skipped, stepState(call, result, ToolStep(0, "", failureRes = R.string.chat_step_turned_off, turnedOff = true)))
        assertEquals(StepState.Failed, stepState(call, result, ToolStep(0, "", failureRes = R.string.chat_step_fail_error)))
        assertEquals(StepState.Failed, stepState(call, result, null))
    }
}
