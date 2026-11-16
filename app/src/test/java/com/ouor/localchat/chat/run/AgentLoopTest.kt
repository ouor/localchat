package com.ouor.localchat.chat.run

import com.ouor.localchat.browser.Control
import com.ouor.localchat.chat.ChatRejected
import com.ouor.localchat.chat.FakeModelPort
import com.ouor.localchat.chat.FakeTool
import com.ouor.localchat.chat.Harness
import com.ouor.localchat.chat.TEST_MODEL
import com.ouor.localchat.chat.caps
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.llm.LlmError
import com.ouor.localchat.llm.LlmEvent
import com.ouor.localchat.llm.LlmResult
import com.ouor.localchat.llm.ToolCallRequest
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.HandoffRequest
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    private fun draft(text: String) = Draft(Draft.NEW_CHAT, text, emptyList(), 0)

    private fun call(id: String, name: String, args: String = """{"q":"x"}""") =
        LlmEvent.ToolCallsReady(listOf(ToolCallRequest(id, name, args)))

    private fun finished(reason: String = "stop") = LlmEvent.Finished(reason, Usage(10, 5))

    private suspend fun Harness.sendNew(text: String = "hello") =
        engine.send(null, draft(text), GenOptions(), TEST_MODEL)

    private fun Harness.lastReply(sessionId: String): MessageBody.Assistant =
        store.messages.values.last { it.sessionId == sessionId && it.body is MessageBody.Assistant }.body as MessageBody.Assistant

    private fun TestScope.phase(h: Harness, sessionId: String) = h.registry.runs.value[sessionId]?.phase

    @Test
    fun `single step reply is saved DONE, titled and the run ends`() = runTest {
        val h = Harness(this)
        h.llm.events(LlmEvent.ReasoningDelta("think", "reasoning_content"), LlmEvent.TextDelta("Hi "), LlmEvent.TextDelta("there"), finished())
        val sid = h.sendNew("hello world")
        assertEquals(TitleSource.FIRST_PROMPT, h.store.sessions.getValue(sid).titleSource)
        advanceUntilIdle()

        val reply = h.lastReply(sid)
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals("Hi there", reply.blocks.filterIsInstance<TextBlock>().single().text)
        assertEquals(1, reply.steps)
        assertEquals(Usage(10, 5), reply.usage)
        assertTrue(h.registry.runs.value.isEmpty())
        assertEquals("Title", h.store.sessions.getValue(sid).title)
        assertEquals(TitleSource.AUTO, h.store.sessions.getValue(sid).titleSource)
        assertEquals(listOf(Draft.NEW_CHAT), h.store.deletedDrafts)
        assertEquals(listOf(TEST_MODEL), h.models.used)
        // DeepSeek reasoning off by default: must be explicit.
        assertEquals("disabled", h.llm.requests.single().thinking?.type)
    }

    @Test
    fun `multi step loop runs tools and sends results and current reasoning back`() = runTest {
        val search = FakeTool("page_text")
        val h = Harness(this, tools = listOf(search))
        h.llm.events(LlmEvent.ReasoningDelta("plan", "reasoning_content"), call("c1", "page_text"), finished("tool_calls"))
        h.llm.events(call("c2", "page_text"), finished("tool_calls"))
        h.llm.text("done")
        val sid = h.sendNew()
        advanceUntilIdle()

        val reply = h.lastReply(sid)
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals(3, reply.steps)
        assertEquals(2, search.calls)
        assertEquals(listOf(ToolCallState.DONE, ToolCallState.DONE), reply.blocks.filterIsInstance<ToolCallBlock>().map { it.state })
        val second = h.llm.requests[1].messages
        val asst = second.first { it.role == "assistant" }
        assertEquals("plan", asst.reasoningContent)
        assertEquals("c1", asst.toolCalls!!.single().id)
        val tool = second.first { it.role == "tool" }
        assertEquals("c1", tool.toolCallId)
        assertTrue(tool.content!!.jsonPrimitive.content.startsWith("<untrusted_page_content>"))
        assertEquals(3, h.llm.requests.size)
    }

    @Test
    fun `unknown tool and bad arguments become error results`() = runTest {
        val h = Harness(this, tools = listOf(FakeTool("open_tab")))
        h.llm.events(
            LlmEvent.ToolCallsReady(listOf(ToolCallRequest("a", "nope", "{}"), ToolCallRequest("b", "open_tab", "{oops"))),
            finished("tool_calls"),
        )
        h.llm.text("ok")
        val sid = h.sendNew()
        advanceUntilIdle()
        val results = h.lastReply(sid).blocks.filterIsInstance<ToolResultBlock>()
        assertTrue(results.all { it.isError })
        assertEquals("Unknown tool: nope", results[0].content)
    }

    @Test
    fun `approval pause allows or denies`() = runTest {
        val exec = FakeTool("execute", approval = ApprovalMode.ALWAYS) { _, _ -> ToolOutcome("ran") }
        val h = Harness(this, tools = listOf(exec))
        h.llm.events(call("c1", "execute", """{"code":"1+1"}"""), finished("tool_calls"))
        h.llm.events(call("c2", "execute", """{"code":"2+2"}"""), finished("tool_calls"))
        h.llm.text("end")
        val sid = h.sendNew()
        advanceUntilIdle()

        val p1 = (phase(h, sid) as RunPhase.Paused).pause as Pause.Approval
        assertEquals("1+1", p1.request.code)
        val live = h.registry.runs.value.getValue(sid).live.body as MessageBody.Assistant
        assertEquals(ToolCallState.AWAITING_APPROVAL, live.blocks.filterIsInstance<ToolCallBlock>().single().state)
        h.engine.resolve(sid, Resolution.StepLimitAnswer(true)) // wrong kind: ignored
        h.engine.resolve(sid, Resolution.ApprovalAnswer(p1.id, allow = false))
        advanceUntilIdle()
        val p2 = (phase(h, sid) as RunPhase.Paused).pause as Pause.Approval
        h.engine.resolve(sid, Resolution.ApprovalAnswer(p2.id, allow = true))
        advanceUntilIdle()

        val reply = h.lastReply(sid)
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals(listOf(ToolCallState.DENIED, ToolCallState.DONE), reply.blocks.filterIsInstance<ToolCallBlock>().map { it.state })
        val results = reply.blocks.filterIsInstance<ToolResultBlock>().map { it.content }
        assertEquals(SystemPrompt.DENIED, results[0])
        assertEquals("<untrusted_page_content>\nran\n</untrusted_page_content>", results[1])
        assertEquals(1, exec.calls)
    }

    @Test
    fun `handoff gives the tab to the user and resumes with a snapshot hint`() = runTest {
        val handoff = FakeTool("request_handoff") { _, _ -> ToolOutcome("waiting", handoff = HandoffRequest(7, "login")) }
        val h = Harness(this, tools = listOf(handoff))
        h.llm.events(call("c1", "request_handoff"), finished("tool_calls"))
        h.llm.text("thanks")
        val sid = h.sendNew()
        advanceUntilIdle()

        assertEquals(Pause.Handoff(7, "login"), (phase(h, sid) as RunPhase.Paused).pause)
        assertEquals(listOf(7 to Control.USER), h.browser.setCalls)
        h.engine.resolve(sid, Resolution.HandoffReturned)
        advanceUntilIdle()

        assertEquals(listOf(7 to Control.USER, 7 to Control.AI), h.browser.setCalls)
        val reply = h.lastReply(sid)
        assertEquals(SystemPrompt.HANDOFF_DONE, reply.blocks.filterIsInstance<ToolResultBlock>().single().content)
        assertEquals(ReplyStatus.DONE, reply.status)
    }

    @Test
    fun `ai tab waits while the user holds control`() = runTest {
        val click = FakeTool("click") { _, ctx -> ToolOutcome("clicked ${ctx.aiTab(3).id}") }
        val h = Harness(this, tools = listOf(click))
        h.browser.controls.value = mapOf(3 to Control.USER)
        h.llm.events(call("c1", "click"), finished("tool_calls"))
        h.llm.text("ok")
        val sid = h.sendNew()
        advanceUntilIdle()
        assertEquals(Pause.UserControl(3), (phase(h, sid) as RunPhase.Paused).pause)
        h.browser.controls.value = mapOf(3 to Control.AI)
        advanceUntilIdle()
        assertEquals("clicked 3", h.lastReply(sid).blocks.filterIsInstance<ToolResultBlock>().single().content)
    }

    @Test
    fun `step limit pauses every maxSteps and can end the reply`() = runTest {
        val tool = FakeTool("open_tab")
        val h = Harness(this, tools = listOf(tool), maxSteps = 2)
        repeat(4) { h.llm.events(call("c$it", "open_tab"), finished("tool_calls")) }
        val sid = h.sendNew()
        advanceUntilIdle()

        assertEquals(Pause.StepLimit(2), (phase(h, sid) as RunPhase.Paused).pause)
        h.engine.resolve(sid, Resolution.StepLimitAnswer(continueRun = true))
        advanceUntilIdle()
        assertEquals(Pause.StepLimit(4), (phase(h, sid) as RunPhase.Paused).pause)
        h.engine.resolve(sid, Resolution.StepLimitAnswer(continueRun = false))
        advanceUntilIdle()

        val reply = h.lastReply(sid)
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals(4, reply.steps)
        assertEquals(4, tool.calls)
    }

    @Test
    fun `stop during a tool saves STOPPED with synthetic results`() = runTest {
        val started = CompletableDeferred<Unit>()
        val slow = FakeTool("navigate") { _, _ -> started.complete(Unit); awaitCancellation() }
        val h = Harness(this, tools = listOf(slow))
        h.llm.events(
            LlmEvent.TextDelta("Let me look."),
            LlmEvent.ToolCallsReady(listOf(ToolCallRequest("c1", "navigate", "{}"), ToolCallRequest("c2", "navigate", "{}"))),
            finished("tool_calls"),
        )
        val sid = h.sendNew()
        advanceUntilIdle()
        assertTrue(started.isCompleted)
        assertFailsRejected { h.engine.send(sid, draft("again"), GenOptions(), TEST_MODEL) }
        h.engine.stop(sid)
        advanceUntilIdle()

        val reply = h.lastReply(sid)
        assertEquals(ReplyStatus.STOPPED, reply.status)
        val results = reply.blocks.filterIsInstance<ToolResultBlock>().associate { it.callId to it.content }
        assertEquals(SystemPrompt.INTERRUPTED, results["c1"])
        assertEquals(SystemPrompt.ABORTED, results["c2"])
        assertTrue(reply.blocks.filterIsInstance<ToolCallBlock>().all { it.state == ToolCallState.CANCELLED })
        assertTrue(h.registry.runs.value.isEmpty())
    }

    @Test
    fun `stop while streaming keeps the partial text`() = runTest {
        val h = Harness(this)
        h.llm.scripts.addLast { emit(LlmEvent.TextDelta("partial")); awaitCancellation() }
        val sid = h.sendNew()
        advanceUntilIdle()
        h.engine.stopAll()
        val reply = h.lastReply(sid)
        assertEquals(ReplyStatus.STOPPED, reply.status)
        assertEquals("partial", (reply.blocks.single() as TextBlock).text)
    }

    @Test
    fun `server error is saved as ERROR`() = runTest {
        val h = Harness(this)
        h.llm.events(LlmEvent.Failed(LlmError.Auth(401, "bad key"), partial = false))
        val sid = h.sendNew()
        advanceUntilIdle()
        val reply = h.lastReply(sid)
        assertEquals(ReplyStatus.ERROR, reply.status)
        assertEquals(com.ouor.localchat.core.model.ChatErrorKind.AUTH, reply.error?.kind)
    }

    @Test
    fun `context overflow compacts once and retries the same step`() = runTest {
        val h = Harness(this)
        val sid = threeTurns(h)
        h.llm.events(LlmEvent.Failed(LlmError.ContextOverflow(400, "too long"), partial = false))
        h.llm.text("fresh answer")
        h.llm.results.addLast(LlmResult.Ok("SUMMARY", "stop", null))
        h.engine.send(sid, draft("q4"), GenOptions(), TEST_MODEL)
        advanceUntilIdle()

        val reply = h.lastReply(sid)
        assertEquals(ReplyStatus.DONE, reply.status)
        assertNull(reply.error)
        val summary = h.store.summaryList.single()
        assertEquals("SUMMARY", summary.text)
        val retry = h.llm.requests.last().messages
        assertEquals(SystemPrompt.SUMMARY_PREFIX + "SUMMARY", retry[1].content!!.jsonPrimitive.content)
        // q1, q2 turns are summarized; the latest two user turns (q3, q4) stay.
        assertFalse(retry.any { it.content?.toString()?.contains("q1") == true })
        assertTrue(retry.any { it.content?.toString()?.contains("q4") == true })
    }

    @Test
    fun `estimate over 80 percent of the window compacts before sending`() = runTest {
        val h = Harness(this, models = FakeModelPort(caps(window = 100)))
        val sid = threeTurns(h, usage = Usage(90, 10))
        h.llm.results.addLast(LlmResult.Ok("S", "stop", null))
        h.llm.text("a4")
        h.engine.send(sid, draft("q4"), GenOptions(), TEST_MODEL)
        advanceUntilIdle()
        assertEquals(1, h.store.summaryList.size)
        assertEquals(1, h.llm.requests.size)
        assertTrue(h.llm.requests.single().messages[1].content!!.jsonPrimitive.content.startsWith(SystemPrompt.SUMMARY_PREFIX))
    }

    @Test
    fun `continue appends to the last text block`() = runTest {
        val h = Harness(this)
        h.llm.scripts.addLast { emit(LlmEvent.TextDelta("Once upon")); awaitCancellation() }
        val sid = h.sendNew()
        advanceUntilIdle()
        h.engine.stopAll()
        val id = h.store.messages.values.last().id
        h.llm.text(" a time")
        h.engine.continueReply(id)
        advanceUntilIdle()

        val reply = h.store.reply(id)
        assertEquals("Once upon a time", (reply.blocks.single() as TextBlock).text)
        assertEquals(ReplyStatus.DONE, reply.status)
        val msgs = h.llm.requests.last().messages
        assertEquals(SystemPrompt.CONTINUE, msgs.last().content!!.jsonPrimitive.content)
        assertEquals("Once upon", msgs[msgs.size - 2].content!!.jsonPrimitive.content)
    }

    @Test
    fun `regenerate adds a sibling reply and selects it`() = runTest {
        val h = Harness(this)
        h.llm.text("first")
        val sid = h.sendNew()
        advanceUntilIdle()
        val first = h.store.messages.values.last()
        h.llm.text("second")
        h.engine.regenerate(first.id, TEST_MODEL)
        advanceUntilIdle()
        val user = h.store.messages.getValue(first.parentId!!)
        val second = h.store.messages.getValue(user.selectedChildId!!)
        assertTrue(second.id != first.id)
        assertEquals("second", ((second.body as MessageBody.Assistant).blocks.single() as TextBlock).text)
        assertEquals(sid, second.sessionId)
    }

    @Test
    fun `recovery stops unfinished replies with interrupted results`() = runTest {
        val h = Harness(this)
        val sid = Ids.new()
        h.store.sessions[sid] = com.ouor.localchat.core.model.Session(sid, "t", TitleSource.NONE, 0, 0, null, null)
        val node = com.ouor.localchat.core.model.MessageNode(
            "a", sid, null, null, com.ouor.localchat.core.model.Role.ASSISTANT, 0,
            MessageBody.Assistant(
                listOf(ToolCallBlock("c1", "click", "{}", 0, ToolCallState.RUNNING, 0)),
                ReplyStatus.STREAMING, model = null, options = GenOptions(),
            ),
        )
        h.store.messages["a"] = node
        StartupRecovery(h.store, h.registry, h.clock).run()
        val reply = h.store.reply("a")
        assertEquals(ReplyStatus.STOPPED, reply.status)
        assertEquals(SystemPrompt.INTERRUPTED, reply.blocks.filterIsInstance<ToolResultBlock>().single().content)
        assertTrue(h.store.gcCutoff != null)
    }

    private suspend fun TestScope.threeTurns(h: Harness, usage: Usage? = null): String {
        h.llm.events(LlmEvent.TextDelta("a1"), LlmEvent.Finished("stop", usage))
        val sid = h.engine.send(null, draft("q1"), GenOptions(), TEST_MODEL)
        advanceUntilIdle()
        for (i in 2..3) {
            h.llm.events(LlmEvent.TextDelta("a$i"), LlmEvent.Finished("stop", usage))
            h.engine.send(sid, draft("q$i"), GenOptions(), TEST_MODEL)
            advanceUntilIdle()
        }
        h.llm.requests.clear()
        h.llm.completeRequests.clear()
        return sid
    }

    private suspend fun assertFailsRejected(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ChatRejected) {
            assertEquals(ChatRejected.Reason.RUN_ACTIVE, e.reason)
            return
        }
        throw AssertionError("expected ChatRejected")
    }
}
