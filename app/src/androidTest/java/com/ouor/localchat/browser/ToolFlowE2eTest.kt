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

    override fun respond(path: String, request: RecordedRequest): MockResponse? {
        val body = PAGES[path] ?: return null
        val b = MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8")
        if (path == "/start") b.addHeader("Set-Cookie", "sid=$COOKIE; Path=/")
        return b.body(body).build()
    }

    @Test
    fun browseClickReadMultiStep() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("click", """{"ref":"#go"}"""),
            Scenario.toolCall("read", "{}"),
            Scenario.text("done"),
        )
        val sid = send("go")
        val reply = awaitEnd(sid)
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals(listOf("browse", "click", "read"), reply.calls().map { it.name })
        assertEquals(reply.results().joinToString { it.content }, List(3) { ToolCallState.DONE }, reply.calls().map { it.state })
        // Results are wrapped as untrusted page content (B-S5), in the reply and in what the model got.
        reply.results().forEach { assertTrue(it.content, it.content.startsWith("<untrusted_page_content>")) }
        assertTrue(reply.results()[0].content, "link \"Next page\" [ref=" in reply.results()[0].content)
        // An action's result says what changed and lists the new page (tools-redesign 4).
        assertTrue(reply.results()[1].content, "→ new page." in reply.results()[1].content)
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
            Scenario.toolCall("click", """{"ref":"#plain"}"""), // harmless button: no approval
            Scenario.toolCall("type", """{"ref":"#q","text":"hello"}"""), // http page: approval every time
            Scenario.toolCall("click", """{"ref":"#send"}"""), // POST submit: denied
            Scenario.toolCall("click", """{"ref":"#del"}"""), // risky keyword: allowed
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
        assertEquals("artifact-approval-preview.jpg", del.request.screenshotId)
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
        assertTrue(reply.results()[2].content, "Not submitted; the form's submit button is button \"Send\" [ref=" in reply.results()[2].content)
        assertTrue(reply.results()[3].content, ToolOutcome.DENIED_TEXT in reply.results()[3].content)
        assertTrue(reply.results()[4].content, "button \"gone\"" in reply.results()[4].content)
        assertTrue(reply.results()[5].content, "hello" in reply.results()[5].content)
        assertTrue("POST must not reach the server", pageHits.none { it == "/submit" })
    }

    @Test
    fun typeInASearchBoxPressesEnter() {
        llm.enqueue(
            Scenario.toolCall("browse", """{"url":"${url("/search")}"}"""),
            Scenario.toolCall("type", """{"ref":"#q","text":"lamps"}"""),
            Scenario.text("done"),
        )
        val sid = send("search")
        val p = awaitPause(sid) as Pause.Approval // http page
        engine.resolve(sid, Resolution.ApprovalAnswer(p.id, allow = true))
        val reply = awaitEnd(sid)
        val typed = reply.results()[1].content
        assertTrue(typed, "Typed \"lamps\" into" in typed && "and pressed Enter → new page." in typed)
        assertTrue(typed, "Page /results body text" in typed)
        assertTrue(pageHits.toString(), pageHits.any { it == "/results" })
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
        assertTrue(back, back.startsWith("<untrusted_page_content>\nThe user gave the tab back. They may or may not have finished"))
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
        assertTrue(reply.results().single().content, "Unknown tool: browse. No tools can be called now." == reply.results().single().content)
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
            Scenario.toolCall("type", """{"ref":"#pw","text":"a"}"""),
            Scenario.toolCall("click", """{"ref":"#f"}"""), // opaque frame: approval (denied)
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
            assertTrue(runCatching { browser.openUserTab("about:blank") }.exceptionOrNull() is PageError)
            assertTrue(runCatching { browser.openAiTab("s1", "about:blank", hidden = true) }.exceptionOrNull() is PageError)
        }
        assertEquals(BrowserEngine.MAX_TABS, browser.visibleTabs.value.size)
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

    private companion object {
        const val COOKIE = "SECRETCOOKIE123"
        val PAGES = mapOf(
            "/start" to """<!doctype html><title>Start</title><a id="go" href="/next">Next page</a>
                <p id="c"></p><script>document.getElementById('c').textContent = 'cookie: ' + document.cookie</script>""",
            "/next" to "<!doctype html><title>Next</title><p>Page /next body text</p>",
            "/alert" to """<!doctype html><title>Before</title><script>alert('hi'); confirm('ok?'); document.title = 'After'</script>""",
            "/login" to """<!doctype html><title>Login</title><input id="user"><input id="pw" type="password">
                <iframe id="f" src="data:text/html,<button>Pay</button>" style="width:300px;height:80px"></iframe>""",
            "/search" to """<!doctype html><title>Search</title><form action="/results"><input id="q" type="search" name="q"></form>""",
            "/form" to """<!doctype html><title>Form</title>
                <button id="plain" onclick="this.textContent='pressed'">Show more</button>
                <form method="post" action="/submit"><input id="q" name="msg"><button id="send" type="submit">Send</button></form>
                <button id="del" onclick="this.textContent='gone'">Delete account</button>""",
        )
    }
}
