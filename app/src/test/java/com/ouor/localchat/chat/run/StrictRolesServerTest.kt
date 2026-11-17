package com.ouor.localchat.chat.run

import com.ouor.localchat.chat.ClientLlmPort
import com.ouor.localchat.chat.FakeAttachments
import com.ouor.localchat.chat.FakeBrowserPort
import com.ouor.localchat.chat.FakeChatStore
import com.ouor.localchat.chat.FakeModelPort
import com.ouor.localchat.chat.FakeTool
import com.ouor.localchat.chat.FakeToolPort
import com.ouor.localchat.chat.MemoryDataStore
import com.ouor.localchat.chat.TEST_MODEL
import com.ouor.localchat.chat.TickClock
import com.ouor.localchat.chat.caps
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.chat.wireEngine
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.AppSettings
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FB4 E-1: a server that rejects `user` right after `tool` (Mistral) still gets the tools-off answer. */
class StrictRolesServerTest {

    @Test
    fun `a 400 for the note after a tool result is retried once with the note in the system message`() = runBlocking {
        FakeOpenAiServer().use { server ->
            server.rejectUserAfterTool = true
            val store = FakeChatStore()
            lateinit var engine: com.ouor.localchat.chat.ChatEngine
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
            server.enqueue(Scenario.toolCall("click", "{}"), Scenario.text("Seoul has 9,668,465 people."))

            withTimeout(10_000) {
                val sid = engine.send(null, Draft(Draft.NEW_CHAT, "populations?", emptyList(), 0), GenOptions(browser = true), TEST_MODEL)
                engine.runs.first { sid !in it }
            }

            val reply = store.messages.values.last { it.body is MessageBody.Assistant }.body as MessageBody.Assistant
            assertEquals(ReplyStatus.DONE, reply.status)
            assertEquals("Seoul has 9,668,465 people.", reply.blocks.filterIsInstance<TextBlock>().single().text)
            val sent = List(3) { AppJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["messages"]!!.jsonArray }
            fun roles(i: Int) = sent[i].map { it.jsonObject["role"]!!.jsonPrimitive.content }
            // DeepSeek's form first (the note as the last user message), then the one a strict server accepts.
            assertEquals(listOf("system", "user", "assistant", "tool", "user"), roles(1))
            assertEquals(listOf("system", "user", "assistant", "tool"), roles(2))
            assertTrue(sent[2].first().jsonObject["content"]!!.jsonPrimitive.content.endsWith(SystemPrompt.TOOLS_OFF_NOTE))
        }
    }
}
