package com.ouor.localchat.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.testing.Scenario
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The shared engine loop (EngineSearch, A-M3 / B-m10) without the real engines: every WebView request goes to a local
 * proxy that refuses the engines' tunnels, after [delayMs]. So each engine load fails on the network, and the loop's
 * retry, engine switch, stop rule and 30 s budget run as they would on a dead network.
 */
@RunWith(AndroidJUnit4::class)
class EngineSearchTest : ToolFlowBase() {

    private lateinit var proxy: MockWebServer
    private val tunnels: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var delayMs = 0L
    private val closing = CountDownLatch(1)

    override fun respond(path: String, request: RecordedRequest): MockResponse? = null

    @Before
    fun startProxy() {
        assumeTrue("proxy override", WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
        // The proxy calls need WebView started; this starts it.
        InstrumentationRegistry.getInstrumentation().run { runOnMainSync { WebView(targetContext).destroy() } }
        proxy = MockWebServer()
        proxy.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                tunnels += request.target.substringBefore(':')
                closing.await(delayMs, TimeUnit.MILLISECONDS)
                return MockResponse.Builder().code(502).build()
            }
        }
        proxy.start()
        val set = CountDownLatch(1)
        ProxyController.getInstance().setProxyOverride(
            ProxyConfig.Builder().addProxyRule("127.0.0.1:${proxy.port}").addBypassRule("localhost").addBypassRule("127.0.0.1").build(),
            { it.run() }, { set.countDown() },
        )
        assertTrue(set.await(5, TimeUnit.SECONDS))
    }

    @After
    fun stopProxy() {
        if (!::proxy.isInitialized) return
        closing.countDown()
        val cleared = CountDownLatch(1)
        ProxyController.getInstance().clearProxyOverride({ it.run() }, { cleared.countDown() })
        cleared.await(5, TimeUnit.SECONDS)
        proxy.close()
    }

    @Test
    fun aDeadNetworkRetriesOnceThenTriesTheNextEngineThenStops() {
        llm.enqueue(Scenario.toolCall("web_search", """{"query":"lamps"}"""), Scenario.text("done"))
        val reply = awaitEnd(send("search", browserOn = false, searchOn = true))
        assertEquals(ReplyStatus.DONE, reply.status)
        val result = reply.results().single()
        assertEquals(result.modelText, ToolCallState.FAILED, reply.calls().single().state)
        assertTrue(result.modelText, "Search failed:" in result.lead)
        // Each engine is loaded twice (one retry); two engines failing on the network ends the search.
        val hosts = tunnels.distinct()
        assertEquals(tunnels.toString(), 2, hosts.size)
        assertEquals(tunnels.toString(), listOf(hosts[0], hosts[0], hosts[1], hosts[1]), tunnels.toList())
        assertEquals("the search tab stays, like any tab", 1, browser.tabs.value.size)
    }

    @Test
    fun noNewLoadStartsPastTheBudget() {
        // 13 s per load: DuckDuckGo twice (27.5 s with the retry pause), the next engine once (40.5 s), then no retry.
        delayMs = 13_000
        llm.enqueue(Scenario.toolCall("open", """{"url":"lamps"}"""), Scenario.text("done"))
        val reply = awaitEnd(send("search", browserOn = true), timeoutMs = 90_000)
        val result = reply.results().single()
        assertTrue(result.modelText, "Search timed out after trying" in result.lead)
        assertEquals(tunnels.toString(), 3, tunnels.size)
    }
}
