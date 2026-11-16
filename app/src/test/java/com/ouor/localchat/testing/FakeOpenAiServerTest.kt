package com.ouor.localchat.testing

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeOpenAiServerTest {

    private val http = OkHttpClient()

    private fun FakeOpenAiServer.post(): Pair<Int, String> {
        val request = Request.Builder()
            .url("${baseUrl}/chat/completions")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { it.code to it.body.string() }
    }

    @Test
    fun `scenarios are served in order`() = FakeOpenAiServer().use { server ->
        server.enqueue(
            Scenario.http(429, retryAfter = 1),
            Scenario.reasoning("hmm").text("Hi").toolCall("click", """{"ref":"e1"}"""),
        )

        assertEquals(429, server.post().first)
        val (code, body) = server.post()
        assertEquals(200, code)
        assertTrue(body.contains("\"reasoning_content\":\"hmm\""))
        assertTrue(body.contains("\"name\":\"click\""))
        assertTrue(body.contains("\"finish_reason\":\"tool_calls\""))
        assertTrue(body.trimEnd().endsWith("data: [DONE]"))
        assertEquals("/v1/chat/completions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `dropAfter ends without finish or DONE`() = FakeOpenAiServer().use { server ->
        server.enqueue(Scenario.text("a").text("b").text("c").dropAfter(2))
        val body = runCatching { server.post().second }.getOrDefault("")
        assertFalse(body.contains("[DONE]"))
        assertFalse(body.contains("\"finish_reason\":\"stop\""))
    }

    @Test
    fun `replays the recorded DeepSeek stream`() = FakeOpenAiServer().use { server ->
        val sse = javaClass.getResource("/fixtures/deepseek-flash-reasoning-toolcall.sse")!!.readText()
        server.enqueue(Scenario.raw(sse))
        assertEquals(sse, server.post().second)
    }

    @Test
    fun `lists models`() = FakeOpenAiServer().use { server ->
        server.models = listOf("a", "b")
        val body = http.newCall(Request.Builder().url("${server.baseUrl}/models").build()).execute()
            .use { it.body.string() }
        assertTrue(body.contains("\"id\":\"b\""))
    }
}
