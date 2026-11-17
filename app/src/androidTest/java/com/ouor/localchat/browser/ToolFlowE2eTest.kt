package com.ouor.localchat.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ouor.localchat.MainActivity
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.chat.CatalogToolPort
import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.chat.ClientLlmPort
import com.ouor.localchat.chat.EngineBrowserPort
import com.ouor.localchat.chat.FakeAttachments
import com.ouor.localchat.chat.FakeChatStore
import com.ouor.localchat.chat.MemoryDataStore
import com.ouor.localchat.chat.wireEngine
import com.ouor.localchat.chat.ModelPort
import com.ouor.localchat.chat.ResolvedModel
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.chat.run.RunState
import com.ouor.localchat.chat.run.TurnRunner
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.ReasoningSpec
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.testing.FakeOpenAiServer
import com.ouor.localchat.testing.Scenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Whole tool flow on a device (B's checklist): FakeOpenAiServer scripts the model's tool calls, the real
 * ChatEngine/TurnRunner/ToolExecutor run them through ToolContext against the real BrowserEngine attached
 * to MainActivity, on pages from a local MockWebServer (plain http, so `type` always needs approval).
 */
@RunWith(AndroidJUnit4::class)
class ToolFlowE2eTest {

    @get:Rule val screen = ScreenOnRule()

    private lateinit var pages: MockWebServer
    private val pageHits: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var llm: FakeOpenAiServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var browser: BrowserEngine
    private lateinit var scope: CoroutineScope
    private lateinit var store: FakeChatStore
    private lateinit var attachments: FakeAttachments
    private lateinit var engine: ChatEngine
    private var caps = caps(tools = true)

    @Before
    fun setUp() {
        pages = MockWebServer()
        pages.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                pageHits += path
                val b = MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8")
                if (path == "/start") b.addHeader("Set-Cookie", "sid=$COOKIE; Path=/")
                return b.body(PAGES[path] ?: "<!doctype html><title>$path</title><p>Page $path body text</p>").build()
            }
        }
        pages.start()
        llm = FakeOpenAiServer()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { browser = it.browserEngine }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = FakeChatStore()
        attachments = FakeAttachments()
        val client = ClientLlmPort(ChatCompletionsClient(OkHttpClient(), AppJson))
        val models = object : ModelPort {
            override suspend fun resolve(ref: ModelRef) = ResolvedModel(ref, LlmTarget(llm.baseUrl, null, 0, allowHttp = true), caps)
            override suspend fun markUsed(ref: ModelRef) = Unit
        }
        val tools = CatalogToolPort()
        val port = EngineBrowserPort(browser)
        val settings = SettingsStore(MemoryDataStore(AppSettings()))
        val wired = wireEngine(scope, store, client, models, tools, attachments, port, settings, Clock.System) { browser }
        runBlocking { wired.recovery.run() }
        engine = wired.engine
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking(Dispatchers.Main) { browser.tabs.value.forEach { browser.close(it.id) } }
        scenario.close()
        llm.close()
        pages.close()
    }

    private fun url(path: String) = pages.url(path).toString()

    private fun send(text: String, browserOn: Boolean = true) = runBlocking {
        engine.send(null, Draft(Draft.NEW_CHAT, text, emptyList(), 0), GenOptions(browser = browserOn), MODEL)
    }

    private fun awaitRun(sid: String, what: String, predicate: (RunState?) -> Boolean): RunState? = runBlocking {
        try {
            withTimeout(30_000) { engine.runs.first { predicate(it[sid]) }[sid] }
        } catch (e: Exception) {
            val last = store.messages.values.lastOrNull { it.sessionId == sid && it.body is MessageBody.Assistant }?.body
            throw AssertionError("timed out waiting for $what; phase=${engine.runs.value[sid]?.phase}; reply=$last", e)
        }
    }

    private var lastPause: Pause? = null

    /** Next pause after the one answered last (resolve is async: the old pause can still be showing). */
    private fun awaitPause(sid: String): Pause =
        (awaitRun(sid, "pause") { (it?.phase as? RunPhase.Paused)?.pause.let { p -> p != null && p != lastPause } }!!.phase as RunPhase.Paused)
            .pause.also { lastPause = it }

    private fun awaitEnd(sid: String): MessageBody.Assistant {
        awaitRun(sid, "end of run") { it == null }
        return store.messages.values.last { it.sessionId == sid && it.body is MessageBody.Assistant }.body as MessageBody.Assistant
    }

    private fun awaitControl(tabId: Int, control: Control) = runBlocking {
        withTimeout(5_000) { browser.tabs.first { l -> l.any { it.id == tabId && it.control == control } } }
    }

    private fun MessageBody.Assistant.calls() = blocks.filterIsInstance<ToolCallBlock>()
    private fun MessageBody.Assistant.results() = blocks.filterIsInstance<ToolResultBlock>()

    @Test
    fun openSnapshotClickReadMultiStep() {
        llm.enqueue(
            Scenario.toolCall("open_tab", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("snapshot", "{}"),
            Scenario.toolCall("click", """{"target":"#go"}"""),
            Scenario.toolCall("page_text", "{}"),
            Scenario.text("done"),
        )
        val sid = send("go")
        val reply = awaitEnd(sid)
        assertEquals(ReplyStatus.DONE, reply.status)
        assertEquals(listOf("open_tab", "snapshot", "click", "page_text"), reply.calls().map { it.name })
        assertEquals(reply.results().joinToString { it.content }, List(4) { ToolCallState.DONE }, reply.calls().map { it.state })
        // Results are wrapped as untrusted page content (B-S5), in the reply and in what the model got.
        reply.results().forEach { assertTrue(it.content, it.content.startsWith("<untrusted_page_content>")) }
        assertTrue(reply.results()[1].content, "Next page" in reply.results()[1].content)
        assertTrue(reply.results()[3].content, "Page /next body text" in reply.results()[3].content)
        val tab = browser.tabs.value.single()
        assertTrue(tab.url, tab.url.endsWith("/next"))
        assertEquals(Owner.Ai(sid), tab.owner)
        val sent = modelRequests()
        // sent[5], if any, is the title request.
        assertTrue(sent.size >= 5)
        assertTrue(sent[4], sent[4].contains("untrusted_page_content"))
    }

    @Test
    fun cookieValuesAreMaskedInResults() {
        llm.enqueue(
            Scenario.toolCall("open_tab", """{"url":"${url("/start")}"}"""),
            Scenario.toolCall("execute", """{"code":"document.cookie"}"""),
            Scenario.toolCall("page_text", "{}"),
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
            Scenario.toolCall("open_tab", """{"url":"${url("/form")}"}"""),
            Scenario.toolCall("click", """{"target":"#plain"}"""), // harmless button: no approval
            Scenario.toolCall("type", """{"target":"#q","text":"hello"}"""), // http page: approval every time
            Scenario.toolCall("click", """{"target":"#send"}"""), // POST submit: denied
            Scenario.toolCall("click", """{"target":"#del"}"""), // risky keyword: allowed
            Scenario.toolCall("execute", """{"code":"document.getElementById('q').value"}"""), // always
            Scenario.text("done"),
        )
        val sid = send("form")
        val type = awaitPause(sid) as Pause.Approval
        assertEquals("type", type.request.toolName)
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
        assertTrue(reply.results()[3].content, SystemPrompt.DENIED in reply.results()[3].content)
        assertTrue(reply.results()[5].content, "hello" in reply.results()[5].content)
        assertTrue("POST must not reach the server", pageHits.none { it == "/submit" })
    }

    @Test
    fun handoffRoundTrip() {
        llm.enqueue(
            Scenario.toolCall("open_tab", """{"url":"${url("/start")}"}"""),
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
        assertEquals(SystemPrompt.HANDOFF_DONE, reply.results()[1].content)
        assertEquals(ReplyStatus.DONE, reply.status)
    }

    @Test
    fun waitsWhileUserControlsTheTab() {
        llm.enqueue(
            Scenario.toolCall("open_tab", """{"url":"${url("/start")}"}"""),
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
        llm.enqueue(Scenario.toolCall("open_tab", """{"url":"${url("/start")}"}"""), Scenario.text("plain"))
        val sid = send("open the start page", browserOn = false)
        val reply = awaitEnd(sid)
        assertFalse(modelRequests().first().contains("\"tools\""))
        assertTrue(reply.calls().none { it.state == ToolCallState.DONE })
        assertTrue("/start" !in pageHits)
        assertTrue(browser.tabs.value.isEmpty())
    }

    @Test
    fun regenerateWithSwitchesOffSendsNoTools() {
        llm.enqueue(Scenario.toolCall("open_tab", """{"url":"${url("/p1")}"}"""), Scenario.text("first"))
        val sid = send("open p1")
        val first = store.messages.values.last { it.sessionId == sid && it.body is MessageBody.Assistant }
        assertEquals(listOf("open_tab"), awaitEnd(sid).calls().map { it.name })
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
    fun keyboardIsRefusedInPasswordFieldAndFrames() {
        llm.enqueue(
            Scenario.toolCall("open_tab", """{"url":"${url("/login")}"}"""),
            Scenario.toolCall("click", """{"target":"#pw"}"""),
            Scenario.toolCall("keyboard", """{"key":"a"}"""),
            Scenario.toolCall("click", """{"target":"#f"}"""), // opaque frame: approval (denied)
            Scenario.text("done"),
        )
        val sid = send("login")
        val frame = awaitPause(sid) as Pause.Approval
        assertEquals("FRAME", frame.request.reason?.name)
        engine.resolve(sid, Resolution.ApprovalAnswer(frame.id, allow = false))
        val reply = awaitEnd(sid)
        assertEquals(reply.results().joinToString { it.content }, listOf(ToolCallState.DONE, ToolCallState.DONE, ToolCallState.FAILED, ToolCallState.DENIED), reply.calls().map { it.state })
        assertTrue(reply.results()[2].content, "Refused" in reply.results()[2].content)
    }

    @Test
    fun enterOnFocusedRiskyButtonAsks() {
        llm.enqueue(
            Scenario.toolCall("open_tab", """{"url":"${url("/danger")}"}"""),
            Scenario.toolCall("keyboard", """{"key":"Enter"}"""),
            Scenario.toolCall("keyboard", """{"key":"Space"}"""),
            Scenario.text("done"),
        )
        val sid = send("danger")
        repeat(2) {
            val p = awaitPause(sid) as Pause.Approval
            assertEquals("keyboard", p.request.toolName)
            assertEquals("RISKY_KEYWORD", p.request.reason?.name)
            engine.resolve(sid, Resolution.ApprovalAnswer(p.id, allow = false))
        }
        val reply = awaitEnd(sid)
        assertEquals(listOf(ToolCallState.DONE, ToolCallState.DENIED, ToolCallState.DENIED), reply.calls().map { it.state })
    }

    @Test
    fun getTabsShowsOnlyTheDomainOfUserTabs() {
        runBlocking(Dispatchers.Main) { browser.openUserTab(url("/private-wiki?token=abc123")) }
        llm.enqueue(Scenario.toolCall("get_tabs", "{}"), Scenario.text("done"))
        val reply = awaitEnd(send("tabs"))
        val out = reply.results().single().content
        assertTrue(out, "(user's tab: localhost)" in out)
        assertFalse(out, "token" in out || "private-wiki" in out)
    }

    @Test
    fun acquireAiTabMarksActivityAndWaitsForControl() = runBlocking<Unit> {
        val tab = browser.acquireAiTab("s1", null, "snapshot")
        assertEquals("snapshot", browser.aiActivity.value[tab.id])
        browser.setControl(tab.id, Control.USER) // from a worker thread: posted to main (F-10)
        withTimeout(5_000) { browser.tabs.first { l -> l.any { it.id == tab.id && it.control == Control.USER } } }
        val events = Collections.synchronizedList(mutableListOf<String>())
        val waiting = async(Dispatchers.Default) {
            browser.acquireAiTab("s1", tab.id, "click", onUserControl = { events += "user $it" }, onResumed = { events += "resumed" })
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
    fun prefetchTabStaysHiddenAndNeverBecomesTheDefaultTab() = runBlocking<Unit> {
        // FB3 eng M2, arch M1.
        val default = browser.acquireAiTab("s1", null, "snapshot")
        val hidden = withContext(Dispatchers.Main) { browser.openAiTab("s1", url("/p1"), hidden = true) }
        browser.acquireAiTab("s1", hidden, "page_text")
        assertEquals(default.id, withContext(Dispatchers.Main) { browser.currentAiTab("s1") })
        assertEquals(listOf(default.id), browser.visibleTabs.value.map { it.id })
        assertEquals(listOf(default.id), browser.sessionTabs("s1").first().map { it.id })
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
        val a = browser.acquireAiTab("chatA", null, "snapshot")
        val b = browser.acquireAiTab("chatB", null, "click")
        assertEquals(listOf(a.id), browser.sessionTabs("chatA").first().map { it.id })
        assertEquals(mapOf(b.id to "click"), browser.sessionActivity("chatB").first())
        browser.clearAiActivity("chatB")
        withTimeout(5_000) { browser.sessionActivity("chatB").first { it.isEmpty() } }
        assertEquals(mapOf(a.id to "snapshot"), browser.sessionActivity("chatA").first()) // other chat untouched
        browser.closeSessionTabs("chatA") // from a worker thread
        withTimeout(5_000) { browser.sessionTabs("chatA").first { it.isEmpty() } }
        assertEquals(listOf(b.id), browser.tabs.value.map { it.id })
        assertTrue(a.id !in browser.aiActivity.value)
    }

    /** Chat request bodies sent to the fake model so far. */
    private fun modelRequests(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val r = runCatching { llm.takeRequest(1) }.getOrNull() ?: break
            if (r.url.encodedPath.endsWith("/chat/completions")) out += r.body?.utf8().orEmpty()
        }
        return out.filter { "\"stream\":true" in it }
    }

    private companion object {
        const val COOKIE = "SECRETCOOKIE123"
        val MODEL = ModelRef("ep", "fake-model", "Fake")
        val PAGES = mapOf(
            "/start" to """<!doctype html><title>Start</title><a id="go" href="/next">Next page</a>
                <p id="c"></p><script>document.getElementById('c').textContent = 'cookie: ' + document.cookie</script>""",
            "/next" to "<!doctype html><title>Next</title><p>Page /next body text</p>",
            "/alert" to """<!doctype html><title>Before</title><script>alert('hi'); confirm('ok?'); document.title = 'After'</script>""",
            "/login" to """<!doctype html><title>Login</title><input id="user"><input id="pw" type="password">
                <iframe id="f" src="data:text/html,<button>Pay</button>" style="width:300px;height:80px"></iframe>""",
            "/danger" to """<!doctype html><title>Danger</title><button id="del" onclick="this.textContent='gone'">Delete account</button>
                <script>document.getElementById('del').focus()</script>""",
            "/form" to """<!doctype html><title>Form</title>
                <button id="plain" onclick="this.textContent='pressed'">Show more</button>
                <form method="post" action="/submit"><input id="q" name="q"><button id="send" type="submit">Send</button></form>
                <button id="del" onclick="this.textContent='gone'">Delete account</button>""",
        )

        fun caps(tools: Boolean) = ModelCapabilities(
            toolCalling = Sourced(tools, CapSource.DEFAULT),
            imageInput = Sourced(false, CapSource.DEFAULT),
            reasoning = Sourced(ReasoningSpec(emptyList(), null, ReasoningWire.DEEPSEEK_THINKING), CapSource.DEFAULT),
            contextWindow = Sourced(64_000, CapSource.DEFAULT),
            maxOutputTokens = Sourced(null, CapSource.DEFAULT),
        )
    }
}
