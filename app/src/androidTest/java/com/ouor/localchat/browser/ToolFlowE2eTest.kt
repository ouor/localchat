package com.ouor.localchat.browser

import androidx.datastore.core.DataStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ouor.localchat.MainActivity
import com.ouor.localchat.chat.AttachmentPayload
import com.ouor.localchat.chat.AttachmentPort
import com.ouor.localchat.chat.CatalogToolPort
import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.chat.ChatStore
import com.ouor.localchat.chat.ClientLlmPort
import com.ouor.localchat.chat.Compactor
import com.ouor.localchat.chat.EngineBrowserPort
import com.ouor.localchat.chat.LinkPrefetcher
import com.ouor.localchat.chat.ModelPort
import com.ouor.localchat.chat.ResolvedModel
import com.ouor.localchat.chat.TitleGenerator
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.chat.run.RunRegistry
import com.ouor.localchat.chat.run.RunState
import com.ouor.localchat.chat.run.StartupRecovery
import com.ouor.localchat.chat.run.TurnRunner
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.MessageTree
import com.ouor.localchat.core.Selection
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.ReasoningSpec
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.core.model.Summary
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private lateinit var pages: MockWebServer
    private val pageHits: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var llm: FakeOpenAiServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var browser: BrowserEngine
    private lateinit var scope: CoroutineScope
    private lateinit var store: MemoryChatStore
    private lateinit var attachments: MemoryAttachments
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
        store = MemoryChatStore()
        attachments = MemoryAttachments()
        val client = ClientLlmPort(ChatCompletionsClient(OkHttpClient(), AppJson))
        val models = object : ModelPort {
            override suspend fun resolve(ref: ModelRef) = ResolvedModel(ref, LlmTarget(llm.baseUrl, null, 0, allowHttp = true), caps)
            override suspend fun markUsed(ref: ModelRef) = Unit
        }
        val tools = CatalogToolPort()
        val port = EngineBrowserPort(browser)
        val settings = SettingsStore(MemoryDataStore(AppSettings()))
        val compactor = Compactor(store, client, Clock.System)
        val runner = TurnRunner(
            store, client, models, tools, attachments, port, { browser }, settings, compactor,
            TitleGenerator(store, client), LinkPrefetcher(tools, port, attachments), Clock.System, scope,
        )
        val recovery = StartupRecovery(store, Clock.System)
        runBlocking { recovery.run() }
        engine = ChatEngine(store, RunRegistry(scope), runner, recovery, models, tools, compactor, settings, Clock.System)
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
        assertEquals(Control.USER, browser.tabs.value.single { it.id == h.tabId }.control)
        engine.resolve(sid, Resolution.HandoffReturned)
        val reply = awaitEnd(sid)
        assertEquals(Control.AI, browser.tabs.value.single { it.id == h.tabId }.control)
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

private class MemoryDataStore<T>(initial: T) : DataStore<T> {
    val state = MutableStateFlow(initial)
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(state.value).also { state.value = it }
}

/** Same rules as chat's JVM FakeChatStore (test source set is not visible here). */
private class MemoryChatStore : ChatStore {
    val sessions = Collections.synchronizedMap(LinkedHashMap<String, Session>())
    val messages: MutableMap<String, MessageNode> = Collections.synchronizedMap(LinkedHashMap())
    private val summaryList = mutableListOf<Summary>()

    override suspend fun session(id: String) = sessions[id]
    override suspend fun nodes(sessionId: String) = synchronized(messages) { messages.values.filter { it.sessionId == sessionId } }
    override suspend fun node(id: String) = messages[id]
    override suspend fun summaries(sessionId: String) = summaryList.filter { it.sessionId == sessionId }
    override suspend fun createSession(session: Session) { sessions[session.id] = session }

    override suspend fun insertNodes(sessionId: String, nodes: List<MessageNode>, selection: Selection) {
        nodes.forEach { messages[it.id] = it }
        val s = sessions.getValue(sessionId)
        val (root, updated) = MessageTree.apply(s.rootSelectedId, nodes(sessionId), selection)
        updated.forEach { messages[it.id] = it }
        sessions[sessionId] = s.copy(rootSelectedId = root)
    }

    override suspend fun saveNode(node: MessageNode) {
        messages[node.id] = node.copy(selectedChildId = messages[node.id]?.selectedChildId ?: node.selectedChildId)
    }

    override suspend fun updateSession(id: String, transform: (Session) -> Session) {
        sessions[id]?.let { sessions[id] = transform(it) }
    }

    override suspend fun addSummary(summary: Summary) { summaryList += summary }

    override suspend fun unfinishedAssistants() = synchronized(messages) {
        messages.values.filter {
            val b = it.body
            b is MessageBody.Assistant && (b.status == ReplyStatus.PENDING || b.status == ReplyStatus.STREAMING)
        }
    }

    override suspend fun deleteDraft(key: String) = Unit
    override suspend fun collectAttachmentGarbage(cutoff: Long) = Unit
}

private class MemoryAttachments : AttachmentPort {
    val saved: MutableMap<String, AttachmentPayload> = Collections.synchronizedMap(LinkedHashMap())
    override suspend fun payload(id: String): AttachmentPayload? = saved[id]
    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = "artifact-$name"
    override suspend fun saveText(name: String, text: String): String {
        val id = "doc${saved.size + 1}"
        saved[id] = AttachmentPayload(
            Attachment(id, name, "text/plain", text.length.toLong(), AttachmentKind.DOCUMENT, PrepState.Ready, true, createdAt = 0),
            text = text,
        )
        return id
    }
}
