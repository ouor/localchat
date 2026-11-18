package com.ouor.localchat.browser

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.core.model.SearchEngine
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.testing.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Page readiness, load errors and their retry, dialogs and downloads through the real tools (tools-redesign 2, 7;
 * cases E2-E7 of tools-audit-browser.md). Wait times are logged under tag PageReady.
 */
@RunWith(AndroidJUnit4::class)
class PageReadyTest : ToolFlowBase() {

    private val flaky = AtomicInteger()

    override fun respond(path: String, request: RecordedRequest): MockResponse? = when (path) {
        "/a" -> html("""<!doctype html><title>A</title><a id="go" href="/slow">Slow page</a>""")
        "/slow" -> { Thread.sleep(1_500); html("<!doctype html><title>Slow</title><p>Slow page body text</p>") } // TTFB 1.5 s (E2)
        "/jsredir" -> html("""<!doctype html><title>Between</title><script>setTimeout(function () { location.replace('/final') }, 300)</script>""")
        "/final" -> html("<!doctype html><title>Final</title><p>Final page body text</p>")
        "/late" -> html("""<!doctype html><title>Late</title><div id="m"></div>
            <script>setTimeout(function () { document.getElementById('m').textContent = 'LATE-TEXT ' + 'word '.repeat(60) }, 800)</script>""")
        "/clock" -> html("""<!doctype html><title>Clock</title><p>Ticker page with a long enough introduction. ${"Lorem ipsum dolor sit amet. ".repeat(10)}</p>
            <p id="t"></p><script>setInterval(function () { document.getElementById('t').textContent = Date.now() }, 100)</script>""")
        "/static" -> html("<!doctype html><title>Static</title><p>${"Static text. ".repeat(40)}</p>")
        "/flaky" -> if (flaky.incrementAndGet() == 1) html("busy", 503) else html("<!doctype html><title>Flaky</title><p>Flaky page body text</p>")
        "/down" -> html("down", 503)
        "/file.pdf" -> MockResponse.Builder().addHeader("Content-Type", "application/pdf")
            .addHeader("Content-Disposition", "attachment; filename=\"file.pdf\"").body("%PDF-1.4 fake").build()
        "/dialogs" -> html("""<!doctype html><title>Dialogs</title><p id="r"></p>
            <button id="alert" onclick="alert('Saved!'); document.getElementById('r').textContent = 'after alert'">Save</button>
            <button id="confirm" onclick="document.getElementById('r').textContent = 'confirm=' + confirm('Really leave?')">Leave</button>""")
        "/post" -> html("""<!doctype html><title>Post</title><form method="post" action="/submit"><input id="m" name="message"><button>Send</button></form>""")
        "/blocked" -> html("<!doctype html><title>Sorry</title><p>Our systems have detected unusual traffic from your computer network.</p>")
        "/ddg" -> html("<!doctype html><title>DDG</title>" + (1..3).joinToString("") {
            """<div class="result"><a class="result__a" href="https://example$it.test/page">Result $it title</a><a class="result__snippet">Snippet $it</a></div>"""
        })
        else -> null
    }

    /** Runs one tool call to the end and returns its result text and state; [answer] handles each pause. */
    private fun run(name: String, args: String, answer: (Pause, String) -> Unit = { _, _ -> error("unexpected pause") }): Pair<String, ToolCallState> {
        llm.enqueue(Scenario.toolCall(name, args), Scenario.text("done"))
        val sid = send("go")
        while (true) {
            val st = awaitRun(sid, "pause or end") { it == null || it.phase is com.ouor.localchat.chat.run.RunPhase.Paused }
            if (st == null) break
            answer((st.phase as com.ouor.localchat.chat.run.RunPhase.Paused).pause, sid)
            awaitRun(sid, "resume") { it?.phase !is com.ouor.localchat.chat.run.RunPhase.Paused }
        }
        val reply = awaitEnd(sid)
        return reply.results().single().content to reply.calls().single().state
    }

    /** One readiness wait on a hidden tab, from the load call: (readiness, ms). */
    private fun measure(path: String): Pair<Readiness, Long> = runBlocking {
        val since = SystemClock.uptimeMillis()
        val id = withContext(Dispatchers.Main) { browser.openAiTab("m", url(path), hidden = true) }
        try {
            val tab = withContext(Dispatchers.Main) { browser.tab(id) }
            val r = tab.awaitReady(since)
            val ms = SystemClock.uptimeMillis() - since
            val load = tab.evaluate("performance.timing.loadEventEnd - performance.timing.navigationStart", 5_000)
            Log.i(TAG, "$path: $r in $ms ms (page load $load ms)")
            r to ms
        } finally {
            withContext(Dispatchers.Main) { browser.close(id) }
        }
    }

