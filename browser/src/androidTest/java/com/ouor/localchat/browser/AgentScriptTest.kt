package com.ouor.localchat.browser

import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.testing.TestHostActivity
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.browser.script.PageList
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.ReadText
import com.ouor.localchat.browser.script.ScrollResult
import com.ouor.localchat.browser.script.Suggestions
import com.ouor.localchat.browser.script.TypeResult
import com.ouor.localchat.browser.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * agent.js on a real WebView (architecture 11): pages from androidTest/assets/pages served by MockWebServer,
 * the tab attached at full size to a test Activity like the engine's host container.
 */
@RunWith(AndroidJUnit4::class)
class AgentScriptTest {

    @get:Rule val screen = ScreenOnRule()

    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<TestHostActivity>
    private lateinit var tab: BrowserTab

    @Before
    fun setUp() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = if (path.startsWith("/pages/")) assets.open(path.removePrefix("/")).bufferedReader().use { it.readText() }
                else "<!doctype html><title>${request.method} $path</title><p>${request.method} $path</p>"
                return MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()
            }
        }
        server.start()
        scenario = ActivityScenario.launch(TestHostActivity::class.java)
        scenario.onActivity { a ->
            val host = FrameLayout(a)
            a.addContentView(host, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            tab = BrowserTab(1, a.applicationContext, events)
            tab.contextWrapper.baseContext = a
            host.addView(tab.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    /** Dialogs wait for an answer here ([BrowserTab.resolve]), like the shown tab's dialogs wait for the user. */
    private val events = object : BrowserTab.Events {
        override fun dispatch(tab: BrowserTab, e: BrowserEvent): Answer =
            if (e is BrowserEvent.Dialog) Answer.ToUser else BrowserEngine.defaultAnswer(e, shown = false)
    }

    @After
    fun tearDown() {
        scenario.onActivity { tab.destroy() }
        scenario.close()
        server.close()
    }

    /** Loads [page] and waits the way the tools do: no fixed sleep (tools-redesign 2-1). */
    private fun load(page: String) = runBlocking {
        val since = android.os.SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) { tab.load(server.url("/pages/$page").toString()) }
        assertEquals("load $page", Readiness.READY, tab.awaitReady(since))
    }

    private suspend fun snapshot() = PageScript.call<PageList>(tab, "pageList").text
    private suspend fun locate(target: String) = PageScript.call<Located>(tab, "locate", buildJsonObject { put("target", target) })
    private suspend fun js(code: String) = PageScript.execute(tab, code, 5_000)
    private suspend fun text(id: String) = js("document.getElementById('$id').textContent").trim('"')
    private fun refOf(snap: String, line: String): String =
        Regex(Regex.escape(line) + "[^\\n]*\\[ref=(\\d+e\\d+)]").find(snap)?.groupValues?.get(1) ?: error("no ref for '$line' in\n$snap")

    /**
     * The ref the element list gives the element with [id], as a model would read it (the tools take refs only, F2):
     * the element is named uniquely for one full list, then its own name is put back.
     */
    private suspend fun ref(id: String): String {
        val e = "document.getElementById('$id')"
        val old = tab.evaluate("(function () { var e = $e, o = e.getAttribute('aria-label'); e.setAttribute('aria-label', 'zz-$id'); return o; })()", 5_000)
        try {
            return refOf(PageScript.call<PageList>(tab, "pageList", buildJsonObject { put("budget", 500_000) }).text, "\"zz-$id\"")
        } finally {
            tab.evaluate(if (old == "null") "$e.removeAttribute('aria-label')" else "$e.setAttribute('aria-label', $old)", 5_000)
        }
    }

    private suspend fun call(fn: String, id: String, text: String? = null) =
        PageScript.callJson(tab, fn, buildJsonObject { put("target", ref(id)); text?.let { put("text", it) } })

    @Test
    fun thumbnailAfterLoad() {
        load("snapshot.html")
        runBlocking { delay(1_500) }
        val t = tab.thumbnail
        assertNotNull(t)
        t!!
        assertEquals(BrowserTab.THUMB_WIDTH, t.width)
        assertTrue(t.height > 0)
    }

    @Test
    fun elementListFormat() = runBlocking<Unit> {
        load("snapshot.html")
        val s = snapshot()
        listOf(
            "- navigation \"Main\"",
            "  - link \"Next page\" [ref=",
            "-> /next", // a path on this site, which open() and read() take as it is (G-m5, QA-M2)
            "- heading \"Search results\" [level=1]",
            "- text: \"Plain paragraph text.\"",
            "- searchbox \"Query\" [ref=", // named q: a search box (G-m4)
            "value=\"foo\"",
            "- checkbox \"I agree\" [checked] [ref=",
            "- combobox \"Color\" [ref=",
            "value=\"Green\" options=[Red, Green, Blue]",
            "- list\n    - text: \"First item\"", // list items add no line of their own (QA-M2)
            "- button \"Press me\" [ref=",
            "- button \"Shadow button\" [ref=",
            "- textbox \"Shadow field\" [ref=",
            "Slotted title",
            "- iframe \"Same origin\"",
            "  - button \"Inner button\" [ref=",
            "- iframe \"Cross origin\" (cross-origin, not accessible) [ref=", // a click on it asks first
        ).forEach { assertTrue("missing '$it' in\n$s", it in s) }
        // [secret] is a fact (password type, autocomplete one-time-code / cc-*); the value is listed, a policy hides it.
        assertTrue(s, Regex("textbox \"Password\" \\[secret] \\[ref=1e\\d+] value=\"hunter22\"").containsMatchIn(s))
        assertTrue(s, Regex("textbox \"Card\" \\[secret] \\[ref=1e\\d+] value=\"4111111111111111\"").containsMatchIn(s))
        assertTrue(s, Regex("textbox \"One-time code\" \\[ref=1e\\d+] value=\"123456\"").containsMatchIn(s)) // a name alone is no fact
        listOf("hidden-value", "Invisible", "Hidden from AT").forEach {
            assertFalse("'$it' leaked into\n$s", it in s)
        }
        // Same element keeps its ref across lists; the page DOM has no marker attributes.
        assertEquals(refOf(s, "button \"Press me\""), refOf(snapshot(), "button \"Press me\""))
        assertEquals("0", js("document.querySelectorAll('[data-ref],[data-tandem-snap],[data-ref-id]').length"))
    }

    @Test
    fun refClickIsNativeAndHighlightRectMapsToViewPixels() = runBlocking<Unit> {
        load("snapshot.html")
        val ref = refOf(snapshot(), "button \"Press me\"")
        val at = locate(ref)
        assertTrue("hit test", at.hit)
        assertEquals(200f, at.rect.width, 1f)
        assertEquals(50f, at.rect.height, 1f)
        val (viewWidth, density) = withContext(Dispatchers.Main) { tab.view.width to tab.view.resources.displayMetrics.density }
        // view.width / innerWidth is the CSS px -> view px factor the overlay uses (architecture 7.5).
        assertTrue("innerWidth ${at.innerWidth} * density $density vs view $viewWidth", abs(at.innerWidth * density - viewWidth) < 3)
        assertEquals(BrowserTab.Tap.DONE, tab.tap(at.cx, at.cy, at.innerWidth))
        delay(300)
        assertEquals("clicked:true", text("out"))
    }

    @Test
    fun refsAreNeverReusedInATab() = runBlocking<Unit> {
        // F7: each document of a tab numbers on from the last; an old ref is told apart from a wrong one.
        load("snapshot.html")
        val first = snapshot()
        val old = refOf(first, "button \"Press me\"")
        val highest = Regex("\\[ref=1e(\\d+)]").findAll(first).maxOf { it.groupValues[1].toInt() }
        load("snapshot.html")
        val second = snapshot()
        assertTrue(second, Regex("\\[ref=1e(\\d+)]").findAll(second).all { it.groupValues[1].toInt() > highest })
        assertFalse("every ref names its tab (S4)", Regex("\\[ref=e").containsMatchIn(second))
        try {
            locate(old)
            fail("expected stale ref")
        } catch (e: StaleRef) {
            assertEquals("Ref $old is from an earlier page of this tab, not this one.", e.message)
        }
        try {
            locate("1e99999")
            fail("expected unknown ref")
        } catch (e: StaleRef) {
            assertEquals("Ref 1e99999 is not on this page.", e.message)
        }
        try {
            locate("2e5")
            fail("expected another tab's ref")
        } catch (e: StaleRef) {
            assertEquals("Ref 2e5 is from tab 2, not tab 1.", e.message)
        }
        try {
            locate("#q")
            fail("selectors are not taken")
        } catch (e: StaleRef) {
            assertTrue(e.message, "is not a ref" in e.message!!)
        }
        // How models write refs (G-m5): @1e1, [ref=1e1], 1e1.
        val now = refOf(second, "button \"Press me\"")
        listOf("@$now", "[ref=$now]", " $now ").forEach { assertTrue(it, locate(it).hit) }
    }

    @Test
    fun shadowDomAndIframeTargets() = runBlocking<Unit> {
        load("snapshot.html")
        val s = snapshot()
        val shadow = refOf(s, "button \"Shadow button\"")
        val at = locate(shadow)
        assertTrue("shadow hit", at.hit)
        assertEquals(BrowserTab.Tap.DONE, tab.tap(at.cx, at.cy, at.innerWidth))
        delay(300)
        assertEquals("shadow clicked", text("out"))
        val inner = locate(refOf(s, "button \"Inner button\""))
        assertTrue("iframe element hit in top-level coordinates", inner.hit)
    }

    @Test
    fun secretFieldsAreTypedLikeAnyOtherAndExecuteRunsOnce() = runBlocking<Unit> {
        // Layer 1 refuses nothing (browser-layers 2-7): whether the AI may fill a secret field is a policy's call.
        load("snapshot.html")
        assertEquals("pass", PageScript.call<TypeResult>(tab, "type", buildJsonObject { put("target", ref("pw")); put("text", "pass") }).value)
        val r = PageScript.call<TypeResult>(tab, "type", buildJsonObject { put("target", ref("q")); put("text", "bar") })
        assertEquals("bar", r.value)
        assertTrue(locate(ref("q")).info.search)
        js("document.getElementById('pw').value = 'x'")
        assertEquals("\"x\"", js("document.getElementById('pw').value"))
        assertEquals("6", js("var a = 2; return a * 3"))
        assertEquals("42", js("new Promise(function (r) { setTimeout(function () { r(42); }, 300); })"))
        assertEquals("\"Snapshot test\"", js("document.title;"))
    }

    @Test
    fun describeTellsTheFormsSubmitButtonAndTheFrame() = runBlocking<Unit> {
        load("forms.html")
        val user = PageScript.call<Located>(tab, "locate", buildJsonObject { put("target", ref("user")); put("scroll", false) })
        assertEquals("Sign in", user.formSubmitLabel)
        assertEquals("POST".lowercase(), user.info.formMethod)
        assertEquals(null, user.frame)
        assertEquals("Search", locate(ref("sq")).formSubmitLabel)
        assertEquals(null, locate(ref("del")).formSubmitLabel)
        // type reports the form's submit button for the "not submitted" note.
        val typed = PageScript.call<TypeResult>(tab, "type", buildJsonObject { put("target", ref("user")); put("text", "kim") })
        assertEquals(ref("signin"), typed.submitRef)
        load("snapshot.html")
        val frame = Regex("iframe \"Cross origin\" \\(cross-origin, not accessible\\) \\[ref=(\\d+e\\d+)]").find(snapshot())!!.groupValues[1]
        assertTrue(locate(frame).frame.orEmpty().startsWith("http"))
    }

    @Test
    fun nativeKeysReachTheFocusedField() = runBlocking<Unit> {
        load("forms.html")
        call("type", "keys", "")
        withContext(Dispatchers.Main) { tab.key(KeyEvent.KEYCODE_A, 0) }
        delay(200)
        assertTrue(text("log"), "a:true" in text("log"))
        call("type", "sq", "dogs")
        withContext(Dispatchers.Main) { tab.key(KeyEvent.KEYCODE_ENTER, 0) }
        delay(300)
        assertTrue(text("log"), "submitted" in text("log"))
    }

    @Test
    fun typeChoosesOptionsAndEditsContentEditable_readTextReadsThePage() = runBlocking<Unit> {
        load("snapshot.html")
        // A select takes the option's text or value through type (tools-redesign 3-5).
        assertEquals("Blue", PageScript.call<TypeResult>(tab, "type", buildJsonObject { put("target", ref("color")); put("text", "blue") }).selected)
        try {
            call("type", "color", "Purple")
            fail("no such option")
        } catch (e: PageError) {
            assertTrue(e.message, e.message!!.startsWith("No option matches \"Purple\". Options: Red, Green, Blue"))
        }
        val t = PageScript.call<ReadText>(tab, "readText")
        assertTrue(t.text, "Plain paragraph text." in t.text)
        assertFalse("hunter22" in t.text)
        assertEquals("Snapshot test", t.title)
        load("forms.html")
        val r = PageScript.call<TypeResult>(tab, "type", buildJsonObject { put("target", ref("editor")); put("text", "new text") })
        assertEquals("new text", r.value)
    }

    @Test
    fun dialogsAreHeldAndAnswered() = runBlocking<Unit> {
        load("dialog.html")
        try {
            call("click", "ask")
            fail("click should be cut short by confirm()")
        } catch (e: PageError) {
            assertTrue(e.message, "confirm dialog is open: \"Really delete?\". The page is waiting for the user to answer it." in e.message!!)
        }
        val d = tab.dialog.value!!
        assertEquals(DialogKind.CONFIRM, d.kind)
        withContext(Dispatchers.Main) { tab.resolve(d.id, Answer.Accept()) }
        assertEquals("confirm=true", text("r"))
        val at = locate(ref("name"))
        tab.tap(at.cx, at.cy, at.innerWidth)
        delay(300)
        assertEquals(DialogKind.PROMPT, tab.dialog.value?.kind)
        assertEquals("anon", tab.dialog.value?.defaultText)
        withContext(Dispatchers.Main) { tab.resolve(tab.dialog.value!!.id, Answer.Accept("Kim")) }
        assertEquals("prompt=Kim", text("r"))
        // Every event and its final answer is in the tab's record for the next tool result (browser-layers 2-4).
        val final = withContext(Dispatchers.Main) { tab.eventsSince(0) }.filter { it.answer != Answer.ToUser }
        assertEquals(listOf(DialogKind.CONFIRM, DialogKind.PROMPT), final.map { (it.event as BrowserEvent.Dialog).kind })
        assertTrue(final.all { it.answer is Answer.Accept })
    }

    @Test
    fun screenshotAndPdf() = runBlocking<Unit> {
        load("forms.html")
        val bmp = withContext(Dispatchers.Main) { tab.capture() }
        assertNotNull(bmp)
        val pdf = PdfPrinter.print(tab)
        assertEquals("%PDF", String(pdf, 0, 4))
    }

    @Test
    fun severalArticlesAreReadAndALongPageIsNoBotCheck() = runBlocking<Unit> {
        load("reading.html")
        val t = PageScript.call<ReadText>(tab, "readText")
        // B-m11: two articles are both the page's text.
        assertTrue(t.text, "ALPHA-STORY" in t.text && "BETA-STORY" in t.text)
        // B-M3: a long page that mentions a captcha is no bot check; a short one that says so is.
        assertFalse(t.flags.toString(), "bot" in t.flags)
        load("short-bot.html")
        assertTrue("bot" in PageScript.call<ReadText>(tab, "readText").flags)
    }

    @Test
    fun aSearchForCaptchaIsNotABotCheck() = runBlocking<Unit> {
        // B-M3: the results page of the query "captcha" names it everywhere; results mean it was no block.
        load("ddg-captcha.html")
        val page = PageScript.search(tab, SearchEngine.DUCKDUCKGO)
        assertFalse(page.blocked)
        assertEquals(2, page.results.size)
        load("short-bot.html")
        assertTrue(PageScript.search(tab, SearchEngine.DUCKDUCKGO).blocked)
    }

    @Test
    fun aCrowdedPageStillListsWhatIsOnScreen() = runBlocking<Unit> {
        // B-M4: past the element cap, on-screen actions are kept and the rest is counted.
        load("crowded.html")
        val l = PageScript.call<PageList>(tab, "pageList")
        assertTrue(l.text, "button \"Fixed action\" [ref=" in l.text)
        // Past MAX_ITEMS (20,000) only what is important and on screen is kept; the rest is counted apart (F6).
        assertTrue("$l", l.listed + l.above + l.below + l.beyond >= 21_001)
        assertTrue("$l", l.below > 0 && l.beyond > 0)
    }

    /** The screen is listed whatever the budget: at the bottom of HN, a 64k model's 8,000 characters still give More. */
    @Test
    fun theScreenIsListedWholeAtThePageEnd() = runBlocking<Unit> {
        load("hn.html")
        js("window.scrollTo(0, document.documentElement.scrollHeight)")
        val l = PageScript.call<PageList>(tab, "pageList", buildJsonObject { put("budget", 8_000); put("screen", true) })
        assertTrue(l.text, "link \"More\" [ref=" in l.text)
        assertEquals("$l", 0, l.screenLeft)
        // A budget too small for the screen: the on-screen bound (twice the budget) cuts and says how many.
        val tiny = PageScript.call<PageList>(tab, "pageList", buildJsonObject { put("budget", 300); put("screen", true) })
        assertTrue("$tiny", tiny.screenLeft > 0 && tiny.text.length <= 900)
    }

    @Test
    fun aLayoutTablePageListsEveryLinkInTheFirstList() = runBlocking<Unit> {
        // QA-M2: an HN-like front page (layout tables, 30 stories) fits in one list, More included.
        load("hn.html")
        val l = PageScript.call<PageList>(tab, "pageList", buildJsonObject { put("budget", 16_000) })
        assertTrue(l.text, "link \"More\" [ref=" in l.text)
        // 228 links and the search field: every one of them.
        assertEquals(l.text, 229, Regex("\\[ref=1e").findAll(l.text).count())
        // QA-M3: the nameless vote link is named by its icon, and a link with nothing else by its address.
        assertTrue(l.text, "link \"upvote\" [ref=" in l.text)
        assertTrue(l.text, "link \"news.ycombinator.com\" [ref=" in l.text)
        assertFalse(l.text, Regex("^ *- (table|row|cell|listitem)", RegexOption.MULTILINE).containsMatchIn(l.text))
    }

    @Test
    fun suggestionsAreTheFieldsOwn() = runBlocking<Unit> {
        // G-m2: type leaves Enter alone when the field's own suggestions open, not for any list on the page.
        load("reading.html")
        suspend fun opened(id: String, text: String): Boolean {
            val r = ref(id)
            val before = PageScript.call<Suggestions>(tab, "suggestions", buildJsonObject { put("target", r) })
            PageScript.callJson(tab, "type", buildJsonObject { put("target", r); put("text", text) })
            delay(200)
            return before.openedBy(PageScript.call(tab, "suggestions", buildJsonObject { put("target", r) }))
        }
        assertTrue("aria-controls list", opened("box", "lamp"))
        assertTrue("datalist option", opened("dl", "lamp"))
        assertFalse("a list far from the field", opened("plain", "lamp"))
    }

    @Test
    fun aDocumentNumbersOnFromTheTabsNextRef() = runBlocking<Unit> {
        // F7: a document back from the back/forward cache keeps its old base; new refs still start at the tab's next.
        load("snapshot.html")
        snapshot()
        val next = tab.refNext
        tab.refNext = next + 100 // what another document of the tab handed out meanwhile
        tab.evaluate("document.body.insertAdjacentHTML('afterbegin', '<button>Added later</button>')", 5_000)
        assertEquals("1e${next + 100}", refOf(snapshot(), "button \"Added later\""))
    }

    @Test
    fun codeThatBreaksOutOfTheCheckIsNotRun() = runBlocking<Unit> {
        // The syntax check builds the code with new Function in a blank WebView: nothing of it runs in the page.
        load("snapshot.html")
        try {
            js("window.hits = (window.hits || 0) + 1 })(); window.hits = (window.hits || 0) + 10; (function () {")
            fail("expected a syntax error")
        } catch (e: PageError) {
            assertTrue(e.message, e.message!!.startsWith("SyntaxError"))
        }
        assertEquals("\"undefined\"", tab.evaluate("String(window.hits)", 5_000))
        assertEquals("3", js("1 + 2"))
    }

    private suspend fun scroll(dir: String, target: String? = null) =
        PageScript.call<ScrollResult>(tab, "scroll", buildJsonObject { put("dir", dir); target?.let { put("target", it) } })

    private suspend fun scrollY() = tab.evaluate("String(Math.round(scrollY))", 5_000).trim('"').toInt()

    @Test
    fun scrollMovesTheDocumentOneScreenAtOnceAndBack() = runBlocking<Unit> {
        load("scroll.html")
        val inner = tab.evaluate("innerHeight", 5_000).toFloat()
        val down = scroll("down")
        assertTrue("$down", down.moved && down.box == null)
        // instant despite the page's scroll-behavior: smooth, and 0.85 of a screen
        assertEquals(inner * 0.85f, scrollY().toFloat(), 2f)
        assertEquals(down.y, scrollY())
        assertTrue("$down", down.max > down.y && down.docHeight > inner && down.count > 100)
        val up = scroll("up")
        assertTrue(up.moved)
        assertEquals(0, scrollY())
        assertFalse("already at the top", scroll("up").moved)
    }

    @Test
    fun aScrollingBoxIsListedAndScrolledByItsRef() = runBlocking<Unit> {
        load("scroll.html")
        val s = snapshot()
        val box = Regex("generic \\[scrollable] \\[ref=(1e\\d+)]").find(s)?.groupValues?.get(1) ?: error("no scrollable line in\n$s")
        // the pane's own lines sit one level deeper
        assertTrue(s, Regex("generic \\[scrollable] \\[ref=1e\\d+]\\n  - text: \"Box line 1\"").containsMatchIn(s))
        val r = scroll("down", box)
        assertTrue("$r", r.moved && r.box == box && r.y > 0)
        assertEquals("the document stays", 0, scrollY())
        // On a tiny budget the pane line is kept: it is important (W-m1).
        val small = PageScript.call<PageList>(tab, "pageList", buildJsonObject { put("budget", 300) }).text
        assertTrue(small, "[scrollable]" in small)
    }

    @Test
    fun aPageWhoseDocumentDoesNotScrollScrollsItsBiggestPane() = runBlocking<Unit> {
        load("pane.html")
        val r = scroll("down")
        assertTrue("$r", r.moved && r.box != null)
        assertEquals("\"pane\"", tab.evaluate("(function(){return document.getElementById('pane').scrollTop > 0 ? 'pane' : 'none'})()", 5_000))
        assertEquals("\"0\"", tab.evaluate("String(document.getElementById('side').scrollTop)", 5_000))
    }

    @Test
    fun readTextSaysWhereTheScreenIsAndSkipsAFixedHeader() = runBlocking<Unit> {
        load("scroll.html")
        val top = PageScript.call<ReadText>(tab, "readText")
        assertFalse(top.text, "\u0001" in top.text)
        // The page has a <main>: its text is read, so the screen starts at its first paragraph.
        assertTrue(top.text.substring(top.screenAt!!).take(80), top.text.substring(top.screenAt!!).startsWith("Paragraph 1 "))
        scroll("down")
        scroll("down")
        val lower = PageScript.call<ReadText>(tab, "readText")
        val at = lower.text.substring(lower.screenAt!!)
        assertTrue(at.take(80), at.startsWith("Paragraph ") && !at.startsWith("Paragraph 1 "))
        assertTrue(lower.screenAt!! > top.screenAt!!)
    }

    @Test
    fun aFlingingPageIsNotTapped() = runBlocking<Unit> {
        load("scroll.html")
        val ref = refOf(PageScript.call<PageList>(tab, "pageList", buildJsonObject { put("budget", 500_000) }).text, "generic [scrollable]")
        val at = locate(ref)
        // a smooth scroll still running when the tap comes: the down would only stop it
        tab.evaluate("window.scrollTo({top: 3000, behavior: 'smooth'})", 5_000)
        while (scrollY() == 0) delay(5) // the scroll is under way, as a fling the user just let go of
        assertEquals(BrowserTab.Tap.MOVING, tab.tap(at.cx, at.cy, at.innerWidth))
    }

    /** Live DuckDuckGo HTML results (R8). Skipped without network. */
    @Test
    fun duckDuckGoExtractorOnTheRealSite() = runBlocking<Unit> {
        assumeTrue("liveWeb", InstrumentationRegistry.getArguments().getString("liveWeb") == "true")
        val since = android.os.SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) { tab.load(SearchEngines.url(SearchEngine.DUCKDUCKGO, "android webview")) }
        assumeTrue("network", tab.awaitReady(since) == Readiness.READY && tab.url.contains("duckduckgo"))
        val page = PageScript.search(tab, SearchEngine.DUCKDUCKGO)
        assumeTrue("no bot check", !page.blocked)
        val results = page.results
        assertTrue("results: $results", results.size >= 3)
        results.forEach {
            assertTrue(it.url, it.url.startsWith("http") && "duckduckgo.com/l/" !in it.url)
            assertTrue(it.title.isNotBlank())
        }
        android.util.Log.i("AgentScriptTest", "DDG: " + results.joinToString(" | ") { "${it.title} <${it.url}>" })
    }
}
