package com.ouor.localchat.browser

import androidx.test.core.app.ActivityScenario
import com.ouor.localchat.MainActivity
import com.ouor.localchat.chat.CatalogToolPort
import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.chat.ClientLlmPort
import com.ouor.localchat.chat.EngineBrowserPort
import com.ouor.localchat.chat.FakeAttachments
import com.ouor.localchat.chat.FakeChatStore
import com.ouor.localchat.chat.MemoryDataStore
import com.ouor.localchat.chat.ModelPort
import com.ouor.localchat.chat.ResolvedModel
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.chat.run.RunState
import com.ouor.localchat.chat.wireEngine
import com.ouor.localchat.core.AppJson
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
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.testing.FakeOpenAiServer
import com.ouor.localchat.testing.ScreenOnRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.util.Collections

/**
 * Whole tool flow on a device: FakeOpenAiServer scripts the model's tool calls, the real ChatEngine/TurnRunner/
 * ToolExecutor run them against the real BrowserEngine attached to MainActivity, on pages from a local MockWebServer
 * (plain http, so `type` always needs approval).
 */
abstract class ToolFlowBase {

    @get:Rule val screen = ScreenOnRule()

    protected lateinit var pages: MockWebServer
    protected val pageHits: MutableList<String> = Collections.synchronizedList(mutableListOf())
    protected lateinit var llm: FakeOpenAiServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    protected lateinit var browser: BrowserEngine
    private lateinit var scope: CoroutineScope
    protected lateinit var store: FakeChatStore
    protected lateinit var attachments: FakeAttachments
    protected lateinit var engine: ChatEngine
    protected var caps = caps(tools = true)

    /** The response for [request] at [path]; null serves a default page that says "Page <path> body text". */
    protected abstract fun respond(path: String, request: RecordedRequest): MockResponse?

    @Before
    fun setUp() {
        pages = MockWebServer()
        pages.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                pageHits += path
                return respond(path, request) ?: html("<!doctype html><title>$path</title><p>Page $path body text</p>")
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
        val settings = SettingsStore(MemoryDataStore(AppSettings()))
        val wired = wireEngine(scope, store, client, models, CatalogToolPort(), attachments, EngineBrowserPort(browser), settings, Clock.System) { browser }
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

    protected fun html(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

    protected fun url(path: String) = pages.url(path).toString()

    protected fun send(text: String, browserOn: Boolean = true, searchOn: Boolean = false) = runBlocking {
        engine.send(null, Draft(Draft.NEW_CHAT, text, emptyList(), 0), GenOptions(search = searchOn, browser = browserOn), MODEL)
    }

    /** The next message in chat [sid]. */
    protected fun sendTo(sid: String, text: String) = runBlocking {
        engine.send(sid, Draft(sid, text, emptyList(), 0), GenOptions(browser = true), MODEL)
    }

    /** A new chat's first reply is followed by a title request; waits for it so it does not take a scripted scenario. */
    protected fun awaitTitleRequest() {
        repeat(30) {
            val r = runCatching { llm.takeRequest(1) }.getOrNull() ?: return@repeat
            if ("Write the title in" in r.body?.utf8().orEmpty()) return
        }
        error("no title request")
    }

    protected fun awaitRun(sid: String, what: String, timeoutMs: Long = 30_000, predicate: (RunState?) -> Boolean): RunState? = runBlocking {
        try {
            withTimeout(timeoutMs) { engine.runs.first { predicate(it[sid]) }[sid] }
        } catch (e: Exception) {
            val last = store.messages.values.lastOrNull { it.sessionId == sid && it.body is MessageBody.Assistant }?.body
            throw AssertionError("timed out waiting for $what; phase=${engine.runs.value[sid]?.phase}; reply=$last", e)
        }
    }

    private var lastPause: Pause? = null

    /** Next pause after the one answered last (resolve is async: the old pause can still be showing). */
    protected fun awaitPause(sid: String): Pause =
        (awaitRun(sid, "pause") { (it?.phase as? RunPhase.Paused)?.pause.let { p -> p != null && p != lastPause } }!!.phase as RunPhase.Paused)
            .pause.also { lastPause = it }

    protected fun awaitEnd(sid: String, timeoutMs: Long = 30_000): MessageBody.Assistant {
        awaitRun(sid, "end of run", timeoutMs) { it == null }
        return store.messages.values.last { it.sessionId == sid && it.body is MessageBody.Assistant }.body as MessageBody.Assistant
    }

    protected fun awaitControl(tabId: Int, control: Control) = runBlocking {
        withTimeout(5_000) { browser.tabs.first { l -> l.any { it.id == tabId && it.control == control } } }
    }

    protected fun MessageBody.Assistant.calls() = blocks.filterIsInstance<ToolCallBlock>()
    protected fun MessageBody.Assistant.results() = blocks.filterIsInstance<ToolResultBlock>()

    /** Chat request bodies sent to the fake model so far. */
    protected fun modelRequests(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val r = runCatching { llm.takeRequest(1) }.getOrNull() ?: break
            if (r.url.encodedPath.endsWith("/chat/completions")) out += r.body?.utf8().orEmpty()
        }
        return out.filter { "\"stream\":true" in it }
    }

    protected companion object {
        val MODEL = ModelRef("ep", "fake-model", "Fake")

        fun caps(tools: Boolean) = ModelCapabilities(
            toolCalling = Sourced(tools, CapSource.DEFAULT),
            imageInput = Sourced(false, CapSource.DEFAULT),
            reasoning = Sourced(ReasoningSpec(emptyList(), null, ReasoningWire.DEEPSEEK_THINKING), CapSource.DEFAULT),
            contextWindow = Sourced(64_000, CapSource.DEFAULT),
            maxOutputTokens = Sourced(null, CapSource.DEFAULT),
        )
    }
}
