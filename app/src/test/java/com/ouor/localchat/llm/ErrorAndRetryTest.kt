package com.ouor.localchat.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

class ErrorAndRetryTest {

    private fun body(message: String) = """{"error":{"message":"$message","type":"x","code":null}}"""

    @Test
    fun `status codes map to E-01 to E-03`() {
        assertEquals(LlmError.Auth(401, "bad key"), ErrorClassifier.classify(401, body("bad key")))
        assertTrue(ErrorClassifier.classify(403, null) is LlmError.Auth)
        assertEquals(LlmError.Balance(402, "Insufficient Balance"), ErrorClassifier.classify(402, body("Insufficient Balance")))
        assertTrue(ErrorClassifier.classify(429, null) is LlmError.RateLimited)
        assertTrue(ErrorClassifier.classify(503, "<html>busy</html>") is LlmError.ServerBusy)
        listOf(400, 404, 413, 422).forEach { assertTrue(ErrorClassifier.classify(it, body("nope")) is LlmError.BadRequest) }
    }

    @Test
    fun `context overflow is recognised by message, any case`() {
        listOf(
            "This model's maximum context length is 8192 tokens",
            "Context Length exceeded",
            "too many tokens in prompt",
            "context_length_exceeded",
        ).forEach { assertTrue(it, ErrorClassifier.classify(400, body(it)) is LlmError.ContextOverflow) }
    }

    @Test
    fun `server message comes from common body shapes`() {
        assertEquals("a", ErrorClassifier.classify(400, """{"error":"a"}""").serverMessage)
        assertEquals("b", ErrorClassifier.classify(400, """{"message":"b"}""").serverMessage)
        assertEquals("plain text", ErrorClassifier.classify(400, " plain text ").serverMessage)
        assertNull(ErrorClassifier.classify(400, "").serverMessage)
    }

    @Test
    fun `exceptions map to timeout, network, protocol`() {
        assertEquals(LlmError.Timeout, ErrorClassifier.classify(SocketTimeoutException()))
        assertEquals(LlmError.Network, ErrorClassifier.classify(IOException("reset")))
        assertTrue(ErrorClassifier.classify(IllegalStateException("Invalid content-type")) is LlmError.Protocol)
        assertTrue(ErrorClassifier.classify(HttpStatusException(429, null, "1")) is LlmError.RateLimited)
    }

    @Test
    fun `in-stream errors use a numeric code as status`() {
        fun e(s: String) = ErrorClassifier.classifyStreamError(Json.parseToJsonElement(s))
        assertEquals(LlmError.RateLimited(429, "slow down"), e("""{"message":"slow down","code":429}"""))
        assertTrue(e("""{"message":"maximum context length is 10"}""") is LlmError.ContextOverflow)
        assertEquals(LlmError.Protocol("boom"), e(""""boom""""))
    }

    @Test
    fun `only E-03 kinds are retryable`() {
        listOf(LlmError.RateLimited(429, null), LlmError.ServerBusy(500, null), LlmError.Timeout, LlmError.Network, LlmError.EmptyResponse)
            .forEach { assertTrue(RetryPolicy.isRetryable(it)) }
        listOf(LlmError.Auth(401, null), LlmError.Balance(402, null), LlmError.BadRequest(400, null), LlmError.ContextOverflow(400, null), LlmError.Protocol(null))
            .forEach { assertFalse(RetryPolicy.isRetryable(it)) }
    }

    @Test
    fun `backoff doubles from half a second to ten seconds with ten percent jitter`() {
        val expected = listOf(500.0, 1000.0, 2000.0, 4000.0, 8000.0, 10_000.0, 10_000.0)
        expected.forEachIndexed { i, base ->
            repeat(50) {
                val d = RetryPolicy.delayMs(i + 1, null, Random(it))
                assertTrue("attempt ${i + 1}: $d", d >= base * 0.9 - 1 && d <= base * 1.1 + 1)
            }
        }
    }

    @Test
    fun `retry-after wins and is capped at sixty seconds`() {
        assertEquals(3000L, RetryPolicy.delayMs(1, 3000, Random(0)))
        assertEquals(60_000L, RetryPolicy.delayMs(1, 600_000, Random(0)))
    }

    @Test
    fun `retry-after parses seconds and http dates`() {
        assertEquals(2000L, RetryPolicy.parseRetryAfter("2", 0))
        assertEquals(1500L, RetryPolicy.parseRetryAfter("1.5", 0))
        val now = java.time.ZonedDateTime.parse("2026-11-16T10:00:00Z").toInstant().toEpochMilli()
        assertEquals(30_000L, RetryPolicy.parseRetryAfter("Mon, 16 Nov 2026 10:00:30 GMT", now))
        assertEquals(0L, RetryPolicy.parseRetryAfter("Mon, 16 Nov 2026 09:00:00 GMT", now))
        assertNull(RetryPolicy.parseRetryAfter("soon", now))
        assertNull(RetryPolicy.parseRetryAfter(null, now))
    }
}
