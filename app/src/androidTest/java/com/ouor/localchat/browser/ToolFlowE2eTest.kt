package com.ouor.localchat.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.testing.Scenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
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
import java.util.Collections

/** Tool flows end to end (B's checklist, tools-redesign 7); the harness is [ToolFlowBase]. */
@RunWith(AndroidJUnit4::class)
class ToolFlowE2eTest : ToolFlowBase() {

    private val tickerVersion = java.util.concurrent.atomic.AtomicInteger()

    override fun respond(path: String, request: RecordedRequest): MockResponse? {
        // M1: a page in three parts; the ticker says something else on every load.
        if (path == "/long-read" || path == "/ticker") {
            val v = if (path == "/ticker") "Version ${tickerVersion.incrementAndGet()} " else ""
            return html("<!doctype html><title>Long read</title><main>" + (0 until 40).joinToString("") { "<p>${v}Para $it ${"x".repeat(950)}</p>" } + "</main>")
        }
        if (path == "/to-account") return MockResponse.Builder().code(302).addHeader("Location", SENSITIVE_URL).build()
        val body = PAGES[path] ?: return null
        val b = MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8")
        if (path == "/start") b.addHeader("Set-Cookie", "sid=$COOKIE; Path=/")
        return b.body(body).build()
    }

    @Test
    fun browseClickReadMultiStep() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"Next page\"")}"}"""),
            Scenario.toolCall("read", "{}"),
            Scenario.text("done"),
        )
        val sid = send("go")
        val reply = awaitEnd(sid)
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals(listOf("browse", "click", "read"), reply.calls().map { it.name })
        assertEquals(reply.results().joinToString { it.content }, List(3) { ToolCallState.DONE }, reply.calls().map { it.state })
        // Page content is wrapped as untrusted (B-S5); the app's own lines stay outside it (G-M1).
        reply.results().forEach { assertTrue(it.content, "<untrusted_page_content>" in it.content) }
        assertTrue(reply.results()[0].content, "link \"Next page\" [ref=" in reply.results()[0].content)
        // An action's result says what changed, outside the page, and lists the new page (tools-redesign 4).
        assertTrue(reply.results()[1].content, Regex("^Clicked e\\d+ → new page.\n<untrusted_page_content>").containsMatchIn(reply.results()[1].content))
        assertTrue(reply.results()[2].content, "Page /next body text" in reply.results()[2].content)
        val tab = browser.tabs.value.single()
        assertTrue(tab.url, tab.url.endsWith("/next"))
        assertEquals(Owner.Ai(sid), tab.owner)
        val sent = modelRequests()
        // sent[4], if any, is the title request.
        assertTrue(sent.size >= 4)
        assertTrue(sent[3], sent[3].contains("untrusted_page_content"))
    }

    @Test
    fun readUrlUsesAHiddenTabAndLeavesNone() {
        llm.enqueue(Scenario.toolCall("read", """{"url":"${url("/p1")}"}"""), Scenario.text("done"))
        val reply = awaitEnd(send("read p1"))
        assertEquals(ToolCallState.DONE, reply.calls().single().state)
        assertTrue(reply.results().single().content, "Page /p1 body text" in reply.results().single().content)
        assertTrue(browser.tabs.value.isEmpty())
    }

    @Test
    fun aPageReadToTheEndIsReadAgainFromTheStart() {
        // M1: a finished cursor does not answer "that was the last part"; the page is read anew.
        llm.enqueue(
            Scenario.toolCall("read", """{"url":"${url("/p1")}"}"""),
            Scenario.toolCall("read", """{"url":"${url("/p1")}"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("read twice"))
        reply.results().forEach { assertTrue(it.content, "Page /p1 body text" in it.content) }
        assertEquals(2, pageHits.count { it == "/p1" })
    }

    @Test
    fun readTakesAPathFromTheListOnTheTabsSite() {
        // QA-M2: same-site links are listed as paths; read opens one on the work tab's site.
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("read", """{"url":"/p1"}"""),
            Scenario.text("done"),
        )
        val read = awaitEnd(send("read a path")).results()[1].content
        assertTrue(read, "Page /p1 body text" in read)
    }

    @Test
    fun readingGoesOnInALaterTurnAndARegeneratedReplyStartsOver() {
        // M1: the next turn goes on from part 2 (same page); regenerating that reply starts from part 1 again.
        llm.enqueue(Scenario.toolCall("read", """{"url":"${url("/long-read")}"}"""), Scenario.text("first"))
        val sid = send("read it")
        assertTrue(awaitEnd(sid).results().single().content.let { "(part 1 of 3)" in it && "Para 0 " in it })
        awaitTitleRequest()
        llm.enqueue(Scenario.toolCall("read", """{"url":"${url("/long-read")}"}"""), Scenario.text("second"))
        sendTo(sid, "go on")
        val second = awaitEnd(sid).results().single().content
        assertTrue(second, "(part 2 of 3)" in second)
        val secondId = store.messages.values.last { it.sessionId == sid && it.body is MessageBody.Assistant }.id
        llm.enqueue(Scenario.toolCall("read", """{"url":"${url("/long-read")}"}"""), Scenario.text("again"))
        runBlocking { engine.regenerate(secondId, MODEL, GenOptions(browser = true)) }
        val again = runBlocking {
            withTimeout(30_000) {
                var r: MessageBody.Assistant? = null
                while (r == null) {
                    r = store.messages.values.lastOrNull { it.sessionId == sid && it.body is MessageBody.Assistant && it.id != secondId }
                        ?.let { it.body as MessageBody.Assistant }?.takeIf { it.status == ReplyStatus.DONE && it.calls().isNotEmpty() }
                    if (r == null) delay(100)
                }
                r
            }
        }
        assertTrue(again.results().single().content, "(part 1 of 3)" in again.results().single().content)
    }

    @Test
    fun aChangedPageIsReadFromTheStartInALaterTurn() {
        // M1: an earlier turn's position is kept only while the page says the same.
        llm.enqueue(Scenario.toolCall("read", """{"url":"${url("/ticker")}"}"""), Scenario.text("first"))
        val sid = send("read the ticker")
        assertTrue("(part 1 of 3)" in awaitEnd(sid).results().single().content)
        awaitTitleRequest()
        llm.enqueue(Scenario.toolCall("read", """{"url":"${url("/ticker")}"}"""), Scenario.text("second"))
        sendTo(sid, "again")
        val second = awaitEnd(sid).results().single().content
        assertTrue(second, "(part 1 of 3)" in second && "Version 2 Para 0 " in second)
    }

    @Test
    fun actionsWithoutATabSayToBrowseFirst() {
        llm.enqueue(Scenario.toolCall("click", """{"ref":"e1"}"""), Scenario.text("done"))
        val reply = awaitEnd(send("click"))
        assertEquals(ToolCallState.FAILED, reply.calls().single().state)
        assertTrue(reply.results().single().content, BrowserEngine.NO_TAB in reply.results().single().content)
        assertTrue(browser.tabs.value.isEmpty())
    }

    @Test
    fun cookieValuesAreMaskedInResults() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("execute", """{"code":"document.cookie"}"""),
            Scenario.toolCall("read", "{}"),
            Scenario.text("done"),
        )
        val sid = send("cookies")
        val p = awaitPause(sid) as Pause.Approval // execute: ALWAYS
        assertEquals("document.cookie", p.request.code)
        engine.resolve(sid, Resolution.ApprovalAnswer(p.id, allow = true))
        val reply = awaitEnd(sid)
        val (exec, text) = reply.results().drop(1)
        listOf(exec, text).forEach { assertFalse(it.content, COOKIE in it.content) }
        assertTrue(exec.content, "[cookie]" in exec.content)
        assertTrue(text.content, "[cookie]" in text.content)
        assertTrue(modelRequests().none { COOKIE in it })
    }

    @Test
    fun conditionalAndAlwaysApprovals() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/form")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("button \"Show more\"")}"}"""), // harmless button: no approval
            Scenario.toolCall("type", """{"ref":"${Scenario.ref("textbox")}","text":"hello"}"""), // http page: approval every time
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("button \"Send\"")}"}"""), // POST submit: denied
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("button \"Delete account\"")}"}"""), // risky keyword: allowed
            Scenario.toolCall("execute", """{"code":"document.getElementById('q').value"}"""), // always
            Scenario.text("done"),
        )
        val sid = send("form")
        val type = awaitPause(sid) as Pause.Approval
        assertEquals("type", type.request.toolName)
        assertEquals("INSECURE_INPUT", type.request.reason?.name)
        engine.resolve(sid, Resolution.ApprovalAnswer(type.id, allow = true))
        val post = awaitPause(sid) as Pause.Approval
        assertEquals("click", post.request.toolName)
        assertEquals("POST_FORM", post.request.reason?.name)
        engine.resolve(sid, Resolution.ApprovalAnswer(post.id, allow = false))
        val del = awaitPause(sid) as Pause.Approval
        assertEquals("RISKY_KEYWORD", del.request.reason?.name)
        // SC-13: the approval carries a crop of the target element (null when none could be made).
        assertEquals("artifact-approval-preview.jpg", del.request.previewId)
        engine.resolve(sid, Resolution.ApprovalAnswer(del.id, allow = true))
        val exec = awaitPause(sid) as Pause.Approval
        assertEquals("execute", exec.request.toolName)
        engine.resolve(sid, Resolution.ApprovalAnswer(exec.id, allow = true))
        val reply = awaitEnd(sid)
        assertEquals(
            reply.results().joinToString { it.content },
            listOf(ToolCallState.DONE, ToolCallState.DONE, ToolCallState.DONE, ToolCallState.DENIED, ToolCallState.DONE, ToolCallState.DONE),
            reply.calls().map { it.state },
        )
        // A field outside a search box is not submitted; the result points at the submit button.
        assertTrue(reply.results()[2].content, Regex("Not submitted; the form's submit button is e\\d+\\.").containsMatchIn(reply.results()[2].content))
        assertTrue(reply.results()[3].content, ToolOutcome.DENIED_TEXT in reply.results()[3].content)
        assertTrue(reply.results()[4].content, "button \"gone\"" in reply.results()[4].content)
        assertTrue(reply.results()[5].content, "hello" in reply.results()[5].content)
        assertTrue("POST must not reach the server", pageHits.none { it == "/submit" })
    }

    @Test
    fun typeInASearchBoxPressesEnter() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/search")}"}"""),
            Scenario.toolCall("type", """{"ref":"${Scenario.ref("searchbox")}","text":"lamps"}"""),
            Scenario.text("done"),
        )
        val sid = send("search")
        val p = awaitPause(sid) as Pause.Approval // http page
        engine.resolve(sid, Resolution.ApprovalAnswer(p.id, allow = true))
        val reply = awaitEnd(sid)
        val typed = reply.results()[1].content
        assertTrue(typed, Regex("^Typed \"lamps\" into e\\d+ and pressed Enter → new page\\.").containsMatchIn(typed))
        assertTrue(typed, "Page /results body text" in typed)
        assertTrue(pageHits.toString(), pageHits.any { it == "/results" })
    }

    @Test
    fun typeInASearchBoxWaitsForAScriptHandledSubmit() {
        // The Enter of a search box is often handled by the page's script, which navigates a moment later (300 ms
        // here). The wait after Enter must start at the Enter, not at the typing before it.
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/search-js")}"}"""),
            Scenario.toolCall("type", """{"ref":"${Scenario.ref("searchbox")}","text":"lamps"}"""),
            Scenario.text("done"),
        )
        val sid = send("search")
        val p = awaitPause(sid) as Pause.Approval // http page
        engine.resolve(sid, Resolution.ApprovalAnswer(p.id, allow = true))
        val typed = awaitEnd(sid).results()[1].content
        assertTrue(typed, Regex("^Typed \"lamps\" into e\\d+ and pressed Enter → new page\\.").containsMatchIn(typed))
        assertTrue(typed, "Page /results body text" in typed)
    }

    @Test
    fun handoffRoundTrip() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("request_handoff", """{"reason":"Please sign in"}"""),
            Scenario.text("done"),
        )
        val sid = send("handoff")
        val h = awaitPause(sid) as Pause.Handoff
        assertEquals("Please sign in", h.reason)
        // The loop calls setControl from a worker thread; the engine applies it on main, so `tabs` catches up later.
        awaitControl(h.tabId, Control.USER)
        engine.resolve(sid, Resolution.HandoffReturned)
        val reply = awaitEnd(sid)
        awaitControl(h.tabId, Control.AI)
        // The result is the page as the user left it (tools-redesign 3-7).
        val back = reply.results()[1].content
        assertTrue(back, back.startsWith("The user gave the tab back. They may or may not have finished; check the page below.\n<untrusted_page_content>"))
        assertTrue(back, "link \"Next page\" [ref=" in back)
        assertEquals(ReplyStatus.DONE, reply.status)
    }

    @Test
    fun waitsWhileUserControlsTheTab() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("execute", """{"code":"location.pathname"}"""),
            Scenario.text("done"),
        )
        val sid = send("control")
        val p = awaitPause(sid) as Pause.Approval // sync point between steps
        val tabId = browser.tabs.value.single().id
        runBlocking(Dispatchers.Main) { browser.setControl(tabId, Control.USER) }
        engine.resolve(sid, Resolution.ApprovalAnswer(p.id, allow = true))
        assertEquals(Pause.UserControl(tabId), awaitPause(sid))
        runBlocking(Dispatchers.Main) { browser.setControl(tabId, Control.AI) }
        val reply = awaitEnd(sid)
        assertEquals(ToolCallState.DONE, reply.calls()[1].state)
        assertTrue(reply.results()[1].content, "/start" in reply.results()[1].content)
    }

    @Test
    fun toolCapableModelWithSwitchesOffGetsNoToolsAndCannotOpenATab() {
        // The model tries anyway: the call is not in the exposed list, so nothing opens.
        llm.enqueue(Scenario.toolCall("browse", """{"url":"${url("/start")}"}"""), Scenario.text("plain"))
        val sid = send("open the start page", browserOn = false)
        val reply = awaitEnd(sid)
        assertFalse(modelRequests().first().contains("\"tools\""))
        assertTrue(reply.calls().none { it.state == ToolCallState.DONE })
        assertTrue("/start" !in pageHits)
        assertTrue(browser.tabs.value.isEmpty())
    }

    @Test
    fun regenerateWithSwitchesOffSendsNoTools() {
        llm.enqueue(Scenario.toolCall("browse", """{"url":"${url("/p1")}"}"""), Scenario.text("first"))
        val sid = send("open p1")
        val first = store.messages.values.last { it.sessionId == sid && it.body is MessageBody.Assistant }
        assertEquals(listOf("browse"), awaitEnd(sid).calls().map { it.name })
        modelRequests()
        llm.enqueue(Scenario.text("second"))
        runBlocking { engine.regenerate(first.id, MODEL, GenOptions()) }
        val second = runBlocking {
            withTimeout(30_000) {
                var done: MessageBody.Assistant? = null
                while (done == null) {
                    done = store.messages.values.lastOrNull { it.sessionId == sid && it.body is MessageBody.Assistant && it.id != first.id }
                        ?.let { it.body as MessageBody.Assistant }?.takeIf { it.status == ReplyStatus.DONE }
                    if (done == null) kotlinx.coroutines.delay(100)
                }
                done
            }
        }
        assertTrue(second.calls().isEmpty())
        // The regenerated turn is the last request (the first run's may still be queued).
        assertFalse(modelRequests().last().contains("\"tools\""))
    }

    @Test
    fun linkPrefetchReadsThreeAndSkipsSensitive() {
        caps = caps(tools = false)
        llm.enqueue(Scenario.text("summary"))
        val text = "read https://accounts.google.com/signin ${url("/p1")} ${url("/p2")} ${url("/p3")} ${url("/p4")}"
        val sid = send(text, browserOn = false)
        val reply = awaitEnd(sid)
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals(listOf(url("/p1"), url("/p2"), url("/p3")), attachments.saved.values.map { it.attachment.name })
        attachments.saved.values.forEach { assertTrue(it.text!!.startsWith("<untrusted_page_content>")) }
        assertTrue("/p4" !in pageHits)
        assertTrue("hidden tabs closed", browser.tabs.value.isEmpty())
        assertTrue(modelRequests().first().contains("Page /p3 body text"))
    }

    @Test
    fun typeIsRefusedInPasswordFieldAndFramesAsk() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/login")}"}"""),
            Scenario.toolCall("type", """{"ref":"${Scenario.ref("[sensitive]")}","text":"a"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("iframe")}"}"""), // opaque frame: approval (denied)
            Scenario.text("done"),
        )
        val sid = send("login")
        val frame = awaitPause(sid) as Pause.Approval
        assertEquals("FRAME", frame.request.reason?.name)
        engine.resolve(sid, Resolution.ApprovalAnswer(frame.id, allow = false))
        val reply = awaitEnd(sid)
        assertEquals(reply.results().joinToString { it.content }, listOf(ToolCallState.DONE, ToolCallState.FAILED, ToolCallState.DENIED), reply.calls().map { it.state })
        assertTrue(reply.results()[1].content, "Refused" in reply.results()[1].content)
    }

    @Test
    fun acquireAiTabMarksActivityAndWaitsForControl() = runBlocking<Unit> {
        val tab = browser.acquireAiTab("s1", "browse", navigating = true)
        assertEquals("browse", browser.aiActivity.value[tab.id])
        browser.setControl(tab.id, Control.USER) // from a worker thread: posted to main (F-10)
        withTimeout(5_000) { browser.tabs.first { l -> l.any { it.id == tab.id && it.control == Control.USER } } }
        val events = Collections.synchronizedList(mutableListOf<String>())
        val waiting = async(Dispatchers.Default) {
            browser.acquireAiTab("s1", "click", navigating = false, onUserControl = { events += "user $it" }, onResumed = { events += "resumed" })
        }
        withTimeout(5_000) { while (events.isEmpty()) delay(50) }
        assertFalse(waiting.isCompleted)
        browser.setControl(tab.id, Control.AI)
        assertEquals(tab.id, withTimeout(5_000) { waiting.await() }.id)
        assertEquals(listOf("user ${tab.id}", "resumed"), events.toList())
        assertEquals("click", browser.aiActivity.value[tab.id])
        browser.clearAiActivity("s1")
        withTimeout(5_000) { browser.aiActivity.first { tab.id !in it } }
    }

    @Test
    fun onlyBrowseCreatesTheWorkTab() = runBlocking<Unit> {
        assertTrue(runCatching { browser.acquireAiTab("s1", "click", navigating = false) }.exceptionOrNull()?.message == BrowserEngine.NO_TAB)
        assertTrue(browser.tabs.value.isEmpty())
        val tab = browser.acquireAiTab("s1", "browse", navigating = true)
        assertEquals(tab.id, browser.acquireAiTab("s1", "click", navigating = false).id) // one work tab per chat
        assertEquals(listOf(tab.id), browser.tabs.value.map { it.id })
    }

    @Test
    fun prefetchTabStaysHiddenAndNeverBecomesTheWorkTab() = runBlocking<Unit> {
        // FB3 eng M2, arch M1.
        val work = browser.acquireAiTab("s1", "browse", navigating = true)
        val hidden = withContext(Dispatchers.Main) { browser.openAiTab("s1", url("/p1"), hidden = true) }
        assertEquals(work.id, withContext(Dispatchers.Main) { browser.workTab("s1") })
        assertEquals(listOf(work.id), browser.visibleTabs.value.map { it.id })
        assertEquals(listOf(work.id), browser.sessionTabs("s1").first().map { it.id })
        assertTrue(browser.tabs.value.any { it.id == hidden })
    }

    @Test
    fun prefetchTabsDoNotTakeTheUsersTabSlots() = runBlocking<Unit> {
        // FB3 arch M1: hidden tabs have their own cap and never fill the visible limit.
        withContext(Dispatchers.Main) {
            repeat(TabRules.MAX_HIDDEN_TABS) { browser.openAiTab("s1", "about:blank", hidden = true) }
            repeat(BrowserEngine.MAX_TABS) { browser.openUserTab("about:blank") }
            assertTrue(runCatching { browser.openUserTab("about:blank") }.exceptionOrNull() is BrowserRefusal)
            assertTrue(runCatching { browser.openAiTab("s1", "about:blank", hidden = true) }.exceptionOrNull() is BrowserRefusal)
        }
        assertEquals(BrowserEngine.MAX_TABS, browser.visibleTabs.value.size)
    }

    @Test
    fun aHiddenTabWaitsForAFreeSlotAndStopsWaitingOnCancel() = runBlocking<Unit> {
        // B-m9 and its cancel: at the limit the call waits for a slot; a stop ends the wait at once.
        val held = withContext(Dispatchers.Main) { List(TabRules.MAX_HIDDEN_TABS) { browser.openAiTab("s1", "about:blank", hidden = true) } }
        var got: Int? = null
        val waiting = async(Dispatchers.Default) { browser.openHiddenTab("s2", "about:blank") { got = it } }
        delay(600)
        assertFalse(waiting.isCompleted)
        withContext(Dispatchers.Main) { browser.close(held[0]) }
        withTimeout(2_000) { waiting.await() }
        assertTrue(browser.tabs.value.any { it.id == got && it.hidden })
        val cancelled = async(Dispatchers.Default) { browser.openHiddenTab("s3", "about:blank") { error("opened while full") } }
        delay(300)
        val t = System.currentTimeMillis()
        cancelled.cancel()
        withTimeout(1_000) { cancelled.join() }
        assertTrue("stopped in ${System.currentTimeMillis() - t} ms", System.currentTimeMillis() - t < 1_000)
        assertEquals(TabRules.MAX_HIDDEN_TABS, browser.tabs.value.count { it.hidden })
    }

    @Test
    fun aHiddenTabsDialogsAreRefusedAtOnce() = runBlocking<Unit> {
        // FB3 ui m6: nobody sees a prefetch tab, so alert()/confirm() must not hold the page.
        val hidden = withContext(Dispatchers.Main) { browser.openAiTab("s1", url("/alert"), hidden = true) }
        withTimeout(10_000) { browser.tabs.first { l -> l.any { it.id == hidden && it.title == "After" } } }
        assertTrue(browser.prompts.value.isEmpty())
    }

    @Test
    fun tabsAndActivityPerChatAndCloseOnDelete() = runBlocking<Unit> {
        val a = browser.acquireAiTab("chatA", "browse", navigating = true)
        val b = browser.acquireAiTab("chatB", "browse", navigating = true)
        browser.acquireAiTab("chatB", "click", navigating = false)
        assertEquals(listOf(a.id), browser.sessionTabs("chatA").first().map { it.id })
        assertEquals(mapOf(b.id to "click"), browser.sessionActivity("chatB").first())
        browser.clearAiActivity("chatB")
        withTimeout(5_000) { browser.sessionActivity("chatB").first { it.isEmpty() } }
        assertEquals(mapOf(a.id to "browse"), browser.sessionActivity("chatA").first()) // other chat untouched
        browser.closeSessionTabs("chatA") // from a worker thread
        withTimeout(5_000) { browser.sessionTabs("chatA").first { it.isEmpty() } }
        assertEquals(listOf(b.id), browser.tabs.value.map { it.id })
        assertTrue(a.id !in browser.aiActivity.value)
    }

    @Test
    fun readRefusesARedirectToASensitiveSiteAndKeepsNothing() {
        // F1 (A-M1, B-M1): /to-account answers 302 to a sign-in page; the second call must not be served from a cache.
        llm.enqueue(
            Scenario.toolCall("read", """{"url":"${url("/to-account")}"}"""),
            Scenario.toolCall("read", """{"url":"${url("/to-account")}"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("read it"))
        assertEquals(reply.results().joinToString { it.content }, List(2) { ToolCallState.FAILED }, reply.calls().map { it.state })
        reply.results().forEach { r ->
            android.util.Log.i("ToolFlowE2e", "read via redirect: ${r.content}")
            assertTrue(r.content, "accounts.google.com" in r.content || r.content == com.ouor.localchat.chat.run.ToolExecutor.SENSITIVE)
            assertFalse(r.content, "<untrusted_page_content>" in r.content)
            assertTrue(r.sources.isEmpty())
        }
        assertEquals(pageHits.toString(), 2, pageHits.count { it == "/to-account" })
        assertTrue(browser.tabs.value.isEmpty())
    }

    @Test
    fun aStaleRefIsAnsweredWithTheNewList() {
        // F7: refs of the old page are never reused; the error carries the current list.
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("click", """{"ref":"${Scenario.ref("link \"Next page\"")}"}"""),
            Scenario.toolCall("click", """{"ref":"e1"}"""),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("stale"))
        val stale = reply.results()[2]
        assertTrue(stale.content, stale.isError)
        assertTrue(stale.content, stale.content.startsWith("Ref e1 is from an earlier page of this tab, not this one. Use a ref from the element list below."))
        assertTrue(stale.content, "Page: Next" in stale.content)
    }

    @Test
    fun browseWithoutUrlListsWhatTheLastListLeftOut() {
        // F9 (G-M3): a long page is listed in parts; browse() continues where the last list stopped.
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/long")}"}"""),
            Scenario.toolCall("browse", "{}"),
            Scenario.text("done"),
        )
        val reply = awaitEnd(send("long"))
        val (first, second) = reply.results().map { it.content }
        assertTrue(first, "browse() with no url lists the remaining elements" in first)
        assertTrue(first, "button \"Item 1\" [" in first)
        assertTrue(second, "button \"Item 1\" [" !in second)
        assertTrue(second, "button \"Item 699\"" in second)
    }

    private companion object {
        const val SENSITIVE_URL = "https://accounts.google.com/signin"
        const val COOKIE = "SECRETCOOKIE123"
        val PAGES = mapOf(
            "/start" to """<!doctype html><title>Start</title><a id="go" href="/next">Next page</a>
                <p id="c"></p><script>document.getElementById('c').textContent = 'cookie: ' + document.cookie</script>""",
            "/next" to "<!doctype html><title>Next</title><p>Page /next body text</p>",
            "/alert" to """<!doctype html><title>Before</title><script>alert('hi'); confirm('ok?'); document.title = 'After'</script>""",
            "/login" to """<!doctype html><title>Login</title><input id="user"><input id="pw" type="password">
                <iframe id="f" src="data:text/html,<button>Pay</button>" style="width:300px;height:80px"></iframe>""",
            "/long" to "<!doctype html><title>Long</title>" + (0 until 700).joinToString("") { "<button>Item $it</button>" },
            "/search-js" to """<!doctype html><title>Search</title><form id="f" action="/results"><input id="q" type="search" name="q"></form>
                <script>document.getElementById('f').addEventListener('submit', function (e) {
                    e.preventDefault(); setTimeout(function () { location.assign('/results?q=' + encodeURIComponent(document.getElementById('q').value)) }, 300)
                })</script>""",
            "/search" to """<!doctype html><title>Search</title><form action="/results"><input id="q" type="search" name="q"></form>""",
            "/form" to """<!doctype html><title>Form</title>
                <button id="plain" onclick="this.textContent='pressed'">Show more</button>
                <form method="post" action="/submit"><input id="q" name="msg"><button id="send" type="submit">Send</button></form>
                <button id="del" onclick="this.textContent='gone'">Delete account</button>""",
        )
    }
}
