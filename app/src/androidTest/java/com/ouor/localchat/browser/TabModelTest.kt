package com.ouor.localchat.browser

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.MainActivity
import com.ouor.localchat.testing.ScreenOnRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** Layer 1 on a device (browser-layers 10-2): tabs without owners, front, sleep and wake, events, popups, downloads. */
@RunWith(AndroidJUnit4::class)
class TabModelTest {
    @get:Rule val screen = ScreenOnRule()

    private lateinit var pages: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var engine: BrowserEngine

    @Before fun setUp() {
        pages = MockWebServer()
        pages.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request.url.encodedPath)
        }
        pages.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { engine = it.browserEngine }
        runBlocking(Dispatchers.Main) { engine.tabs.value.forEach { engine.close(it.id) } }
    }

    @After fun tearDown() {
        runBlocking(Dispatchers.Main) { engine.tabs.value.forEach { engine.close(it.id) } }
        scenario.close()
        pages.close()
    }

    private fun html(body: String) = MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

    private fun respond(path: String): MockResponse = when (path) {
        "/links" -> html("""<!doctype html><title>Links</title>
            <a id="mail" href="mailto:someone@example.com" style="display:block;height:60px">Mail</a>
            <a id="blank" href="/child" target="_blank" style="display:block;height:60px">Blank</a>
            <button id="pop" style="display:block;height:60px" onclick="window.open('/child')">Pop</button>
            <button id="data" style="display:block;height:60px" onclick="location.href='data:application/octet-stream;base64,aGVsbG8='">Data</button>
            <button id="blob" style="display:block;height:60px" onclick="var a=document.createElement('a');a.href=window.URL.createObjectURL(new Blob(['blob body'],{type:'application/octet-stream'}));a.download='b.bin';document.body.appendChild(a);a.click()">Blob</button>
            <a id="file" href="/file" style="display:block;height:60px">File</a>""")
        "/file" -> MockResponse.Builder().addHeader("Content-Type", "text/plain")
            .addHeader("Content-Disposition", "attachment; filename*=UTF-8''%ED%85%8C%EC%8A%A4%ED%8A%B8.txt").body("file body").build()
        "/rows" -> html("<!doctype html><meta name=\"viewport\" content=\"width=device-width\"><title>Rows</title>" +
            (0 until 80).joinToString("") { "<a href=\"/row$it\" style=\"display:block;height:40px\">Row $it</a>" } + "<a id=\"more\" href=\"/more\">More</a>")
        else -> html("<!doctype html><title>${path.trim('/')}</title><p>Page $path</p>")
    }

    private fun url(path: String) = pages.url(path).toString()

    private fun <T> main(block: suspend () -> T): T = runBlocking(Dispatchers.Main) { block() }

    private fun openReady(path: String): BrowserTab = main {
        val t = engine.tab(engine.open(url(path)))
        t.awaitReady(SystemClock.uptimeMillis())
        t
    }

    private fun load(tab: BrowserTab, path: String) = main {
        val since = SystemClock.uptimeMillis()
        tab.load(url(path))
        tab.awaitReady(since)
    }

    /** Injected (AI) tap on the centre of element [id]. */
    private fun tapOn(tab: BrowserTab, id: String) = main {
        val box = tab.evaluate("(function(){var r=document.getElementById('$id').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,innerWidth].join(',')})()", 5_000)
            .trim('"').split(',').map { it.toFloat() }
        assertEquals(BrowserTab.Tap.DONE, tab.tap(box[0], box[1], box[2]))
    }

    private fun <T> eventually(what: String, timeoutMs: Long = 10_000, get: () -> T?): T {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            main { get() }?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("timed out: $what")
    }

    /**
     * QA B1: after a script scroll, the AI's tap lands on the element in a parked tab and in a shown one. The tap does
     * not give the WebView focus (taking focus moved the page back to the top before the click, so a row was clicked).
     */
    @Test fun aTapAfterAScrollLandsOnTheElementAndTakesNoFocus() {
        for (shown in listOf(false, true)) {
            val tab = openReady("/rows")
            if (shown) main { engine.show(tab.id) }
            try {
                val w = main { tab.webView.value!! }
                main { tab.evaluate("document.getElementById('more').scrollIntoView({block:'center',behavior:'instant'})", 5_000) }
                Thread.sleep(300)
                tapOn(tab, "more")
                eventually("shown=$shown: More reached") { tab.url.takeIf { it.endsWith("/more") } }
                assertFalse("shown=$shown: no focus taken", main { w.hasFocus() })
            } finally {
                if (shown) main { engine.park(tab.id) }
                main { engine.close(tab.id) }
            }
        }
    }

    @Test fun openDoesNotMoveTheFrontAndClosingTheFrontPicksItsNeighbour() {
        val a = openReady("/a"); val b = openReady("/b"); val c = openReady("/c")
        main { engine.select(b.id) }
        main { engine.open(url("/d")) }
        assertEquals("open keeps the front", b.id, engine.front.value?.tabId)
        assertEquals(4, engine.tabs.value.size)
        main { engine.close(b.id) }
        assertEquals("right neighbour", c.id, engine.front.value?.tabId)
        val d = engine.tabs.value.last().id
        main { engine.close(d) }
        main { engine.select(c.id); engine.close(c.id) }
        assertEquals("no right neighbour: the left one", a.id, engine.front.value?.tabId)
        main { engine.close(a.id) }
        assertNull(engine.front.value)
    }

    @Test fun tabNumbersGoOnAfterANewCounter() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val first = IdCounter(ctx).next()
        assertEquals(first + 1, IdCounter(ctx).next())
        val t = openReady("/a")
        assertTrue("engine numbers come from the same counter", t.id > first + 1)
    }

    @Test fun discardKeepsTheEntryAndWakeRestoresHistory() {
        val tab = openReady("/a")
        load(tab, "/b")
        assertTrue(main { engine.discard(tab.id) })
        val info = engine.tabs.value.single { it.id == tab.id }
        assertEquals(Asleep.DISCARDED, info.asleep)
        assertEquals(url("/b"), info.url)
        assertEquals("b", info.title)
        assertNull(tab.webView.value)
        assertFalse("asleep already", main { engine.discard(tab.id) })
        main {
            val since = SystemClock.uptimeMillis()
            assertTrue(tab.wake())
            tab.awaitReady(since)
        }
        assertTrue("same BrowserTab", main { engine.tab(tab.id) } === tab)
        assertNotNull(tab.webView.value)
        assertTrue("back/forward list restored", main { tab.canGoBack })
        main { val since = SystemClock.uptimeMillis(); tab.goBack(); tab.awaitReady(since) }
        assertEquals(url("/a"), main { tab.url })
    }

    @Test fun aLockedTabIsNotDiscardedAndActionsTakeTurns() = runBlocking {
        val tab = openReady("/a")
        val order = Collections.synchronizedList(mutableListOf<String>())
        val first = async(Dispatchers.Default) { tab.lock.withLock { order += "1 in"; assertFalse(main { engine.discard(tab.id) }); delay(300); order += "1 out" } }
        delay(50)
        val second = async(Dispatchers.Default) { tab.lock.withLock { order += "2 in"; order += "2 out" } }
        first.await(); second.await()
        assertEquals(listOf("1 in", "1 out", "2 in", "2 out"), order)
    }

    private fun confirmIn(tab: BrowserTab) =
        main { tab.evaluate("setTimeout(function(){document.title='c='+confirm('Sure?')},0);1", 5_000) }

    @Test fun dialogsGoToTheListenerThenTheShownTabsUserElseAreDismissed() {
        val tab = openReady("/a")
        // a listener answers
        val l = engine.listen(tab.id) { e, _ -> if (e is BrowserEvent.Dialog) Answer.Accept() else null }
        confirmIn(tab)
        eventually("listener accepted") { tab.title.takeIf { it == "c=true" } }
        l.close()
        // a background tab: dismissed at once and recorded
        val since = SystemClock.uptimeMillis()
        confirmIn(tab)
        eventually("dismissed") { tab.title.takeIf { it == "c=false" } }
        val h = main { tab.eventsSince(since) }.single { it.event is BrowserEvent.Dialog }
        assertEquals(Answer.Dismiss, h.answer)
        // the shown tab: the user answers
        main { engine.show(tab.id) }
        try {
            confirmIn(tab)
            val e = eventually("prompt listed") { engine.prompts.value.firstOrNull { it.tabId == tab.id } }
            assertTrue(engine.tabs.value.single { it.id == tab.id }.pending)
            engine.answer(e.id, Answer.Accept())
            eventually("user accepted") { tab.title.takeIf { it == "c=true" } }
            assertTrue(engine.prompts.value.isEmpty())
        } finally {
            main { engine.park(tab.id) }
        }
    }

    @Test fun anInjectedTapDoesNotLeaveTheApp() {
        val tab = openReady("/links")
        val since = SystemClock.uptimeMillis()
        tapOn(tab, "mail")
        val h = eventually("external link recorded") { tab.eventsSince(since).firstOrNull { it.event is BrowserEvent.ExternalLink } }
        assertFalse((h.event as BrowserEvent.ExternalLink).userInput)
        assertEquals(Answer.Dismiss, h.answer)
    }

    @Test fun popupsOpenNewTabsWithAnOpenerAndKeepTheFront() {
        val tab = openReady("/links")
        main { engine.select(tab.id) }
        tapOn(tab, "pop")
        val pop = eventually("window.open tab") { engine.tabs.value.firstOrNull { it.id != tab.id && it.url.endsWith("/child") && !it.loading } }
        assertEquals("an injected gesture does not change the front", tab.id, engine.front.value?.tabId)
        assertEquals("true", main { engine.tab(pop.id).evaluate("String(!!window.opener)", 5_000) }.trim('"'))
        main { engine.close(pop.id) }
        tapOn(tab, "blank")
        eventually("target=_blank tab") { engine.tabs.value.firstOrNull { it.id != tab.id && it.url.endsWith("/child") } }
        assertEquals(2, engine.tabs.value.size)
    }

    @Test fun dataAndBlobDownloadsAreSavedAndHttpNamesAreRead() {
        val tab = openReady("/links")
        val since = SystemClock.uptimeMillis()
        tapOn(tab, "data")
        val data = eventually("data: saved") { tab.downloadsSince(since).firstOrNull { it.url.startsWith("data:") && it.state != DownloadNote.State.ASKED } }
        assertEquals(DownloadNote.State.SAVED, data.state)
        assertEquals(5L, data.size)
        tapOn(tab, "blob")
        val blob = eventually("blob: saved", 15_000) { tab.downloadsSince(since).firstOrNull { it.url.startsWith("blob:") && it.state != DownloadNote.State.ASKED } }
        assertEquals("${blob.reason}", DownloadNote.State.SAVED, blob.state)
        tapOn(tab, "file")
        val file = eventually("http download started") { tab.downloadsSince(since).firstOrNull { it.url.endsWith("/file") && it.state != DownloadNote.State.ASKED } }
        assertEquals("테스트.txt", file.fileName)
        assertTrue("${file.state} ${file.reason}", file.state == DownloadNote.State.STARTED || file.state == DownloadNote.State.SAVED)
        Log.i("TabModel", "http download: ${file.state}")
    }

    @Test fun aRendererDeathKeepsTheTabAndTheSecondInARowWaits() {
        val tab = openReady("/a")
        main { tab.rendererGone(crashed = true) }
        assertEquals(Asleep.CRASHED, engine.tabs.value.single { it.id == tab.id }.asleep)
        assertNull("not shown: waits for the next use", tab.webView.value)
        main { val since = SystemClock.uptimeMillis(); assertTrue(tab.wake()); tab.awaitReady(since) }
        assertEquals("a", main { tab.title })
        load(tab, "/b")
        // The back/forward list outlives a renderer death (saved before the WebView goes).
        main { tab.rendererGone(crashed = true) }
        main { val since = SystemClock.uptimeMillis(); tab.wake(); tab.awaitReady(since) }
        assertTrue(main { tab.webView.value!!.canGoBack() })
        load(tab, "/c")
        // shown: the first death reloads at once, the second in a row on the same URL does not
        main { engine.show(tab.id) }
        try {
            main { tab.rendererGone(crashed = true) }
            eventually("reloaded") { tab.webView.value?.takeIf { !tab.loading.value } }
            assertNull(main { tab.asleep })
            main { tab.rendererGone(crashed = true) }
            Thread.sleep(500)
            assertEquals(Asleep.CRASHED, main { tab.asleep })
            val gone = main { tab.eventsSince(0) }.filter { it.event is BrowserEvent.RendererGone }
            assertEquals(listOf(false, false, false, true), gone.map { (it.event as BrowserEvent.RendererGone).repeated })
        } finally {
            main { engine.park(tab.id) }
        }
    }

    /** 추정 check (browser-layers 2-1): a real renderer crash reaches every tab, and each stays in the list. */
    @Test fun aRealCrashPutsEveryTabToSleep() {
        val a = openReady("/a"); val b = openReady("/b")
        main { a.load("chrome://crash") }
        runBlocking { withTimeout(15_000) { engine.tabs.first { l -> l.size == 2 && l.all { it.asleep == Asleep.CRASHED } } } }
        Log.i("TabModel", "renderer crash: both tabs CRASHED (${a.id}, ${b.id})")
    }

    /** 추정 check (browser-layers 2-4): a user's tap reaches onCreateWindow well inside USER_INPUT_MS, so its popup is the user's. */
    @Test fun aUsersTapOpensAUsersPopup() {
        val tab = openReady("/links")
        val since = SystemClock.uptimeMillis()
        val box = main { tab.evaluate("(function(){var r=document.getElementById('pop').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,innerWidth].join(',')})()", 5_000) }
            .trim('"').split(',').map { it.toFloat() }
        val touchAt = main {
            val w = tab.webView.value!!
            val scale = w.width / box[2]
            val t = SystemClock.uptimeMillis()
            // As the drawer forwards a finger (BrowserSurface): straight to the WebView, not injected.
            listOf(android.view.MotionEvent.ACTION_DOWN to t, android.view.MotionEvent.ACTION_UP to t + 50).forEach { (action, at) ->
                val e = android.view.MotionEvent.obtain(t, at, action, box[0] * scale, box[1] * scale, 0)
                w.dispatchTouchEvent(e)
                e.recycle()
            }
            t
        }
        val h = eventually("popup") { tab.eventsSince(since).firstOrNull { it.event is BrowserEvent.Popup } }
        Log.i("TabModel", "user popup: onCreateWindow ${h.event.at - touchAt} ms after the touch (USER_INPUT_MS ${BrowserTab.USER_INPUT_MS})")
        assertTrue((h.event as BrowserEvent.Popup).userInput)
    }

    /** 추정 check (browser-layers 2-1): what saveState gives on a WebView whose renderer is gone. */
    @Test fun saveStateAfterRendererDeath() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val result = kotlinx.coroutines.CompletableDeferred<String>()
        val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        main {
            val w = WebViewFactory.create(ctx)
            w.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView, u: String) { if (u.endsWith("/b")) loaded.complete(Unit) }
                override fun onRenderProcessGone(view: android.webkit.WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    val r = runCatching { view.saveState(android.os.Bundle()) }
                    result.complete(r.fold({ l -> "returned ${l?.let { "a list of ${it.size}" } ?: "null"}" }, { e -> "threw ${e.javaClass.simpleName}" }))
                    view.destroy()
                    return true
                }
            }
            w.loadUrl(url("/a"))
            delay(1_000)
            w.loadUrl(url("/b"))
            withTimeout(10_000) { loaded.await() }
            w.loadUrl("chrome://crash")
        }
        val r = runBlocking { withTimeout(15_000) { result.await() } }
        Log.i("TabModel", "saveState after renderer death: $r")
    }
}
