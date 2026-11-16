package com.ouor.localchat.chat

import com.ouor.localchat.chat.context.TokenEstimate
import com.ouor.localchat.chat.context.ToolResultTrimmer
import com.ouor.localchat.chat.run.StreamCollector
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.llm.LlmEvent
import com.ouor.localchat.llm.ToolCallRequest
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.llm.wire.WireMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PureLogicTest {

    @Test
    fun `system prompt always marks page text untrusted and, with tools, names sensitive sites (SE 10, SE 28)`() {
        val zone = java.time.ZoneId.of("UTC")
        val plain = com.ouor.localchat.chat.context.SystemPrompt.build(0, zone, null, "", tools = false)
        val tools = com.ouor.localchat.chat.context.SystemPrompt.build(0, zone, null, "", tools = true)
        assertTrue(plain.contains("untrusted_page_content"))
        assertTrue(tools.contains("Sensitive sites"))
        assertTrue(tools.contains("tell the user what it asks before doing it"))
    }

    private val empty = MessageBody.Assistant(emptyList(), ReplyStatus.STREAMING, model = null, options = GenOptions())

    @Test
    fun `stream reducer groups deltas into blocks`() {
        val events = listOf(
            LlmEvent.ReasoningDelta("a", "reasoning_content"), LlmEvent.ReasoningDelta("b", "reasoning_content"),
            LlmEvent.TextDelta("x"), LlmEvent.TextDelta("y"),
            LlmEvent.ToolCallsReady(listOf(ToolCallRequest("c", "n", "{}"))),
            LlmEvent.Finished("content_filter", Usage(1, 2)),
        )
        var t = 0L
        val body = events.fold(empty) { b, e -> StreamCollector.reduce(b, e, step = 0, now = t++) }
        val thinking = body.blocks[0] as ThinkingBlock
        assertEquals("ab", thinking.text)
        assertEquals(2L, thinking.endedAt)
        assertEquals("xy", (body.blocks[1] as TextBlock).text)
        assertEquals("c", (body.blocks[2] as ToolCallBlock).callId)
        assertTrue(body.refusal)
        assertEquals(Usage(1, 2), body.usage)
    }

    @Test
    fun `trimmer keeps short and latest results`() {
        assertEquals("short", ToolResultTrimmer.cut("short"))
        val cut = ToolResultTrimmer.cut("a".repeat(1500) + "b".repeat(1000) + "c".repeat(500))
        assertEquals("a".repeat(1500) + "…[중략 1000자]…" + "c".repeat(500), cut)
        val big = "z".repeat(2500)
        val out = ToolResultTrimmer.trim(listOf(Triple("1", "page_text", big), Triple("2", "open_tab", big), Triple("3", "execute", big), Triple("4", "snapshot", big)))
        assertTrue(out.getValue("1").length < big.length)
        assertEquals(big, out["2"])
        assertEquals(big, out["3"])
        assertEquals(big, out["4"])
    }

    @Test
    fun `title cleanup`() {
        assertEquals("Paris trip plan", TitleGenerator.clean("Title: \"Paris trip plan.\"\nmore"))
        assertEquals("파리 여행", TitleGenerator.clean("# 제목: 파리 여행"))
        assertNull(TitleGenerator.clean("  \n \"\" "))
        assertEquals(30, TitleGenerator.firstPrompt("x".repeat(50)).length)
        assertEquals("a b", TitleGenerator.firstPrompt(" a\n\n b "))
    }

    @Test
    fun `token estimate uses usage then chars`() {
        val req = ChatRequest("m", listOf(WireMessage("user", JsonPrimitive("x".repeat(350)))))
        assertEquals(100, TokenEstimate.estimate(req, emptyList(), null))
        val used = MessageNode("a", "s", null, null, Role.ASSISTANT, 10, empty.copy(usage = Usage(1000, 0), status = ReplyStatus.DONE))
        val next = MessageNode("u", "s", "a", null, Role.USER, 11, MessageBody.User("y".repeat(35)))
        assertEquals(1010, TokenEstimate.estimate(req, listOf(used, next), null))
        // Usage reported before a summary is stale.
        assertEquals(100, TokenEstimate.estimate(req, listOf(used, next), summaryAt = 10))
    }

    @Test
    fun `close marks unanswered calls`() {
        val body = empty.copy(blocks = listOf(
            ToolCallBlock("a", "n", "{}", 0, com.ouor.localchat.core.model.ToolCallState.DONE, 0),
            ToolResultBlock("a", 0, "r", false),
            ToolCallBlock("b", "n", "{}", 0, com.ouor.localchat.core.model.ToolCallState.RUNNING, 0),
        ))
        val closed = StreamCollector.close(body, inFlightCallId = "b", now = 5)
        assertEquals(ReplyStatus.STOPPED, closed.status)
        assertEquals(2, closed.blocks.filterIsInstance<ToolResultBlock>().size)
    }

    @Test
    fun `write queue keeps the newest failed write and retries`() = runTest {
        val q = WriteQueue()
        var fail = true
        val written = mutableListOf<String>()
        q.write("n") { if (fail) error("disk") else written += "old" }
        assertEquals(SaveHealth.FAILING, q.health.value)
        q.write("n") { if (fail) error("disk") else written += "new" }
        fail = false
        q.retry()
        assertEquals(listOf("new"), written)
        assertEquals(SaveHealth.OK, q.health.value)
    }
}
