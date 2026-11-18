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
import org.junit.Assert.assertFalse
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
    fun `the same step and call id in two replies keeps each reply's own result (AN 4)`() {
        fun call() = com.ouor.localchat.core.model.ToolCallBlock("call_0", "page_text", "{}", 0, com.ouor.localchat.core.model.ToolCallState.DONE, 0)
        fun result(text: String) = com.ouor.localchat.core.model.ToolResultBlock("call_0", 0, text, isError = false)
        val path = listOf(
            user("u1", "q1"), reply("a1", call(), result("PAGE A"), TextBlock("A", 1)),
            user("u2", "q2"), reply("a2", call(), result("PAGE B"), TextBlock("B", 1)),
            user("u3", "q3"),
        )
        val tools = ContextAssembler.build(input(path)).messages.filter { it.role == "tool" }.map { it.content!!.jsonPrimitive.content }
        assertEquals(listOf("PAGE A", "PAGE B"), tools)
    }

    @Test
    fun `an ERROR turn that already ran tools stays in context so they are not repeated (SE 12)`() {
        val click = com.ouor.localchat.core.model.ToolCallBlock("c1", "click", "{}", 0, com.ouor.localchat.core.model.ToolCallState.DONE, 0)
        val done = com.ouor.localchat.core.model.ToolResultBlock("c1", 0, "ordered", isError = false)
        val path = listOf(
            user("u1", "buy it"), reply("a1", click, done, status = ReplyStatus.ERROR, error = ChatError(ChatErrorKind.SERVER_BUSY)),
            user("u2", "go on"),
        )
        val msgs = ContextAssembler.build(input(path)).messages
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), msgs.map { it.role })
        assertEquals("ordered", msgs[3].content!!.jsonPrimitive.content)
    }

    @Test
    fun `only the newest screenshot is resent as an image and every one is estimated (SE 13)`() {
        fun shot(n: Int) = listOf(
            com.ouor.localchat.core.model.ToolCallBlock("c$n", "screenshot", "{}", n, com.ouor.localchat.core.model.ToolCallState.DONE, 0),
            com.ouor.localchat.core.model.ToolResultBlock("c$n", n, "shot $n", isError = false, imageForModelId = "img$n"),
        )
        val att = (1..3).associate { n ->
            "img$n" to AttachmentPayload(
                com.ouor.localchat.core.model.Attachment("img$n", "s.png", "image/png", 1, com.ouor.localchat.core.model.AttachmentKind.ARTIFACT, com.ouor.localchat.core.model.PrepState.Ready, true, createdAt = 0),
                imageDataUrl = "data:image/png;base64,$n",
            )
        }
        val path = listOf(user("u1", "q1"), reply("a1", *(shot(1) + shot(2)).toTypedArray(), TextBlock("ok", 3)), user("u2", "q2"))
        val current = empty().copy(blocks = shot(3))
        val vision = model.copy(caps = caps().copy(imageInput = com.ouor.localchat.core.model.Sourced(true, com.ouor.localchat.core.model.CapSource.DEFAULT)))
        val request = ContextAssembler.build(input(path, current, attachments = att).copy(model = vision))
        val images = request.messages.filter { it.role == "user" }.map { it.content.toString() }.filter { "image_url" in it }
        assertEquals(1, images.size)
        assertTrue(images.single().contains("base64,3"))

        // Usage-anchored estimate: a screenshot taken after the last usage counts as an image, not 6 chars.
        val anchored = listOf(user("u1", "q1"), reply("a1", TextBlock("ok", 0), usage = Usage(10, 10)), user("u2", "q2"))
        val estimate = TokenEstimate.estimate(request, anchored + MessageNode("cur", "s", null, null, Role.ASSISTANT, t++, current), null)
        assertTrue("$estimate", estimate >= 20 + 1000)
    }

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

    private fun afterCall(): Pair<List<MessageNode>, MessageBody.Assistant> {
        val call = ToolCallBlock("c1", "page_text", "{}", 0, ToolCallState.DONE, 0)
        val result = ToolResultBlock("c1", 0, "PAGE", isError = false)
        return listOf(user("u1", "q1"), reply("a1", TextBlock("x", 0)), user("u2", "q2")) to empty().copy(blocks = listOf(call, result))
    }

    @Test
    fun `the tools-off note as the last user message, with tool_choice none (FB3 D1)`() {
        val (path, current) = afterCall()
        val request = ContextAssembler.build(input(path, current).copy(noCalls = true, note = NotePlacement.LAST_USER))
        assertEquals("none", request.toolChoice)
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "tool", "user"), request.messages.map { it.role })
        assertEquals(SystemPrompt.TOOLS_OFF_NOTE, request.messages.last().content!!.jsonPrimitive.content)
        assertEquals("SYS", request.messages.first().content!!.jsonPrimitive.content)
        assertNull(ContextAssembler.build(input(path, current)).toolChoice)
    }

    @Test
    fun `the tools-off note at the end of the system message keeps tool last, also with a summary (FB4 E-1)`() {
        val (path, current) = afterCall()
        val s = Summary("s1", "s", "a1", "SUM", 5)
        val msgs = ContextAssembler.build(input(path, current, summaries = listOf(s)).copy(note = NotePlacement.SYSTEM_END)).messages
        assertEquals(listOf("system", "user", "user", "assistant", "tool"), msgs.map { it.role })
        assertEquals("SYS\n" + SystemPrompt.TOOLS_OFF_NOTE, msgs.first().content!!.jsonPrimitive.content)
        assertEquals(SystemPrompt.SUMMARY_PREFIX + "SUM", msgs[1].content!!.jsonPrimitive.content)
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
        assertEquals("see\n\n[Image: cat.png]\n\nFile: a.txt\n```\nbody\n```", content)
    }

    @Test
    fun `reasoning switch uses the model wire`() {
        val off = ContextAssembler.build(input(listOf(user("u", "q"))))
        assertEquals("disabled", off.thinking?.type)
        val levels = model.copy(caps = caps().copy(reasoning = com.ouor.localchat.core.model.Sourced(
            com.ouor.localchat.core.model.ReasoningSpec(listOf("high", "max"), "high", com.ouor.localchat.core.model.ReasoningWire.DEEPSEEK_THINKING),
            com.ouor.localchat.core.model.CapSource.SERVER)))
        val on = ContextAssembler.build(input(listOf(user("u", "q")), options = GenOptions(reasoning = true, reasoningEffort = "max")).copy(model = levels))
        assertEquals("enabled", on.thinking?.type)
        assertEquals("max", on.reasoningEffort)
        // An effort another model offered falls back to this model's default.
        val foreign = ContextAssembler.build(input(listOf(user("u", "q")), options = GenOptions(reasoning = true, reasoningEffort = "minimal")).copy(model = levels))
        assertEquals("high", foreign.reasoningEffort)
    }

    @Test
    fun `a model whose reasoning is switched off never reasons, whatever the options say`() {
        val noReasoning = model.copy(caps = caps().copy(reasoning = com.ouor.localchat.core.model.Sourced(null, com.ouor.localchat.core.model.CapSource.USER)))
        val req = ContextAssembler.build(input(listOf(user("u", "q")), options = GenOptions(reasoning = true, reasoningEffort = "high")).copy(model = noReasoning))
        // ResolvedModel keeps the DeepSeek wire, so "off" is sent explicitly.
        assertEquals("disabled", req.thinking?.type)
        assertNull(req.reasoningEffort)
    }

    @Test
    fun `FB-6 max_tokens is sent only for a user-set max output`() {
        fun maxTokens(source: com.ouor.localchat.core.model.CapSource) = ContextAssembler.build(
            input(listOf(user("u", "q"))).copy(model = model.copy(caps = caps().copy(maxOutputTokens = com.ouor.localchat.core.model.Sourced(8192, source)))),
        ).maxTokens
        assertEquals(8192, maxTokens(com.ouor.localchat.core.model.CapSource.USER))
        assertNull(maxTokens(com.ouor.localchat.core.model.CapSource.SERVER))
        assertNull(maxTokens(com.ouor.localchat.core.model.CapSource.DEFAULT))
        assertNull(ContextAssembler.build(input(listOf(user("u", "q")))).maxTokens)
        assertNull(ContextAssembler.plain(model.copy(caps = caps().copy(maxOutputTokens = com.ouor.localchat.core.model.Sourced(8192, com.ouor.localchat.core.model.CapSource.USER))), "s", "u").maxTokens)
    }

    @Test
    fun `M-1 a user max output larger than the room left is cut to the room, whatever room is left`() {
        val req = ContextAssembler.build(input(listOf(user("u", "q")))).copy(maxTokens = 4096)
        // Window 8K, input 7K, user 4K: what is sent fits the 1K left.
        assertEquals(1024, ContextAssembler.fitOutput(req, 8192, 7168).maxTokens)
        assertEquals(4096, ContextAssembler.fitOutput(req, 32768, 7168).maxTokens)
        // Audit #6: under 256 left the room is still sent (omitting it lets the server default overrun it).
        assertEquals(192, ContextAssembler.fitOutput(req, 8192, 8000).maxTokens)
        assertNull(ContextAssembler.fitOutput(req, 8192, 8192).maxTokens)
        assertTrue(ContextAssembler.tooLittleRoom(req, 8192, 8000))
        assertFalse(ContextAssembler.tooLittleRoom(req, 8192, 7168))
        assertFalse(ContextAssembler.tooLittleRoom(req.copy(maxTokens = null), 8192, 8000))
        assertEquals(4096, ContextAssembler.fitOutput(req, null, 7168).maxTokens)
        assertNull(ContextAssembler.fitOutput(req.copy(maxTokens = null), 8192, 7168).maxTokens)
    }

    @Test
    fun `only older bulky results are trimmed`() {
        val big = "x".repeat(3000)
        val blocks = (0..2).flatMap { listOf(ToolCallBlock("c$it", "execute", "{}", it, ToolCallState.DONE, 0), ToolResultBlock("c$it", it, big, false)) }
        val msgs = ContextAssembler.build(input(listOf(user("u", "q")), empty().copy(blocks = blocks))).messages.filter { it.role == "tool" }
        val sent = msgs.map { it.content!!.jsonPrimitive.content }
        assertTrue(sent[0].contains("…[1000 characters omitted]…"))
        assertEquals(big, sent[1])
        assertEquals(big, sent[2])
    }

    @Test
    fun `older element lists are sent as their head`() {
        val list = "Page: A — https://a.test/\n\n- link \"x\" [ref=e1]"
        val blocks = (0..1).flatMap { listOf(ToolCallBlock("c$it", "browse", "{}", it, ToolCallState.DONE, 0), ToolResultBlock("c$it", it, list, false)) }
        val msgs = ContextAssembler.build(input(listOf(user("u", "q")), empty().copy(blocks = blocks))).messages.filter { it.role == "tool" }
        val sent = msgs.map { it.content!!.jsonPrimitive.content }
        assertEquals("Page: A — https://a.test/\n" + com.ouor.localchat.chat.context.ToolResultTrimmer.STALE, sent[0])
        assertEquals(list, sent[1])
    }
}
