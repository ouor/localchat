package com.ouor.localchat.chat.run

import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.chat.ClientLlmPort
import com.ouor.localchat.chat.FakeAttachments
import com.ouor.localchat.agent.FakeBrowserPort
import com.ouor.localchat.chat.FakeChatStore
import com.ouor.localchat.chat.FakeModelPort
import com.ouor.localchat.agent.FakeTool
import com.ouor.localchat.chat.FakeToolPort
import com.ouor.localchat.chat.MemoryDataStore
import com.ouor.localchat.chat.TEST_MODEL
import com.ouor.localchat.chat.TickClock
import com.ouor.localchat.chat.caps
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.chat.wireEngine
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.testing.FakeOpenAiServer
import com.ouor.localchat.testing.Scenario
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tools-off steps through the real client (StreamAssembler, AnswerFilter, error mapping) against a fake server:
 * the browser is switched off during the first call, so the next request carries the hidden note.
 */
class ToolsOffServerTest {

    private val dsml = "<｜DSML｜function_calls><｜DSML｜invoke name=\"click\"></｜DSML｜invoke></｜DSML｜function_calls>"

    private class Run(val reply: MessageBody.Assistant, val sent: List<JsonArray>) {
        fun roles(i: Int) = sent[i].map { it.jsonObject["role"]!!.jsonPrimitive.content }
        fun system(i: Int) = sent[i].first().jsonObject["content"]!!.jsonPrimitive.content
        fun last(i: Int) = sent[i].last().jsonObject["content"]?.jsonPrimitive?.content
        val text get() = reply.blocks.filterIsInstance<TextBlock>().joinToString("") { it.text }
    }

    /** First reply calls `click` (which switches the browser off), then [then] in order. */
    private fun run(strict: Boolean, vararg then: Scenario): Run = runBlocking {
        FakeOpenAiServer().use { server ->
            server.rejectUserAfterTool = strict
            val store = FakeChatStore()
            lateinit var engine: ChatEngine
            val click = FakeTool("click", groups = setOf(ToolGroup.BROWSER)) { _, ctx ->
                engine.narrowRun(ctx.sessionId, GenOptions())
                ToolOutcome("Seoul: 9,668,465")
            }
            val wired = wireEngine(
                this, store, ClientLlmPort(ChatCompletionsClient(OkHttpClient(), AppJson)),
                FakeModelPort(caps(tools = true), server.baseUrl), FakeToolPort(listOf(click)), FakeAttachments(),
                FakeBrowserPort(), SettingsStore(MemoryDataStore(AppSettings())), TickClock(),
            )
            engine = wired.engine
            wired.recovery.run()
            server.enqueue(Scenario.toolCall("click", "{}"), *then)
            withTimeout(10_000) {
                val sid = engine.send(null, Draft(Draft.NEW_CHAT, "populations?", emptyList(), 0), GenOptions(browser = true), TEST_MODEL)
                engine.runs.first { sid !in it }
            }
            val sent = generateSequence { runCatching { server.takeRequest(0) }.getOrNull() }
                .filter { it.url.encodedPath.endsWith("/chat/completions") }
                .map { AppJson.parseToJsonElement(it.body!!.utf8()).jsonObject["messages"]!!.jsonArray }.toList()
            Run(store.messages.values.last { it.body is MessageBody.Assistant }.body as MessageBody.Assistant, sent)
        }
    }

    @Test
    fun `a 400 for the note after a tool result is retried once with the note in the system message`() {
        // FB4 E-1: a server that rejects `user` right after `tool` (Mistral) still gets the tools-off answer.
        val r = run(strict = true, Scenario.text("Seoul has 9,668,465 people."))
        assertEquals(ReplyStatus.DONE, r.reply.status)
        assertEquals("Seoul has 9,668,465 people.", r.text)
        assertEquals(3, r.sent.size)
        // DeepSeek's form first (the note as the last user message), then the one a strict server accepts.
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), r.roles(1))
        assertEquals(listOf("system", "user", "assistant", "tool"), r.roles(2))
        assertTrue(r.system(2).endsWith(SystemPrompt.TOOLS_OFF_NOTE))
    }

    @Test
    fun `the 400 retry is spent once - a second 400 ends the reply`() {
        // FB4 eng m1: without the once-only guard this would resend until the queue ran dry.
        val r = run(strict = true, Scenario.http(400, message = "still bad"))
        assertEquals(ReplyStatus.ERROR, r.reply.status)
        assertEquals(ChatErrorKind.BAD_REQUEST, r.reply.error!!.kind)
        assertEquals(3, r.sent.size)
    }

    @Test
    fun `once moved, the note stays in the system message for the rest of the run`() {
        // FB4 eng m1: the markup-only reply goes through the real AnswerFilter, becomes an empty reply and is asked again.
        val r = run(strict = true, Scenario.text(dsml), Scenario.text("Seoul has 9,668,465 people."))
        assertEquals(ReplyStatus.DONE, r.reply.status)
        assertEquals("Seoul has 9,668,465 people.", r.text)
        assertEquals(4, r.sent.size)
        assertEquals("tool", r.roles(3).last())
        assertTrue(r.system(3).endsWith(SystemPrompt.TOOLS_OFF_NOTE))
    }

    @Test
    fun `markup-only replies through the real AnswerFilter are asked once more, then are the empty-response error`() {
        // FB4 eng m8: DeepSeek's form (the note as the last user message), DSML written as text both times.
        val r = run(strict = false, Scenario.text(dsml), Scenario.text(dsml))
        assertEquals(ReplyStatus.ERROR, r.reply.status)
        assertEquals(ChatErrorKind.EMPTY_RESPONSE, r.reply.error!!.kind)
        assertEquals("", r.text)
        assertEquals(3, r.sent.size)
        assertEquals(SystemPrompt.TOOLS_OFF_NOTE, r.last(1))
        assertEquals(SystemPrompt.TOOLS_OFF_NOTE, r.last(2))
    }
}
