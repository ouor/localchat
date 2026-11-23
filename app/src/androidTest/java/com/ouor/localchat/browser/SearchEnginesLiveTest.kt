package com.ouor.localchat.browser

import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.MainActivity
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * search.js against the live engines in this WebView (R8). Needs network; a CAPTCHA/consent page skips.
 * Results are logged under tag SearchLive so markup changes can be diagnosed from logcat.
 * Skipped unless run with instrumentation argument `liveWeb=true` (track-c.md 7, QA command).
 */
@RunWith(AndroidJUnit4::class)
class SearchEnginesLiveTest {

    @get:Rule val screen = ScreenOnRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var tab: BrowserTab

    @Before
    fun setUp() {
        assumeTrue("live web tests: pass liveWeb=true", InstrumentationRegistry.getArguments().getString("liveWeb") == "true")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { a ->
            val host = FrameLayout(a)
            a.addContentView(host, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            tab = BrowserTab(1, a.applicationContext, object : BrowserTab.Events {})
            tab.contextWrapper.baseContext = a
            host.addView(tab.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    @After
    fun tearDown() {
        if (!::scenario.isInitialized) return
        scenario.onActivity { tab.destroy() }
        scenario.close()
    }

    private fun check(engine: SearchEngine, query: String) = runBlocking<Unit> {
        val since = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) { tab.load(SearchEngines.url(engine, query)) }
        val ready = tab.awaitReady(since) // no fixed sleep: the page is read once it is quiet (tools-redesign 2-1)
        Log.i(TAG, "$engine ready=$ready in ${SystemClock.uptimeMillis() - since} ms")
        assumeTrue("network", ready != Readiness.FAILED)
        val page = PageScript.search(tab, engine)
        val results = page.results
        Log.i(TAG, "$engine ${tab.url} blocked=${page.blocked} -> ${results.size}: " + results.joinToString(" | ") { "${it.title} <${it.url}> «${it.snippet?.take(60)}»" })
        if (results.isEmpty()) Log.i(TAG, "$engine empty page: " + tab.evaluate(PAGE_SHAPE, 5_000))
        assumeTrue("blocked page on $engine", !page.blocked)
        assertTrue("$engine results: $results", results.size >= 3)
        results.forEach { r ->
            assertTrue(r.url, r.url.startsWith("http"))
            assertTrue("$engine redirect left in ${r.url}", listOf("/url?", "bing.com/ck/", "duckduckgo.com/l/").none { it in r.url })
            assertTrue(r.title.isNotBlank())
        }
        assertTrue("$engine snippets", results.count { !it.snippet.isNullOrBlank() } >= results.size / 2)
    }

    @Test fun duckDuckGo() = check(SearchEngine.DUCKDUCKGO, "kotlin coroutines")
    @Test fun google() = check(SearchEngine.GOOGLE, "kotlin coroutines")
    @Test fun bing() = check(SearchEngine.BING, "kotlin coroutines")
    @Test fun naver() = check(SearchEngine.NAVER, "코틀린 코루틴")

    private companion object {
        const val TAG = "SearchLive"
        /** What an empty result page looks like, for diagnosing markup changes. */
        const val PAGE_SHAPE = "document.readyState + ' h3=' + document.querySelectorAll('h3').length + ' text=' + document.body.innerText.slice(0, 300)"
    }
}
