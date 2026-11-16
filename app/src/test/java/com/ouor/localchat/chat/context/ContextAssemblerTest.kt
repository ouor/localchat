package com.ouor.localchat.chat.context

import com.ouor.localchat.chat.AttachmentPayload
import com.ouor.localchat.chat.ResolvedModel
import com.ouor.localchat.chat.TEST_MODEL
import com.ouor.localchat.chat.caps
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.Summary
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.llm.LlmTarget
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAssemblerTest {

    private var t = 0L
    private fun user(id: String, text: String, files: List<String> = emptyList()) =
        MessageNode(id, "s", null, null, Role.USER, t++, MessageBody.User(text, files))

    private fun reply(id: String, vararg blocks: com.ouor.localchat.core.model.Block, status: ReplyStatus = ReplyStatus.DONE, error: ChatError? = null, usage: Usage? = null) =
        MessageNode(id, "s", null, null, Role.ASSISTANT, t++, MessageBody.Assistant(blocks.toList(), status, error = error, model = TEST_MODEL, options = GenOptions(), usage = usage))

    private val model = ResolvedModel(TEST_MODEL, LlmTarget("u", null, 0, false), caps())

    private fun input(path: List<MessageNode>, current: MessageBody.Assistant = empty(), summaries: List<Summary> = emptyList(), attachments: Map<String, AttachmentPayload> = emptyMap(), options: GenOptions = GenOptions()) =
        AssemblyInput("SYS", path, current, summaries, attachments, model, options, emptyList())

    private fun empty() = MessageBody.Assistant(emptyList(), ReplyStatus.STREAMING, model = TEST_MODEL, options = GenOptions())

    private fun texts(input: AssemblyInput) = ContextAssembler.build(input).messages.map { it.role to it.content?.jsonPrimitive?.content }

    @Test
    fun `failed, refused and empty stopped turns are dropped whole`() {
        val path = listOf(
            user("u1", "ok q"), reply("a1", TextBlock("ok a", 0)),
            user("u2", "err q"), reply("a2", status = ReplyStatus.ERROR, error = ChatError(ChatErrorKind.NETWORK)),
            user("u3", "stop q"), reply("a3", status = ReplyStatus.STOPPED),
            user("u4", "now"),
        )
        assertEquals(
            listOf("system" to "SYS", "user" to "ok q", "assistant" to "ok a", "user" to "now"),
            texts(input(path)),
        )
    }

    @Test
    fun `reasoning goes back only for the current turn's tool steps`() {
        val old = reply("a1", ThinkingBlock("old think", 0, 0, 1), ToolCallBlock("c0", "page_text", "{}", 0, ToolCallState.DONE, 0),
            ToolResultBlock("c0", 0, "r0", false), TextBlock("answer", 1))
        val current = empty().copy(blocks = listOf(ThinkingBlock("now think", 0, 0, 1), ToolCallBlock("c1", "page_text", "{}", 0, ToolCallState.DONE, 0)))
        val msgs = ContextAssembler.build(input(listOf(user("u1", "q"), old, user("u2", "q2")), current)).messages
        val assistants = msgs.filter { it.role == "assistant" }
        assertNull(assistants[0].reasoningContent)
        assertEquals("answer", assistants[1].content!!.jsonPrimitive.content)
        assertEquals("now think", assistants[2].reasoningContent)
        // c1 has no result yet: paired with the synthetic one.
        assertEquals(SystemPrompt.ABORTED, msgs.last().content!!.jsonPrimitive.content)
        assertEquals("c1", msgs.last().toolCallId)
    }

    @Test
    fun `summary replaces the path up to its cut point`() {
        val path = listOf(user("u1", "q1"), reply("a1", TextBlock("x", 0)), user("u2", "q2"))
        val s = Summary("s1", "s", "a1", "SUM", 5)
        assertEquals(
            listOf("system" to "SYS", "user" to SystemPrompt.SUMMARY_PREFIX + "SUM", "user" to "q2"),
            texts(input(path, summaries = listOf(s))),
        )
        // A summary of another branch does not apply.
        assertEquals(4, texts(input(path, summaries = listOf(s.copy(coversUpToId = "elsewhere")))).size)
    }

    @Test
    fun `attachments become text when the model has no image input`() {
        val img = Attachment("i", "cat.png", "image/png", 1, AttachmentKind.IMAGE, PrepState.Ready, true, createdAt = 0)
        val doc = Attachment("d", "a.txt", "text/plain", 1, AttachmentKind.DOCUMENT, PrepState.Ready, true, createdAt = 0)
        val gone = doc.copy(id = "g", hasOriginal = false)
        val payloads = mapOf("i" to AttachmentPayload(img, imageDataUrl = "data:x"), "d" to AttachmentPayload(doc, text = "body"), "g" to AttachmentPayload(gone, text = "no"))
        val content = texts(input(listOf(user("u1", "see", listOf("i", "d", "g"))), attachments = payloads))[1].second
        assertEquals("see\n\n[이미지: cat.png]\n\n파일: a.txt\n```\nbody\n```", content)
    }

    @Test
    fun `reasoning switch uses the model wire`() {
        val off = ContextAssembler.build(input(listOf(user("u", "q"))))
        assertEquals("disabled", off.thinking?.type)
        val on = ContextAssembler.build(input(listOf(user("u", "q")), options = GenOptions(reasoning = true, reasoningEffort = "high")))
        assertEquals("enabled", on.thinking?.type)
        assertEquals("high", on.reasoningEffort)
    }

    @Test
    fun `only older bulky results are trimmed`() {
        val big = "x".repeat(3000)
        val blocks = (0..2).flatMap { listOf(ToolCallBlock("c$it", "snapshot", "{}", it, ToolCallState.DONE, 0), ToolResultBlock("c$it", it, big, false)) }
        val msgs = ContextAssembler.build(input(listOf(user("u", "q")), empty().copy(blocks = blocks))).messages.filter { it.role == "tool" }
        val sent = msgs.map { it.content!!.jsonPrimitive.content }
        assertTrue(sent[0].contains("…[중략 1000자]…"))
        assertEquals(big, sent[1])
        assertEquals(big, sent[2])
    }
}
