package com.ouor.localchat.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.di.PolicyModule
import com.ouor.localchat.policy.CookieMaskPolicy
import com.ouor.localchat.policy.MoneyConfirmPolicy
import com.ouor.localchat.policy.Policies
import com.ouor.localchat.testing.Scenario
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.browser.AskUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tool flows end to end (browser-layers 10-2) with an empty policy chain; the harness is [ToolFlowBase]. */
@RunWith(AndroidJUnit4::class)
class ToolFlowE2eTest : ToolFlowBase() {

    override fun respond(path: String, request: RecordedRequest): MockResponse? {
        if (path == "/long-read") {
            return html("<!doctype html><title>Long read</title><main>" + (0 until 40).joinToString("") { "<p>Para $it ${"x".repeat(950)}</p>" } + "</main>")
        }
        if (path == "/redir") return MockResponse.Builder().code(302).addHeader("Location", "/long-read").build()
        if (path == "/file.txt") {
            return MockResponse.Builder().addHeader("Content-Type", "text/plain")
                .addHeader("Content-Disposition", "attachment; filename=\"notes.txt\"").body("file body").build()
        }
        val body = PAGES[path] ?: return null
        val b = MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8")
        if (path == "/start") b.addHeader("Set-Cookie", "sid=$COOKIE; Path=/")
        return b.body(body).build()
    }

