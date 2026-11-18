package com.ouor.localchat.browser

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.testing.Scenario
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * web_search and read against the live web through the whole tool path (tools-redesign 7). Logged under tag
 * WebSearchLive. Skipped unless run with instrumentation argument `liveWeb=true`.
 */
@RunWith(AndroidJUnit4::class)
class WebSearchLiveTest : ToolFlowBase() {

    override fun respond(path: String, request: RecordedRequest): MockResponse? = null

    @Before
    fun live() = assumeTrue("live web tests: pass liveWeb=true", InstrumentationRegistry.getArguments().getString("liveWeb") == "true")

    private fun search(query: String) {
        llm.enqueue(Scenario.toolCall("web_search", """{"query":"$query"}"""), Scenario.text("done"))
        val start = SystemClock.uptimeMillis()
        val reply = awaitEnd(send("search", browserOn = false, searchOn = true))
        val ms = SystemClock.uptimeMillis() - start
        val result = reply.results().single()
        Log.i(TAG, "\"$query\" in $ms ms: ${result.content.take(600)}")
        assumeTrue("all engines blocked", "bot check or consent page" !in result.content)
        assertEquals(result.content, ToolCallState.DONE, reply.calls().single().state)
        assertTrue(result.content, (result.summary?.toIntOrNull() ?: 0) >= 5)
        assertTrue("$ms ms", ms < 20_000)
    }

    @Test fun englishQuery() = search("kotlin coroutines tutorial")

    @Test fun koreanQuery() = search("서울 날씨")

    @Test
    fun readWikipediaInParts() {
        llm.enqueue(Scenario.toolCall("read", """{"url":"https://en.wikipedia.org/wiki/Kotlin_(programming_language)"}"""), Scenario.text("done"))
        val start = SystemClock.uptimeMillis()
        val reply = awaitEnd(send("read", browserOn = false, searchOn = true))
        val text = reply.results().single().content
        Log.i(TAG, "wikipedia in ${SystemClock.uptimeMillis() - start} ms, ${text.length} chars: ${text.take(300)}")
        assertEquals(text.take(300), ToolCallState.DONE, reply.calls().single().state)
        assertTrue(text.take(300), text.length >= 4_000)
        val parts = Regex("\\(part 1 of (\\d+)\\)").find(text)?.groupValues?.get(1)?.toInt() ?: 1
        assertTrue(text.take(300), parts >= 2)
    }

    private companion object {
        const val TAG = "WebSearchLive"
    }
}
