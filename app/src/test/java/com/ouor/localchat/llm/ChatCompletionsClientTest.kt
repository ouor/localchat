package com.ouor.localchat.llm

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.llm.wire.Thinking
import com.ouor.localchat.llm.wire.WireMessage
import com.ouor.localchat.llm.wire.withReasoning
import com.ouor.localchat.testing.FakeOpenAiServer
import com.ouor.localchat.testing.Scenario
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatCompletionsClientTest {

    private val server = FakeOpenAiServer()
    private val client = ChatCompletionsClient(OkHttpClient(), AppJson)
    private val request = ChatRequest("fake-model", listOf(WireMessage("user", JsonPrimitive("hi"))))

    private fun target(maxRetries: Int = 5, key: String? = "test-key") = LlmTarget(server.baseUrl, key, maxRetries, allowHttp = true)

    private suspend fun run(target: LlmTarget = target()) = client.stream(target, request).toList()

    private fun List<LlmEvent>.text() = filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }

    @After
    fun close() = server.close()

    @Test
    fun `replays the recorded DeepSeek stream`() = runTest {
        server.enqueue(Scenario.raw(javaClass.getResource("/fixtures/deepseek-flash-reasoning-toolcall.sse")!!.readText()))
        val events = run()

        val reasoning = events.filterIsInstance<LlmEvent.ReasoningDelta>()
        assertEquals("The user wants weather in Seoul. Simple tool call.", reasoning.joinToString("") { it.text })
        assertTrue(reasoning.all { it.field == "reasoning_content" })
        assertEquals(
            LlmEvent.ToolCallsReady(listOf(ToolCallRequest("call_00_m57pFh6rcE9L2yXG07un7749", "get_weather", """{"city": "Seoul"}"""))),
            events[events.size - 2],
        )
        assertEquals(LlmEvent.Finished("tool_calls", Usage(295, 51, 0)), events.last())

        val sent = server.takeRequest()
        assertEquals("Bearer test-key", sent.headers["Authorization"])
        val body = AppJson.parseToJsonElement(sent.body!!.utf8()).jsonObject
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("""{"include_usage":true}""", body["stream_options"].toString())
        assertFalse("max_tokens is never sent", "max_tokens" in body)
    }

    @Test
    fun `text stream ends with exactly one Finished`() = runTest {
        server.enqueue(Scenario.text("Hel").text("lo"))
        val events = run(target(key = null))
        assertEquals("Hello", events.text())
        assertEquals(LlmEvent.Finished("stop", Usage(10, 5)), events.last())
        assertEquals(1, events.count { it is LlmEvent.Finished || it is LlmEvent.Failed })
        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `finish without DONE is a normal end`() = runTest {
        server.enqueue(Scenario.raw("""data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""" + "\n\n"))
        assertEquals(listOf(LlmEvent.TextDelta("ok"), LlmEvent.Finished("stop", null)), run())
    }

    @Test
    fun `drop after content fails as partial network error without retry`() = runTest {
        server.enqueue(Scenario.text("a").text("b").text("c").dropAfter(2), Scenario.text("never"))
        val events = run()
        assertEquals("ab", events.text())
        assertEquals(LlmEvent.Failed(LlmError.Network, partial = true), events.last())
        assertTrue(events.none { it is LlmEvent.Retrying })
    }

    @Test
    fun `drop before content is retried`() = runTest {
        server.enqueue(Scenario.text("x").dropAfter(0), Scenario.text("ok"))
        val events = run()
        assertTrue(events.first() is LlmEvent.Retrying)
        assertEquals("ok", events.text())
        assertTrue(events.last() is LlmEvent.Finished)
    }

    @Test
    fun `content_filter finishes without being an empty reply`() = runTest {
        server.enqueue(Scenario.text("").finish("content_filter"))
        assertEquals(listOf(LlmEvent.Finished("content_filter", Usage(10, 5))), run())
    }

    @Test
    fun `empty reply is retried, then reported`() = runTest {
        server.enqueue(Scenario.text(""), Scenario.text(""))
        val events = run(target(maxRetries = 1))
        assertEquals(LlmEvent.Retrying(1, 1, events.filterIsInstance<LlmEvent.Retrying>().single().delayMs), events.first())
        assertEquals(LlmEvent.Failed(LlmError.EmptyResponse, partial = false), events.last())
    }

    @Test
    fun `429 waits for Retry-After then succeeds`() = runTest {
        server.enqueue(Scenario.http(429, retryAfter = 1), Scenario.text("ok"))
        val events = run()
        assertEquals(LlmEvent.Retrying(1, 5, 1000), events.first())
        assertEquals("ok", events.text())
    }

    @Test
    fun `5xx is retried maxRetries times`() = runTest {
        server.enqueue(Scenario.http(503), Scenario.http(502), Scenario.http(500))
        val events = run(target(maxRetries = 2))
        assertEquals(listOf(1, 2), events.filterIsInstance<LlmEvent.Retrying>().map { it.attempt })
        val failed = events.last() as LlmEvent.Failed
        assertEquals(LlmError.ServerBusy(500, "fake error 500"), failed.error)
        assertFalse(failed.partial)
    }

    @Test
    fun `401 and 402 are not retried`() = runTest {
        server.enqueue(Scenario.http(401), Scenario.http(402))
        assertEquals(listOf(LlmEvent.Failed(LlmError.Auth(401, "fake error 401"), false)), run())
        assertEquals(listOf(LlmEvent.Failed(LlmError.Balance(402, "fake error 402"), false)), run())
    }

    @Test
    fun `R7 retries once without a field the server rejected`() = runTest {
        server.enqueue(
            Scenario.http(400, message = "Unrecognized key(s) in object: 'stream_options'"),
            Scenario.text("ok"),
        )
        assertEquals("ok", run().text())
        server.takeRequest()
        val retried = AppJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertFalse("stream_options" in retried)
    }

    @Test
    fun `FB-6 max_tokens is serialized only when set`() {
        fun json(r: ChatRequest) = AppJson.parseToJsonElement(AppJson.encodeToString(ChatRequest.serializer(), r)).jsonObject
        assertFalse("max_tokens" in json(request))
        assertEquals("2048", json(request.copy(maxTokens = 2048))["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `FB-6 R7 retries once without a rejected max_tokens`() = runTest {
        server.enqueue(Scenario.http(400, message = "Unsupported parameter: 'max_tokens'"), Scenario.text("ok"))
        assertEquals("ok", client.stream(target(), request.copy(maxTokens = 4096)).toList().text())
        assertTrue("max_tokens" in AppJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject)
        val retried = AppJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertFalse("max_tokens" in retried)
        assertTrue("stream_options" in retried)
    }

    @Test
    fun `R7 does not strip thinking for the DeepSeek reasoning message`() = runTest {
        server.enqueue(Scenario.http(400, message = "The reasoning_content in the thinking mode must be passed back"))
        val req = request.withReasoning(ReasoningWire.DEEPSEEK_THINKING, on = true, effort = null)
        val events = client.stream(target(), req).toList()
        assertTrue((events.single() as LlmEvent.Failed).error is LlmError.BadRequest)
    }

    @Test
    fun `error inside the stream is classified`() = runTest {
        server.enqueue(Scenario.raw("""data: {"error":{"message":"maximum context length is 10 tokens"}}""" + "\n\n"))
        assertEquals(
            listOf(LlmEvent.Failed(LlmError.ContextOverflow(null, "maximum context length is 10 tokens"), false)),
            run(),
        )
    }

    @Test
    fun `http base URL is refused unless allowed`() = runTest {
        val events = client.stream(target().copy(allowHttp = false), request).toList()
        // F-11: a setup problem, not a server 400 (no P-05 "turn features off" hint).
        val error = (events.single() as LlmEvent.Failed).error
        assertTrue("$error", error is LlmError.Config)
        assertEquals(com.ouor.localchat.core.model.ChatErrorKind.CONFIG, error.toChatError().kind)
    }

    @Test
    fun `complete collects the text`() = runTest {
        server.enqueue(Scenario.reasoning("hmm").text("Title"))
        assertEquals(LlmResult.Ok("Title", "stop", Usage(10, 5)), client.complete(target(), request))
    }

    @Test
    fun `listModels returns raw entries and throws classified errors`() = runTest {
        server.models = listOf("a", "b")
        assertEquals(listOf("a", "b"), client.listModels(target()).map { it["id"]!!.jsonPrimitive.content })

        MockWebServer().apply { start() }.use { mock ->
            mock.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"bad"}}""").build())
            mock.enqueue(MockResponse.Builder().body("""{"models":[]}""").build())
            val t = LlmTarget(mock.url("/v1").toString(), "k", 0, allowHttp = true)
            try { client.listModels(t); fail() } catch (e: LlmException) { assertEquals(LlmError.Auth(401, "bad"), e.error) }
            try { client.listModels(t); fail() } catch (e: LlmException) { assertTrue(e.error is LlmError.Protocol) }
        }
    }

    @Test
    fun `cancelling the collector cancels the call at once`(): Unit = runBlocking {
        val canceled = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) = canceled.countDown()
        }).build()
        MockWebServer().apply { start() }.use { mock ->
            // First delta, then the body trickles: the stream would take minutes to finish.
            val first = """data: {"choices":[{"delta":{"content":"a"}}]}""" + "\n\n"
            mock.enqueue(
                MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
                    .body(first + "data: {}\n\n".repeat(200))
                    .throttleBody(first.length.toLong(), 1, TimeUnit.SECONDS)
                    .build(),
            )
            val started = System.nanoTime()
            val event = withTimeout(5_000) {
                ChatCompletionsClient(http, AppJson).stream(LlmTarget(mock.url("/").toString(), null, 0, true), request).first()
            }
            assertEquals(LlmEvent.TextDelta("a"), event)
            assertTrue("call not cancelled", canceled.await(1, TimeUnit.SECONDS))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }
    }

    @Test
    fun `reasoning wire mapping`() {
        val deepOff = request.withReasoning(ReasoningWire.DEEPSEEK_THINKING, on = false, effort = "high")
        assertEquals(Thinking.DISABLED, deepOff.thinking)
        assertEquals(null, deepOff.reasoningEffort)
        val deepOn = request.withReasoning(ReasoningWire.DEEPSEEK_THINKING, on = true, effort = "max")
        assertEquals(Thinking.ENABLED to "max", deepOn.thinking to deepOn.reasoningEffort)
        assertEquals("low", request.withReasoning(ReasoningWire.EFFORT, true, "low").reasoningEffort)
        assertEquals(request, request.withReasoning(ReasoningWire.EFFORT, false, "low"))
        assertEquals(request, request.withReasoning(ReasoningWire.ALWAYS_ON, true, "low"))
        assertEquals(request, request.withReasoning(null, true, "low"))
        assertTrue(AppJson.encodeToString(ChatRequest.serializer(), deepOff).contains(""""thinking":{"type":"disabled"}"""))
    }

    @Test
    fun `U-6 a slow first byte is waited for, a stall after it is not`() = runBlocking {
        val mock = MockWebServer().apply { start() }
        val sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        // Server takes 600 ms before any byte; the client's read timeout is 300 ms.
        mock.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body(sse).headersDelay(600, TimeUnit.MILLISECONDS).build())
        // Headers at once, then 600 ms of silence before the body.
        mock.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body(sse).bodyDelay(600, TimeUnit.MILLISECONDS).build())
        val http = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        val slow = ChatCompletionsClient(http, AppJson, firstByteTimeoutMs = 5_000)
        val target = LlmTarget(mock.url("/v1").toString(), null, maxRetries = 0, allowHttp = true)
        try {
            val first = slow.stream(target, request).toList()
            assertEquals("ok", first.text())
            assertTrue(first.last() is LlmEvent.Finished)
            val second = slow.stream(target, request).toList().last()
            assertTrue("$second", second is LlmEvent.Failed)
        } finally {
            mock.close()
        }
    }}