    @Test
    fun openClickNavigateBackListsEachPage() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"Next page\"")}"}"""),
            Scenario.toolCall("navigate", """{"to":"back"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("go"))
        val t = reply.results().map { it.modelText }
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals(reply.results().joinToString { it.modelText }, List(3) { ToolCallState.DONE }, reply.calls().map { it.state })
        val tab = browser.tabs.value.single()
        assertTrue(t[0], t[0].startsWith("Opened tab ${tab.id}.\n<untrusted_page_content>\nPage: Start — "))
        assertTrue(t[0], "(tab ${tab.id})" in t[0] && "link \"Next page\" [ref=${tab.id}e" in t[0])
        assertTrue(t[1], Regex("^Clicked ${tab.id}e\\d+ → new page\\.").containsMatchIn(t[1]))
        assertTrue(t[2], t[2].startsWith("Went back → new page.") && "Page: Start" in t[2])
        // The front stays where the user left it: nothing the AI did picked a tab.
        assertEquals(null, browser.front.value)
    }

    /** QA B1 on the real site (liveWeb=true): HN /newest, scrolled to the bottom, then More at the bottom, drawer closed. */
    @Test
    fun liveHnMoreInAHiddenTab() {
        org.junit.Assume.assumeTrue("liveWeb", InstrumentationRegistry.getArguments().getString("liveWeb") == "true")
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"https://news.ycombinator.com/newest"}"""),
            *Array(3) { Scenario.toolCall("scroll", "{}") },
            Scenario.toolCall("look", "{}"),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"More\"")}"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("more"), 90_000)
        val t = reply.results().map { it.modelText }
        assertTrue(t[5], "→ new page." in t[5])
        assertTrue(browser.tabs.value.single().url, "next=" in browser.tabs.value.single().url)
    }

    /** QA B2/B3 on real sites (liveWeb=true): a public PDF read in a new tab, then a download link on a page. */
    @Test
    fun liveAPdfAndADownloadLink() {
        org.junit.Assume.assumeTrue("liveWeb", InstrumentationRegistry.getArguments().getString("liveWeb") == "true")
        llm.enqueue(
            Scenario.toolCall("read", """{"url":"https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf"}"""),
            Scenario.toolCall("open", """{"url":"https://filesamples.com/formats/csv"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"sample1.csv\"")}"}"""),
            Scenario.toolCall("look", "{}"),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("files"), 120_000)
        val t = reply.results().map { it.modelText }
        t.forEachIndexed { i, x -> x.chunked(3000).forEach { android.util.Log.i("LIVE", "$i: $it") } }
        assertTrue(t[0], Regex("""saved to Downloads as dummy.*\.pdf\. Its text cannot be read in this app\. Tab \d+ only downloaded it and was closed\.""").containsMatchIn(t[0]))
        assertTrue(t[1], "link \"sample4.csv\" [download]" in t[1])
        // The site's ad script sometimes takes the first click on a link for a full-page ad (#google_vignette).
        assertTrue(t[2], "This is a file" in t[2] || ("page updated" in t[2] && "#google_vignette" in t[2]))
        assertEquals("the page's tab stays", ToolCallState.DONE, reply.calls()[3].state)
    }

    /** QA R-B1b on the real site (liveWeb=true): a link in the middle of a long article in a hidden tab, five times. */
    @Test
    fun liveWikipediaJavaToGosling() {
        org.junit.Assume.assumeTrue("liveWeb", InstrumentationRegistry.getArguments().getString("liveWeb") == "true")
        repeat(5) { n ->
            llm.enqueue(
                Scenario.toolCall("open", """{"url":"https://en.wikipedia.org/wiki/Java_(programming_language)"}"""),
                Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"James Gosling\"")}"}"""),
                Scenario.text("done"),
            )
            val reply = awaitEnd(send("gosling $n"), 90_000)
            awaitTitleRequest()
            val t = reply.results().map { it.modelText }
            android.util.Log.i("RB1", "live wiki $n: ${t[1].lineSequence().first()} | ${browser.tabs.value.maxBy { it.id }.url}")
            assertTrue(t[1], "Oracle" !in browser.tabs.value.maxBy { it.id }.url)
            assertTrue(t[1], "James_Gosling" in browser.tabs.value.maxBy { it.id }.url || "nothing was pressed" in t[1])
        }
    }

    /**
     * QA R-B1 / R-B1b: 20 clicks each on a hidden tab's bottom link and on a link in the middle of a long article. The
     * tap may be refused (it says so), but never presses another element.
     */
    @Test
    fun hiddenTabClicksNeverPressAnotherElement() {
        for ((path, line, target) in TARGETS) {
            val n = clicks(path, line, target, chats = 4)
            assertEquals("$path: another element pressed", 0, n.wrong)
            assertEquals("$path: pressed with no effect", 0, n.nothing)
            assertEquals("$path: 20 clicks", 20, n.touch + n.script + n.refused)
        }
    }

    /**
     * Re-QA 2 (devphone02): the user's keyboard over the app also lies over a parked tab, so the page's visual
     * viewport is offset from its layout viewport and every tap landed that far down. Taps go by the visual viewport.
     */
    @Test
    fun hiddenTabClicksLandWithTheKeyboardUp() {
        keyboardUp()
        for ((path, line, target) in TARGETS) {
            val n = clicks(path, line, target, chats = 1)
            assertEquals("$path: pressed by touch", 5, n.touch)
        }
    }

    /** The user's keyboard is up over the app (an EditText of the activity has it), as after typing a request. */
    private fun keyboardUp() {
        onActivity { a ->
            val e = android.widget.EditText(a)
            a.addContentView(e, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            e.requestFocus()
            androidx.core.view.WindowCompat.getInsetsController(a.window, e).show(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        while (!onActivity { a -> androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }) {
            check(android.os.SystemClock.uptimeMillis() < end) { "keyboard up" }
            Thread.sleep(100)
        }
    }

    /**
     * Re-QA 2 on real sites (liveWeb=true), hidden tab with the keyboard up: HN /newest More, Wikipedia Java's
     * "James Gosling" (infobox), an APG tab (top of the page), five times each. Logs how each click went (tag RB1).
     */
    @Test
    fun liveHiddenTabClicksWithTheKeyboardUp() {
        org.junit.Assume.assumeTrue("liveWeb", InstrumentationRegistry.getArguments().getString("liveWeb") == "true")
        keyboardUp()
        data class Site(val url: String, val line: String, val toEnd: Boolean = false, val reached: (String, String) -> Boolean)
        val sites = listOf(
            Site("https://news.ycombinator.com/newest", "link \"More\"", toEnd = true) { _, u -> "next=" in u },
            Site("https://en.wikipedia.org/wiki/Java_(programming_language)", "link \"James Gosling\"") { _, u -> "James_Gosling" in u },
            Site("https://www.w3.org/WAI/ARIA/apg/patterns/tabs/examples/tabs-automatic/", "tab \"Carl Andersen\"") { t, _ -> "tab \"Carl Andersen\" [selected]" in t },
        )
        var wrong = 0
        for (site in sites) {
            var touch = 0; var script = 0; var other = 0
            repeat(5) { n ->
                // More is listed once the page's end is on screen (the list carries the screen whole).
                val toEnd = if (site.toEnd) List(3) { Scenario.toolCall("scroll", "{}") } else emptyList()
                llm.enqueue(
                    Scenario.toolCall("open", """{"url":"${site.url}"}"""),
                    *toEnd.toTypedArray(),
                    Scenario.toolCall("click", """{"ref":"${Scenario.ref(site.line)}"}"""),
                    Scenario.text("done"),
                )
                val reply = awaitEnd(send("live $n"), 90_000)
                awaitTitleRequest()
                val t = reply.results().last().modelText
                val u = browser.tabs.value.maxBy { it.id }.url
                when {
                    site.reached(t, u) -> if ("pressed by script" in t) script++ else touch++
                    "nothing was pressed" in t || "did not reach" in t -> other++
                    else -> { wrong++; android.util.Log.e("RB1", "${site.url}: ${t.take(400)} | $u") }
                }
                runBlocking(Dispatchers.Main) { browser.tabs.value.forEach { browser.close(it.id) } }
            }
            android.util.Log.i("RB1", "live ${site.url}: touch=$touch script=$script refused=$other")
        }
        assertEquals("another element pressed", 0, wrong)
    }

    private class Clicks(var touch: Int = 0, var script: Int = 0, var refused: Int = 0, var wrong: Int = 0, var nothing: Int = 0)

    /** [chats] chats of 5 (open [path] in a hidden tab + click [line]); how each click went (tag RB1). */
    private fun clicks(path: String, line: String, target: String, chats: Int): Clicks {
        val n = Clicks()
        repeat(chats) {
            llm.enqueue(
                *(0 until 5).flatMap {
                    listOf(Scenario.toolCall("open", """{"url":"${url(path)}"}"""), Scenario.toolCall("click", """{"ref":"${Scenario.ref(line)}"}"""))
                }.toTypedArray(),
                Scenario.text("done"),
            )
            val reply = awaitEnd(send("click"), 120_000)
            awaitTitleRequest()
            reply.results().filterIndexed { i, _ -> i % 2 == 1 }.map { it.modelText }.forEach { t ->
                when {
                    "did not reach" in t || "would have landed" in t -> n.refused++
                    "→ new page" in t && target in t -> if ("pressed by script" in t) n.script++ else n.touch++
                    "→ new page" in t -> { n.wrong++; android.util.Log.e("RB1", t.take(400)) }
                    else -> { n.nothing++; android.util.Log.e("RB1", t.take(400)) }
                }
            }
            runBlocking(Dispatchers.Main) { browser.tabs.value.forEach { browser.close(it.id) } }
        }
        android.util.Log.i("RB1", "$path: touch=${n.touch} script=${n.script} refused=${n.refused} wrong=${n.wrong} nothing=${n.nothing}")
        return n
    }

    /** QA 재QA 3: a tab widget that only flips hidden/aria-selected is a page change, not "no visible change". */
    @Test
    fun aTabPanelSwitchIsAPageUpdate() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/tabs")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("tab \"Two\"")}"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("tabs"))
        val r = reply.results()[1].modelText
        assertTrue(r, "page updated" in r)
    }

    /** QA B1 (2): a point another element covers is not pressed; the result names what is there. */
    @Test
    fun aCoveredClickIsNotPressedAndSaysWhatIsThere() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/covered")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("button \"Press\"")}"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("press"))
        val r = reply.results()[1]
        assertEquals(r.modelText, ToolCallState.FAILED, reply.calls()[1].state)
        assertTrue(r.modelText, "did not reach" in r.modelText && "<div#cover> \"Cookie wall\"" in r.modelText)
        val tab = runBlocking(Dispatchers.Main) { browser.tab(browser.tabs.value.single().id) }
        assertEquals("\"Press\"", runBlocking { tab.evaluate("document.querySelector('button').textContent", 5_000) })
    }

    @Test
    fun scrollingAFeedShowsNewLinesAndNoFalseEnd() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/feed")}"}"""),
            Scenario.toolCall("scroll", "{}"),
            Scenario.toolCall("scroll", "{}"),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("feed"))
        val t = reply.results().map { it.modelText }
        assertTrue(t[1], t[1].startsWith("Scrolled down"))
        // QA B7: the feed grew while the scroll waited, and the result says so with the settled position.
        assertTrue(t[1], Regex("""^Scrolled down → the page loaded more; \d+% down the page \(the page grew longer: \d+ → \d+ px\)\.""").containsMatchIn(t[1]))
        assertFalse(t[2], t[2].startsWith("Reached the bottom"))
        // The items the page loaded late (400 ms after the scroll) are listed, text lines included.
        assertTrue(t[2], "Post 25" in t[2] || "Post 30" in t[2])
    }

    @Test
    fun readingARedirectTwiceGoesOnInTheSameTab() {
        llm.enqueue(
            Scenario.toolCall("read", """{"url":"${url("/redir")}"}"""),
            Scenario.toolCall("read", """{"url":"${url("/redir")}"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("read it"))
        val t = reply.results().map { it.modelText }
        val tab = browser.tabs.value.single()
        val heads = t.joinToString(" | ") { it.lines().take(3).joinToString(" / ") }
        assertTrue(heads, "(tab ${tab.id}, part 1 of " in t[0])
        assertTrue(t[1], "(tab ${tab.id}, part 2 of " in t[1])
        assertEquals(pageHits.toString(), 1, pageHits.count { it == "/redir" })
    }

    @Test
    fun twoTabsAndARefOfTheOtherOne() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("open", """{"url":"${url("/p1")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"Next page\"")}"}"""),
            Scenario.toolCall("tabs", "{}"),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("compare"))
        val t = reply.results().map { it.modelText }
        val (a, b) = browser.tabs.value
        assertTrue(a.url, a.url.endsWith("/next"))
        assertTrue(b.url, b.url.endsWith("/p1"))
        assertTrue(t[3], t[3].startsWith("2 tabs open; your current tab is ${a.id}."))
        assertTrue(t[3], "[${b.id}] /p1 — ${b.url} (you opened it)" in t[3])
    }

    @Test
    fun askUserWaitsForDoneAndListsThePage() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("ask_user", """{"task":"Please sign in"}"""),
            Scenario.text("done"),
        )
        val sid = send("sign in")
        val step = awaitPause(sid) as Pause.UserStep
        assertEquals("Please sign in", step.task)
        assertEquals(browser.tabs.value.single().id, step.tabId)
        engine.resolve(sid, Resolution.UserStepDone)
        val reply = awaitEnd(sid)
        val t = reply.results().map { it.modelText }
        assertTrue(t[1], t[1].startsWith(AskUser.DONE))
        assertTrue(t[1], "link \"Next page\" [ref=" in t[1])
    }

    @Test
    fun aDownloadLinkIsSavedAndSaysSo() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/dl")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"Get notes\"")}"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("download"))
        val t = reply.results().map { it.modelText }
        // The name Downloads gave it: "notes (1).txt" when an earlier run saved one.
        assertTrue(t[1], Regex("""saved to Downloads as notes( \(\d+\))?\.txt\.""").containsMatchIn(t[1]))
    }

    /** QA B2: opening a file says what was saved, that its text is not readable here and that the tab went. */
    @Test
    fun openingAFileSaysWhatBecameOfItAndOfTheTab() {
        llm.enqueue(Scenario.toolCall("read", """{"url":"${url("/file.txt")}"}"""), Scenario.text("done"))
        val reply = awaitEnd(send("read it"))
        val lead = reply.results()[0].modelText
        assertTrue(lead, Regex("""^Opened tab (\d+)\. This is a file \(text/plain, 9 bytes\), saved to Downloads as notes.*\.txt\. Its text cannot be read in this app\. Tab \1 only downloaded it and was closed\.""").containsMatchIn(lead))
        assertTrue(browser.tabs.value.isEmpty())
    }

    @Test
    fun aConfirmDuringAClickIsAnsweredOk() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/confirm")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("button \"Archive\"")}"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("archive"))
        val t = reply.results().map { it.modelText }
        assertTrue(t[1], "The page asked (confirm): \"Archive it?\" — it was answered OK." in t[1])
        assertTrue(t[1], "archived" in t[1])
        assertTrue(browser.prompts.value.isEmpty())
    }

    @Test
    fun theWholeJobWorksWithAnEmptyPolicyChain() {
        // S21: open, click, scroll and read with no policy at all.
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"Next page\"")}"}"""),
            Scenario.toolCall("scroll", "{}"),
            Scenario.toolCall("read", "{}"),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("all"))
        val t = reply.results().map { it.modelText }
        assertEquals(reply.results().joinToString { it.modelText }, List(4) { ToolCallState.DONE }, reply.calls().map { it.state })
        assertTrue(t[3], "Page /next body text" in t[3])
    }

    @Test
    fun theCookieValueIsInTheResultWithoutTheMaskPolicy() {
        llm.enqueue(Scenario.toolCall("open", """{"url":"${url("/start")}"}"""), Scenario.toolCall("read", "{}"), Scenario.text("done"))
        val reply = awaitEnd(send("cookies"))
        assertTrue(reply.results()[1].modelText, COOKIE in reply.results()[1].modelText)
    }

    @Test
    fun aStaleRefIsAnsweredWithTheNewList() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"Next page\"")}"}"""),
            // The same ref again: the page it was on is gone (the newest list of the tab no longer holds it).
            Scenario.toolCall("click", """{"ref":"${Scenario.lastRef()}"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("stale"))
        val stale = reply.results()[2]
        assertTrue(stale.modelText, stale.isError)
        assertTrue(stale.modelText, "Use a ref from the element list below." in stale.lead)
        assertTrue(stale.modelText, "Page: Next" in stale.page)
    }

    @Test
    fun aRefOfAClosedTabSaysSo() {
        llm.enqueue(Scenario.toolCall("click", """{"ref":"9999e1"}"""), Scenario.text("done"))
        val reply = awaitEnd(send("click"))
        assertEquals(ToolCallState.FAILED, reply.calls().single().state)
        assertTrue(reply.results().single().lead, reply.results().single().lead.startsWith("Ref 9999e1 is from tab 9999, which is closed."))
        assertTrue(browser.tabs.value.isEmpty())
    }

    @Test
    fun typeInASearchBoxPressesEnter() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/search")}"}"""),
            Scenario.toolCall("type", """{"ref":"${Scenario.ref("searchbox")}","text":"lamps"}"""),
            Scenario.text("done"),
        )
        val typed = awaitEnd(send("search")).results()[1].modelText
        assertTrue(typed, Regex("^Typed \"lamps\" into \\d+e\\d+ and pressed Enter → new page\\.").containsMatchIn(typed))
        assertTrue(pageHits.toString(), pageHits.any { it == "/results" })
    }

    @Test
    fun toolCapableModelWithSwitchesOffGetsNoToolsAndCannotOpenATab() {
        llm.enqueue(Scenario.toolCall("open", """{"url":"${url("/start")}"}"""), Scenario.text("plain"))
        val reply = awaitEnd(send("open the start page", browserOn = false))
        assertFalse(modelRequests().first().contains("\"tools\""))
        assertTrue(reply.calls().none { it.state == ToolCallState.DONE })
        assertTrue("/start" !in pageHits)
        assertTrue(browser.tabs.value.isEmpty())
    }

    @Test
    fun linkPrefetchReadsThreeThroughTheReadChain() {
        caps = caps(tools = false)
        llm.enqueue(Scenario.text("summary"))
        val text = "read ${url("/p1")} ${url("/p2")} ${url("/p3")} ${url("/p4")}"
        val reply = awaitEnd(send(text, browserOn = false))
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals(listOf(url("/p1"), url("/p2"), url("/p3")), attachments.saved.values.map { it.attachment.name })
        attachments.saved.values.forEach { assertTrue(it.text, "<untrusted_page_content>" in it.text!!) }
        assertTrue("/p4" !in pageHits)
        assertTrue(modelRequests().first().contains("Page /p3 body text"))
    }

    companion object {
        const val COOKIE = "SECRETCOOKIE123"

        /** A hidden tab's bottom link, and a link in the middle of a long article (QA R-B1 / R-B1b). */
        val TARGETS = listOf(
            Triple("/list", "link \"More\"", "/more"),
            Triple("/article", "link \"James Gosling\"", "/wiki/James_Gosling"),
        )
        val PAGES = mapOf(
            "/start" to """<!doctype html><title>Start</title><a id="go" href="/next">Next page</a>
                <p id="c"></p><script>document.getElementById('c').textContent = 'cookie: ' + document.cookie</script>""",
            "/next" to "<!doctype html><title>Next</title><p>Page /next body text</p>",
            "/feed" to """<!doctype html><title>Feed</title><main id="m"></main><script>
                var n = 0, m = document.getElementById('m'), busy = false;
                function more() { for (var i = 0; i < 10; i++) { var p = document.createElement('p'); p.style.height = '120px'; p.textContent = 'Post ' + (++n); m.appendChild(p); } }
                more(); more();
                addEventListener('scroll', function () {
                  if (busy || innerHeight + scrollY < document.documentElement.scrollHeight - 200) return;
                  busy = true; setTimeout(function () { more(); busy = false; }, 400);
                });</script>""",
            "/dl" to """<!doctype html><title>Downloads</title><a href="/file.txt">Get notes</a>""",
            "/confirm" to """<!doctype html><title>Confirm</title><button onclick="this.textContent = confirm('Archive it?') ? 'archived' : 'kept'">Archive</button>""",
            "/search" to """<!doctype html><title>Search</title><form action="/results"><input id="q" type="search" name="q"></form>""",
            "/shop" to """<!doctype html><title>Shop</title><button onclick="this.textContent='ordered'">Place order</button>
                <button onclick="this.textContent='more'">Show more</button>""",
            "/order" to """<!doctype html><meta name="viewport" content="width=device-width"><title>Order</title>
                <form action="/ordered"><p style="height:120px">Large pizza, 1</p><button>Submit order</button></form>""",
            // QA R-B1: More at the bottom of a list, and a link in the middle of a long article among other links.
            "/list" to "<!doctype html><meta name=\"viewport\" content=\"width=device-width\"><title>List</title>" +
                (0 until 80).joinToString("") { "<a href=\"/row$it\" style=\"display:block;height:40px\">Row $it</a>" } + "<a href=\"/more\">More</a>",
            "/article" to "<!doctype html><meta name=\"viewport\" content=\"width=device-width\"><title>Article</title>" +
                (0 until 60).joinToString("") { i ->
                    if (i == 30) "<p>Designed by <a href=\"/wiki/James_Gosling\">James Gosling</a> at <a href=\"/wiki/Oracle\">Oracle Corporation</a>.</p>"
                    else "<p>Paragraph $i with <a href=\"/wiki/Topic_$i\">topic $i</a> and <a href=\"/wiki/Other_$i\">other $i</a>, then more words to fill the line.</p>"
                },
            "/tabs" to """<!doctype html><title>Tabs</title>
                <div role="tablist"><button role="tab" aria-selected="true" onclick="pick(0)">One</button><button role="tab" aria-selected="false" onclick="pick(1)">Two</button></div>
                <div role="tabpanel">Panel one</div><div role="tabpanel" hidden>Panel two</div>
                <script>function pick(i){document.querySelectorAll('[role=tab]').forEach(function(t,j){t.setAttribute('aria-selected',String(i===j))});document.querySelectorAll('[role=tabpanel]').forEach(function(p,j){p.hidden=i!==j})}</script>""",
            "/covered" to """<!doctype html><title>Covered</title><button onclick="this.textContent='pressed'">Press</button>
                <div id="cover" style="position:fixed;inset:0;background:#fff">Cookie wall</div>""",
        )
    }
}

