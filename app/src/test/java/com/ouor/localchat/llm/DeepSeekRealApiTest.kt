package com.ouor.localchat.llm

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.llm.wire.WireMessage
import com.ouor.localchat.llm.wire.withReasoning
import com.ouor.localchat.provider.CapabilityResolver
import com.ouor.localchat.provider.KnownModels
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * RealApi: checks the architecture 0 facts against api.deepseek.com. Skipped unless both
 * `DEEPSEEK_API_KEY` and `LOCALCHAT_REAL_API=1` are set, so a plain test run never spends tokens.
 * The key is only read here and never printed (LlmTarget.toString masks it).
 */
class DeepSeekRealApiTest {

    private val key: String? = System.getenv("DEEPSEEK_API_KEY")
    private val client = ChatCompletionsClient(OkHttpClient(), AppJson)
    private val target get() = LlmTarget("https://api.deepseek.com", key, maxRetries = 2, allowHttp = false)
    private fun ask(text: String) = ChatRequest("deepseek-flash", listOf(WireMessage("user", JsonPrimitive(text))))

    @Before
    fun gate() = assumeTrue(!key.isNullOrBlank() && System.getenv("LOCALCHAT_REAL_API") == "1")

    @Test
    fun `models list carries the extension fields`() = runBlocking {
        val models = client.listModels(target).associateBy { it["id"]!!.jsonPrimitive.content }
        val pro = models.getValue("deepseek-v4-pro")
        val caps = CapabilityResolver.resolve(ModelOverrides(), pro, KnownModels.find("deepseek-v4-pro", KnownModels.DEEPSEEK_HOST))
        assertFalse(caps.imageInput.value)
        assertNotNull(caps.contextWindow.value)
        assertEquals(ReasoningWire.DEEPSEEK_THINKING, caps.reasoning.value?.wire)
        assertTrue(caps.reasoning.value!!.efforts.isNotEmpty())
    }

    @Test
    fun `thinking disabled streams text only, enabled streams reasoning_content`() = runBlocking {
        val off = client.stream(target, ask("Reply with the word ok.").withReasoning(ReasoningWire.DEEPSEEK_THINKING, false, null)).toList()
        assertTrue(off.none { it is LlmEvent.ReasoningDelta })
        val end = off.last() as LlmEvent.Finished
        assertEquals("stop", end.finishReason)
        assertNotNull(end.usage)

        val on = client.stream(target, ask("What is 2+3? Answer briefly.").withReasoning(ReasoningWire.DEEPSEEK_THINKING, true, "low")).toList()
        assertTrue(on.any { it is LlmEvent.ReasoningDelta && it.field == "reasoning_content" })
        assertTrue(on.last() is LlmEvent.Finished)
    }

    @Test
    fun `tool_choice none with the tools still listed answers in text after tool-calling steps`() = runBlocking {
        // FB3 QA M1: what TurnRunner sends once the user turned every tool off mid-run.
        val tool = com.ouor.localchat.llm.wire.WireTool(com.ouor.localchat.llm.wire.WireFunction(
            "page_text", "Read the text of a tab", AppJson.parseToJsonElement("""{"type":"object","properties":{"tabId":{"type":"integer"}}}""") as kotlinx.serialization.json.JsonObject,
        ))
        for (thinking in listOf(false, true)) {
            val request = ChatRequest(
                "deepseek-flash",
                listOf(
                    WireMessage("user", JsonPrimitive("Read tabs 1 and 2 and tell me their titles.")),
                    WireMessage(
                        "assistant", null,
                        toolCalls = listOf(com.ouor.localchat.llm.wire.WireToolCall("call_1", com.ouor.localchat.llm.wire.WireFunctionCall("page_text", """{"tabId":1}"""))),
                        reasoningContent = "I will read tab 1 first.".takeIf { thinking },
                    ),
                    WireMessage("tool", JsonPrimitive("Title: Seoul"), toolCallId = "call_1"),
                ),
                tools = listOf(tool),
                toolChoice = "none",
            ).withReasoning(ReasoningWire.DEEPSEEK_THINKING, thinking, null)
            val events = client.stream(target, request).toList()
            assertTrue(events.toString(), events.last() is LlmEvent.Finished)
            assertTrue(events.none { it is LlmEvent.ToolCallsReady })
            assertFalse(events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }.contains("DSML"))
        }
    }

    @Test
    fun `wrong key is AUTH without retries`() = runBlocking {
        val events = client.stream(target.copy(apiKey = "sk-invalid"), ask("hi")).toList()
        assertEquals(1, events.size)
        assertTrue((events.single() as LlmEvent.Failed).error is LlmError.Auth)
    }
}