    @Test
    fun e2_aClickToASlowFirstByteWaitsForTheNewPage() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/a")}"}"""),
            Scenario.toolCall("click", """{"ref":"#go"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("go"))
        val click = reply.results()[1].content
        assertTrue(click, "→ new page." in click && "/slow" in click && "Slow page body text" in click)
    }

    @Test
    fun e3_anUnknownHostFailsAfterOneRetryWithoutTheErrorPage() {
        val (text, state) = run("read", """{"url":"https://no-such-host.invalid/"}""")
        assertEquals(text, ToolCallState.FAILED, state)
        assertTrue(text, "the site's address was not found" in text && "Already retried once" in text)
        assertFalse(text, "chromewebdata" in text || "웹페이지" in text)
    }

    @Test
    fun e4_a503IsRetriedOnce() {
        val (ok, _) = run("read", """{"url":"${url("/flaky")}"}""")
        assertTrue(ok, "Flaky page body text" in ok)
        assertEquals(2, pageHits.count { it == "/flaky" })
        val (down, _) = run("read", """{"url":"${url("/down")}"}""")
        assertTrue(down, "HTTP 503" in down)
        assertEquals(2, pageHits.count { it == "/down" })
    }

    @Test
    fun e5_aScriptRedirectRightAfterLoadIsFollowed() {
        val (text, _) = run("browse", """{"url":"${url("/jsredir")}"}""")
        assertTrue(text, "Page: Final — ${url("/final")}" in text)
    }

    @Test
    fun e7_textAddedAfterLoadIsRead() {
        val (r, ms) = measure("/late")
        assertEquals(Readiness.READY, r)
        assertTrue("$ms ms", ms < 3_000)
        val (text, _) = run("read", """{"url":"${url("/late")}"}""")
        assertTrue(text, "LATE-TEXT" in text)
    }

    @Test
    fun aPageThatNeverStopsChangingIsReadWithinTheCap() {
        val (r, ms) = measure("/clock")
        assertEquals(Readiness.CHANGING, r)
        assertTrue("$ms ms", ms <= TabRules.SETTLE_CAP + 2_000)
        val (text, _) = run("read", """{"url":"${url("/clock")}"}""")
        assertTrue(text, "(page still changing; content may be incomplete)" in text && "Ticker page" in text)
    }

    @Test
    fun aStaticPageAddsLittleWaitAfterItsLoad() {
        measure("/static") // warm-up: the first WebView load pays process start-up
        val times = (1..3).map { measure("/static").also { (r, _) -> assertEquals(Readiness.READY, r) }.second }
        Log.i(TAG, "static: $times ms")
        // Quiet window (500 ms) plus a local load and polling; the design's bound is 1 s after the load.
        assertTrue("$times", times.all { it <= 1_500 })
    }

    @Test
    fun dialogsAreReportedInTheResult() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/dialogs")}"}"""),
            Scenario.toolCall("click", """{"ref":"#alert"}"""),
            Scenario.toolCall("click", """{"ref":"#confirm"}"""),
            Scenario.text("done"),
        )
        val sid = send("dialogs")
        // The confirm is the user's: shown in the panel; the test answers Cancel there.
        val prompt = runBlocking { withTimeout(30_000) { browser.prompts.first { it.isNotEmpty() } }.first() }
        val tabId = browser.tabs.value.single().id
        runBlocking(Dispatchers.Main) { browser.tab(tabId).answerDialog(false, null) }
        assertTrue(prompt.toString(), "Really leave?" in prompt.toString())
        val reply = awaitEnd(sid)
        val (alert, confirm) = reply.results().drop(1).map { it.content }
        assertTrue(alert, "The page showed an alert: \"Saved!\"." in alert && "after alert" in alert)
        assertTrue(confirm, "The page asked (confirm): \"Really leave?\" — the user chose Cancel." in confirm && "confirm=false" in confirm)
    }

    @Test
    fun aDownloadLinkIsNotRead() {
        val (text, state) = run("read", """{"url":"${url("/file.pdf")}"}""")
        assertEquals(ToolCallState.FAILED, state)
        assertTrue(text, "This URL is a file download (application/pdf)" in text)
    }

    @Test
    fun enterInAPostFormAsksFirst() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/post")}"}"""),
            Scenario.toolCall("type", """{"ref":"#m","text":"hi\n"}"""),
            Scenario.text("done"),
        )
        val sid = send("post")
        val p = awaitPause(sid) as Pause.Approval
        assertEquals("POST_FORM", p.request.reason?.name)
        engine.resolve(sid, Resolution.ApprovalAnswer(p.id, allow = false))
        assertEquals(ToolCallState.DENIED, awaitEnd(sid).calls()[1].state)
        assertTrue(pageHits.none { it == "/submit" })
    }

    @Test
    fun searchPagesAreTold_blockedOrExtracted() = runBlocking<Unit> {
        suspend fun page(path: String) = withContext(Dispatchers.Main) {
            val since = SystemClock.uptimeMillis()
            val id = browser.openAiTab("s", url(path), hidden = true)
            val tab = browser.tab(id)
            tab.awaitReady(since)
            PageScript.search(tab, SearchEngine.DUCKDUCKGO).also { browser.close(id) }
        }
        assertTrue(page("/blocked").blocked)
        val ok = page("/ddg")
        assertFalse(ok.blocked)
        assertEquals(listOf("https://example1.test/page", "https://example2.test/page", "https://example3.test/page"), ok.results.map { it.url })
    }

    private companion object {
        const val TAG = "PageReady"
    }
}