/** The policies on: the same flows with one policy each show only that policy's part (on/off). */
@RunWith(AndroidJUnit4::class)
class ToolFlowPolicyTest : ToolFlowBase() {
    override fun policies(): Policies {
        val texts = PolicyModule.texts(InstrumentationRegistry.getInstrumentation().targetContext)
        return Policies(listOf(MoneyConfirmPolicy(texts), CookieMaskPolicy()), emptyList())
    }

    override fun respond(path: String, request: RecordedRequest): MockResponse? {
        val body = ToolFlowE2eTest.PAGES[path] ?: return null
        val b = MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8")
        if (path == "/start") b.addHeader("Set-Cookie", "sid=${ToolFlowE2eTest.COOKIE}; Path=/")
        return b.body(body).build()
    }

    /** QA B6: the approval preview shows the form being sent, not only its button. */
    @Test
    fun anOrderPreviewShowsTheForm() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/order")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("button \"Submit order\"")}"}"""),
            Scenario.text("done"),
        )
        val sid = send("order")
        val ask = awaitPause(sid) as Pause.Approval
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val preview = ask.request.preview!!
        assertTrue("${preview.height} px", preview.height >= 140 * density)
        engine.resolve(sid, Resolution.ApprovalAnswer(ask.id, allow = false))
        awaitEnd(sid)
    }

    @Test
    fun anOrderButtonAsksAndADenialIsNotClicked() {
        llm.enqueue(
            Scenario.toolCall("open", """{"url":"${url("/shop")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("button \"Show more\"")}"}"""), // no question
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("button \"Place order\"")}"}"""),
            Scenario.text("done"),
        )
        val sid = send("order")
        val ask = awaitPause(sid) as Pause.Approval
        assertEquals("Place order", ask.request.target)
        assertTrue(ask.request.pageUrl.orEmpty().endsWith("/shop"))
        engine.resolve(sid, Resolution.ApprovalAnswer(ask.id, allow = false))
        val reply = awaitEnd(sid)
        assertEquals(listOf(ToolCallState.DONE, ToolCallState.DONE, ToolCallState.DENIED), reply.calls().map { it.state })
        assertTrue(reply.results()[2].modelText, ToolOutcome.DENIED_TEXT in reply.results()[2].modelText)
    }

    @Test
    fun cookieValuesAreMasked() {
        llm.enqueue(Scenario.toolCall("open", """{"url":"${url("/start")}"}"""), Scenario.toolCall("read", "{}"), Scenario.text("done"))
        val reply = awaitEnd(send("cookies"))
        reply.results().forEach { assertFalse(it.modelText, ToolFlowE2eTest.COOKIE in it.modelText) }
        assertTrue(reply.results()[1].modelText, "[cookie hidden by the app]" in reply.results()[1].modelText)
    }
}
