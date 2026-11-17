package com.ouor.localchat.chat.run

import com.ouor.localchat.chat.context.NotePlacement
import com.ouor.localchat.llm.LlmError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FB4 arch M2: StepPolicy's transitions, the table TurnRunner applies. */
class StepPolicyTest {

    private val fresh = StepPolicy()
    private val badRequest = LlmError.BadRequest(400, "Unexpected role 'user' after role 'tool'")

    /** Tools listed, none left on, the reply has called before, NEW mode. */
    private fun StepPolicy.off() = shape(listed = true, toolsLeft = false, newMode = true, hasCalls = true)

    @Test
    fun `request shape`() {
        assertEquals(StepPolicy.Shape(sendTools = true, noCalls = false, callsBlocked = false, note = NotePlacement.NONE),
            fresh.shape(listed = true, toolsLeft = true, newMode = true, hasCalls = true))
        assertEquals(StepPolicy.Shape(true, noCalls = true, callsBlocked = true, note = NotePlacement.LAST_USER), fresh.off())
        // No call yet in this reply: the note waits for an empty reply.
        assertEquals(NotePlacement.NONE, fresh.shape(true, false, true, hasCalls = false).note)
        assertEquals(NotePlacement.LAST_USER, fresh.copy(emptyRetried = true).shape(true, false, true, hasCalls = false).note)
        assertEquals(NotePlacement.SYSTEM_END, fresh.copy(noteInSystem = true).off().note)
        // Two off-only steps block calls with tools still on; CONTINUE never gets the note.
        assertEquals(true, fresh.copy(offOnly = StepPolicy.OFF_ONLY_STEPS).shape(true, toolsLeft = true, newMode = true, hasCalls = true).noCalls)
        assertEquals(StepPolicy.Shape(true, noCalls = true, callsBlocked = false, note = NotePlacement.NONE), fresh.shape(true, false, newMode = false, hasCalls = true))
        // A server that called anyway: no tools at all, no tool_choice.
        assertEquals(StepPolicy.Shape(sendTools = false, noCalls = false, callsBlocked = true, note = NotePlacement.LAST_USER), fresh.copy(noneIgnored = true).off())
        // Nothing listed: nothing to block.
        assertEquals(StepPolicy.Shape(true, false, false, NotePlacement.NONE), fresh.shape(listed = false, toolsLeft = false, newMode = true, hasCalls = true))
    }

    @Test
    fun `each retry is spent once - 400 moves the note, then one empty retry, then the end`() {
        val a = fresh.onFailure(badRequest, fresh.off())!!
        assertEquals(fresh.copy(noteInSystem = true), a)
        assertNull(a.onFailure(badRequest, a.off()))
        val b = a.onFailure(LlmError.EmptyResponse, a.off())!!
        assertEquals(StepPolicy(noteInSystem = true, emptyRetried = true), b)
        assertNull(b.onFailure(LlmError.EmptyResponse, b.off()))
        // A 400 without the note as a user message is just the error.
        assertNull(fresh.onFailure(badRequest, fresh.shape(true, true, true, true)))
        // Other errors never retry here; an empty reply only when calls are blocked.
        assertNull(fresh.onFailure(LlmError.Network, fresh.off()))
        assertNull(fresh.onFailure(LlmError.EmptyResponse, fresh.shape(true, true, true, true)))
    }

    @Test
    fun `calls to a toolless request share the empty-reply retry`() {
        val ignored = fresh.copy(noneIgnored = true)
        val once = ignored.onCallsWithoutTools(ignored.off())!!
        assertEquals(ignored.copy(emptyRetried = true), once)
        assertNull(once.onCallsWithoutTools(once.off()))
        // Never listed any tools: no retry.
        assertNull(fresh.onCallsWithoutTools(fresh.shape(false, false, true, true)))
    }

    @Test
    fun `after calls - off-only steps count up and reset, and an ignored none sticks`() {
        val on = fresh.shape(true, true, true, true)
        assertEquals(1, fresh.afterCalls(allOff = true, on).offOnly)
        assertEquals(0, fresh.copy(offOnly = 1).afterCalls(allOff = false, on).offOnly)
        val blocked = fresh.off()
        assertEquals(true, fresh.afterCalls(allOff = true, blocked).noneIgnored)
        val ignored = fresh.copy(noneIgnored = true)
        assertEquals(true, ignored.afterCalls(false, ignored.off()).noneIgnored)
    }
}
