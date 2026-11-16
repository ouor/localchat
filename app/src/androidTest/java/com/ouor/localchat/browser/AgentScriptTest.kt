package com.ouor.localchat.browser

import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.MainActivity
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.safety.IrreversibleAction.Reason
import com.ouor.localchat.browser.script.FocusResult
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.PageTextResult
import com.ouor.localchat.browser.script.SearchResult
import com.ouor.localchat.browser.script.SelectResult
import com.ouor.localchat.browser.script.SnapshotResult
import com.ouor.localchat.browser.script.TypeResult
import com.ouor.localchat.core.model.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * agent.js on a real WebView (architecture 11): pages from androidTest/assets/pages served by MockWebServer,
 * the tab attached at full size to MainActivity like the engine's host container.
 */
@RunWith(AndroidJUnit4::class)
class AgentScriptTest {

    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>
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
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { a ->
            val host = FrameLayout(a)
            a.addContentView(host, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            tab = BrowserTab(1, a.applicationContext, Owner.Ai("s"), object : BrowserTab.Events {})
            tab.contextWrapper.baseContext = a
            host.addView(tab.webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    @After
    fun tearDown() {
        scenario.onActivity { tab.webView.destroy() }
        scenario.close()
        server.close()
    }

    private fun load(page: String) = runBlocking {
        withContext(Dispatchers.Main) { tab.load(server.url("/pages/$page").toString()) }
        assertTrue("load $page", tab.awaitLoaded(15_000))
        delay(300) // custom elements, srcdoc iframe
    }

    private suspend fun snapshot() = PageScript.call<SnapshotResult>(tab, "snapshot").text
    private suspend fun locate(target: String) = PageScript.call<Located>(tab, "locate", buildJsonObject { put("target", target) })
    private suspend fun js(code: String) = PageScript.execute(tab, code, 5_000)
    private suspend fun text(id: String) = js("document.getElementById('$id').textContent").trim('"')
    private fun refOf(snap: String, line: String): String =
        Regex(Regex.escape(line) + "[^\\n]*\\[ref=(e\\d+)]").find(snap)?.groupValues?.get(1) ?: error("no ref for '$line' in\n$snap")

    @Test
    fun focusInfoReportsSensitiveFieldsAndFrames() = runBlocking<Unit> {
        load("snapshot.html")
        js("document.getElementById('pw').focus()")
        val pw = PageScript.call<FocusResult>(tab, "focusInfo")
        assertTrue(pw.sensitive)
        js("document.getElementById('cross').focus()")
        assertEquals("iframe", PageScript.call<FocusResult>(tab, "focusInfo").info?.tag)
    }

    @Test
    fun consoleIsClearedOnNavigation() = runBlocking<Unit> {
        load("snapshot.html")
        js("console.log('old-page-secret')")
        delay(200)
        assertTrue(tab.consoleLines().any { "old-page-secret" in it })
        load("forms.html")
        assertTrue(tab.consoleLines().toString(), tab.consoleLines().none { "old-page-secret" in it })
    }

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
    fun snapshotFormat() = runBlocking<Unit> {
        load("snapshot.html")
        val s = snapshot()
        val lines = s.lines()
        assertTrue(lines[0], lines[0].startsWith("page: Snapshot test — http://"))
        listOf(
            "- navigation \"Main\"",
            "  - link \"Next page\" [ref=",
            "-> /next",
            "- heading \"Search results\" [level=1]",
            "- text: \"Plain paragraph text.\"",
            "- textbox \"Query\" [ref=",
            "value=\"foo\"",
            "- checkbox \"I agree\" [checked] [ref=",
            "- combobox \"Color\" [ref=",
            "value=\"Green\" options=[Red, Green, Blue]",
            "- listitem: \"First item\"",
            "- button \"Press me\" [ref=",
            "- button \"Shadow button\" [ref=",
            "- textbox \"Shadow field\" [ref=",
            "Slotted title",
            "- iframe \"Same origin\"",
            "  - button \"Inner button\" [ref=",
            "- iframe \"Cross origin\" (cross-origin, not accessible)",
        ).forEach { assertTrue("missing '$it' in\n$s", it in s) }
        assertTrue(s, Regex("textbox \"Password\" \\[ref=e\\d+] \\[sensitive] value=••••").containsMatchIn(s))
        assertTrue(s, Regex("textbox \"One-time code\" \\[ref=e\\d+] \\[sensitive] value=••••").containsMatchIn(s))
        assertTrue(s, Regex("textbox \"Card\" \\[ref=e\\d+] \\[sensitive] value=••••").containsMatchIn(s))
        listOf("hunter22", "123456", "4111", "hidden-value", "Invisible", "Hidden from AT").forEach {
            assertFalse("'$it' leaked into\n$s", it in s)
        }
        // Same element keeps its ref across snapshots; the page DOM has no marker attributes.
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
        val (viewWidth, density) = withContext(Dispatchers.Main) { tab.webView.width to tab.webView.resources.displayMetrics.density }
        // view.width / innerWidth is the CSS px -> view px factor the overlay uses (architecture 7.5).
        assertTrue("innerWidth ${at.innerWidth} * density $density vs view $viewWidth", abs(at.innerWidth * density - viewWidth) < 3)
        assertTrue(withContext(Dispatchers.Main) { tab.tap(at.cx, at.cy, at.innerWidth) })
        delay(300)
        assertEquals("clicked:true", text("out"))
    }

    @Test
    fun staleRefAfterNewDocument() = runBlocking<Unit> {
        load("snapshot.html")
        val ref = refOf(snapshot(), "button \"Press me\"")
        load("snapshot.html")
        try {
            locate(ref)
            fail("expected stale ref")
        } catch (e: PageError) {
            assertTrue(e.message, e.message!!.startsWith("stale ref $ref: take a new snapshot"))
        }
        try {
            locate("#nope")
            fail("expected no match")
        } catch (e: PageError) {
            assertTrue(e.message, "no element matches selector #nope" in e.message!!)
        }
    }

    @Test
    fun shadowDomAndIframeTargets() = runBlocking<Unit> {
        load("snapshot.html")
        val s = snapshot()
        val shadow = refOf(s, "button \"Shadow button\"")
        val at = locate(shadow)
        assertTrue("shadow hit", at.hit)
        assertTrue(withContext(Dispatchers.Main) { tab.tap(at.cx, at.cy, at.innerWidth) })
        delay(300)
        assertEquals("shadow clicked", text("out"))
        val inner = locate(refOf(s, "button \"Inner button\""))
        assertTrue("iframe element hit in top-level coordinates", inner.hit)
        assertNotNull(locate("#shadowBtn")) // CSS selectors reach into open shadow roots too
    }

    @Test
    fun sensitiveFieldsAreRefusedForTypeAndExecute() = runBlocking<Unit> {
        load("snapshot.html")
        assertTrue(locate("#pw").sensitive)
        assertTrue(locate("#otp").sensitive)
        assertFalse(locate("#q").sensitive)
        try {
            PageScript.callJson(tab, "type", buildJsonObject { put("target", "#pw"); put("text", "x") })
            fail("typed into password")
        } catch (e: PageError) {
            assertTrue(e.message, e.message!!.startsWith("refused"))
        }
        val r = PageScript.call<TypeResult>(tab, "type", buildJsonObject { put("target", "#q"); put("text", "bar") })
        assertEquals("bar", r.value)
        try {
            js("document.getElementById('pw').value = 'x'")
            fail("execute set a password")
        } catch (e: PageError) {
            assertTrue(e.message, "refused" in e.message!!)
        }
        // The setter patch lives only while execute runs.
        assertEquals("true", tab.evaluate("Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.toString().indexOf('native code') >= 0", 5_000))
        assertEquals("6", js("var a = 2; return a * 3"))
        assertEquals("42", js("new Promise(function (r) { setTimeout(function () { r(42); }, 300); })"))
        assertEquals("\"Snapshot test\"", js("document.title;"))
    }

    @Test
    fun postFormAndKeywordJudgement() = runBlocking<Unit> {
        load("forms.html")
        suspend fun reason(t: String) = IrreversibleAction.reasonForClick(locate(t).info)
        assertEquals(Reason.POST_FORM, reason("#signin"))
        assertNull(reason("#go"))
        assertNull(reason("#plain"))
        assertNull(reason("#override"))
        assertEquals(Reason.RISKY_KEYWORD, reason("#del"))
        assertEquals(Reason.RISKY_KEYWORD, reason("#buy"))
        assertEquals(Reason.RISKY_KEYWORD, reason("#article")) // SE 18: the label start is checked even on long links
        PageScript.callJson(tab, "type", buildJsonObject { put("target", "#user"); put("text", "kim") })
        assertEquals(Reason.POST_FORM, IrreversibleAction.reasonForEnter(PageScript.call<FocusResult>(tab, "focusInfo").info))
        PageScript.callJson(tab, "type", buildJsonObject { put("target", "#sq"); put("text", "cats") })
        assertNull(IrreversibleAction.reasonForEnter(PageScript.call<FocusResult>(tab, "focusInfo").info))
    }

    @Test
    fun nativeKeysReachTheFocusedField() = runBlocking<Unit> {
        load("forms.html")
        PageScript.callJson(tab, "type", buildJsonObject { put("target", "#keys"); put("text", "") })
        withContext(Dispatchers.Main) { tab.key(KeyEvent.KEYCODE_A, 0) }
        delay(200)
        assertTrue(text("log"), "a:true" in text("log"))
        PageScript.callJson(tab, "type", buildJsonObject { put("target", "#sq"); put("text", "dogs") })
        withContext(Dispatchers.Main) { tab.key(KeyEvent.KEYCODE_ENTER, 0) }
        delay(300)
        assertTrue(text("log"), "submitted" in text("log"))
    }

    @Test
    fun selectContentEditableAndPageText() = runBlocking<Unit> {
        load("snapshot.html")
        val sel = PageScript.call<SelectResult>(tab, "select", buildJsonObject {
            put("target", "#color"); putJsonArray("values") { add(JsonPrimitive("blue")) }
        })
        assertEquals(listOf("Blue"), sel.selected)
        val t = PageScript.call<PageTextResult>(tab, "pageText")
        assertTrue(t.text, "Plain paragraph text." in t.text)
        assertFalse("hunter22" in t.text)
        assertEquals(1, t.pages)
        load("forms.html")
        val r = PageScript.call<TypeResult>(tab, "type", buildJsonObject { put("target", "#editor"); put("text", "new text") })
        assertEquals("new text", r.value)
    }

    @Test
    fun dialogsAreHeldAndAnswered() = runBlocking<Unit> {
        load("dialog.html")
        assertTrue(tab.consoleLines().any { "dialog page ready" in it })
        try {
            PageScript.callJson(tab, "click", buildJsonObject { put("target", "#ask") })
            fail("click should be cut short by confirm()")
        } catch (e: PageError) {
            assertTrue(e.message, "confirm dialog is open: \"Really delete?\"" in e.message!!)
        }
        val d = tab.dialog.value!!
        assertEquals(DialogKind.CONFIRM, d.kind)
        withContext(Dispatchers.Main) { tab.answerDialog(true, null) }
        assertEquals("confirm=true", text("r"))
        val at = locate("#name")
        withContext(Dispatchers.Main) { tab.tap(at.cx, at.cy, at.innerWidth) }
        delay(300)
        assertEquals(DialogKind.PROMPT, tab.dialog.value?.kind)
        assertEquals("anon", tab.dialog.value?.defaultText)
        withContext(Dispatchers.Main) { tab.answerDialog(true, "Kim") }
        assertEquals("prompt=Kim", text("r"))
    }

    @Test
    fun screenshotAndPdf() = runBlocking<Unit> {
        load("forms.html")
        val bmp = withContext(Dispatchers.Main) { tab.capture() }
        assertNotNull(bmp)
        val pdf = PdfPrinter.print(tab)
        assertEquals("%PDF", String(pdf, 0, 4))
    }

    /** Live DuckDuckGo HTML results (R8). Skipped without network. */
    @Test
    fun duckDuckGoExtractorOnTheRealSite() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { tab.load(SearchEngines.url(SearchEngine.DUCKDUCKGO, "android webview")) }
        assumeTrue("network", tab.awaitLoaded(20_000) && tab.url.contains("duckduckgo"))
        val results: List<SearchResult> = PageScript.search(tab, SearchEngine.DUCKDUCKGO, 8)
        assumeTrue("no CAPTCHA page", results.isNotEmpty() || !snapshot().contains("robot", ignoreCase = true))
        assertTrue("results: $results", results.size >= 3)
        results.forEach {
            assertTrue(it.url, it.url.startsWith("http") && "duckduckgo.com/l/" !in it.url)
            assertTrue(it.title.isNotBlank())
        }
        android.util.Log.i("AgentScriptTest", "DDG: " + results.joinToString(" | ") { "${it.title} <${it.url}>" })
    }
}
