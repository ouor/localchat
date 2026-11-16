package com.ouor.localchat.chat

import androidx.datastore.core.DataStore
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Control
import com.ouor.localchat.chat.run.RunRegistry
import com.ouor.localchat.chat.run.StartupRecovery
import com.ouor.localchat.chat.run.TurnRunner
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.MessageTree
import com.ouor.localchat.core.Selection
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.ReasoningSpec
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.core.model.Summary
import com.ouor.localchat.llm.LlmEvent
import com.ouor.localchat.llm.LlmResult
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import javax.inject.Provider

// In-memory stand-ins for other tracks (architecture 12: FakeLlm, fake tools).

class MemoryDataStore<T>(initial: T) : DataStore<T> {
    val state = MutableStateFlow(initial)
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(state.value).also { state.value = it }
}

class FakeChatStore : ChatStore {
    // Synchronized: the device e2e runs the engine on Dispatchers.Default.
    val sessions: MutableMap<String, Session> = Collections.synchronizedMap(LinkedHashMap())
    val messages: MutableMap<String, MessageNode> = Collections.synchronizedMap(LinkedHashMap())
    val summaryList: MutableList<Summary> = Collections.synchronizedList(mutableListOf())
    val saves: MutableList<MessageNode> = Collections.synchronizedList(mutableListOf())
    val deletedDrafts = mutableListOf<String>()
    var gcCutoff: Long? = null

    override suspend fun session(id: String) = sessions[id]
    override suspend fun nodes(sessionId: String) = synchronized(messages) { messages.values.filter { it.sessionId == sessionId } }
    override suspend fun node(id: String) = messages[id]
    override suspend fun summaries(sessionId: String) = synchronized(summaryList) { summaryList.filter { it.sessionId == sessionId } }
    override suspend fun createSession(session: Session) { sessions[session.id] = session }

    override suspend fun insertNodes(sessionId: String, nodes: List<MessageNode>, selection: Selection) {
        nodes.forEach { messages[it.id] = it }
        val s = sessions.getValue(sessionId)
        val (root, updated) = MessageTree.apply(s.rootSelectedId, nodes(sessionId), selection)
        updated.forEach { messages[it.id] = it }
        sessions[sessionId] = s.copy(rootSelectedId = root)
    }

    /** Makes saveNode throw for matching nodes (a broken row, a full disk). */
    var failSave: (MessageNode) -> Boolean = { false }

    override suspend fun saveNode(node: MessageNode) {
        if (failSave(node)) throw IllegalStateException("disk full")
        // Same rule as SessionRepository: the stored selection pointer wins.
        messages[node.id] = node.copy(selectedChildId = messages[node.id]?.selectedChildId ?: node.selectedChildId)
        saves += node
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

    override suspend fun deleteDraft(key: String) { deletedDrafts += key }
    override suspend fun collectAttachmentGarbage(cutoff: Long) { gcCutoff = cutoff }

    fun reply(id: String) = messages.getValue(id).body as MessageBody.Assistant
}

/** Each stream call takes the next script; requests are recorded. */
class FakeLlm : LlmPort {
    val scripts = ArrayDeque<suspend kotlinx.coroutines.flow.FlowCollector<LlmEvent>.() -> Unit>()
    val results = ArrayDeque<LlmResult>()
    val requests = mutableListOf<ChatRequest>()
    val completeRequests = mutableListOf<ChatRequest>()

    fun events(vararg events: LlmEvent) = scripts.addLast { events.forEach { emit(it) } }

    fun text(text: String) = events(LlmEvent.TextDelta(text), LlmEvent.Finished("stop", null))

    override fun stream(target: LlmTarget, request: ChatRequest): Flow<LlmEvent> {
        requests += request
        val script = scripts.removeFirst()
        return flow { script() }
    }

    override suspend fun complete(target: LlmTarget, request: ChatRequest): LlmResult {
        completeRequests += request
        return results.removeFirstOrNull() ?: LlmResult.Ok("Title", "stop", null)
    }
}

val TEST_MODEL = ModelRef("ep", "deepseek-chat", "DeepSeek")

fun caps(window: Int? = 64_000, tools: Boolean = true) = ModelCapabilities(
    toolCalling = Sourced(tools, CapSource.DEFAULT),
    imageInput = Sourced(false, CapSource.DEFAULT),
    reasoning = Sourced(ReasoningSpec(emptyList(), null, ReasoningWire.DEEPSEEK_THINKING), CapSource.DEFAULT),
    contextWindow = Sourced(window, CapSource.DEFAULT),
    maxOutputTokens = Sourced(null, CapSource.DEFAULT),
)

class FakeModelPort(var caps: ModelCapabilities = caps()) : ModelPort {
    val used = mutableListOf<ModelRef>()
    /** Virtual delay in resolve, to let two sends interleave. */
    var resolveDelayMs = 0L
    /** resolve returns null from this call on (model deleted mid-flight, P-08). */
    var goneAfter = Int.MAX_VALUE
    private var resolves = 0
    override suspend fun resolve(ref: ModelRef): ResolvedModel? {
        if (resolveDelayMs > 0) kotlinx.coroutines.delay(resolveDelayMs)
        if (++resolves > goneAfter) return null
        return ResolvedModel(ref, LlmTarget("http://fake/v1", null, 0, true), caps)
    }
    override suspend fun markUsed(ref: ModelRef) { used += ref }
}

/** Same switch rule as ToolExposure (groups, tool calling, vision), over the given tools instead of the catalog. */
class FakeToolPort(val tools: List<Tool>) : ToolPort {
    override fun exposed(caps: ModelCapabilities, search: Boolean, browser: Boolean): List<Tool> {
        if (!caps.toolCalling.value) return emptyList()
        val on = buildSet { if (search) add(ToolGroup.SEARCH); if (browser) add(ToolGroup.BROWSER) }
        return tools.filter { t -> t.groups.any(on::contains) && (!t.requiresVision || caps.imageInput.value) }
    }
    override fun byName(name: String) = tools.firstOrNull { it.name == name }
}

class FakeBrowserPort : BrowserPort {
    val controls = MutableStateFlow<Map<Int, Control>>(emptyMap())
    val setCalls = mutableListOf<Pair<Int, Control>>()
    val aiTabCalls = mutableListOf<Int?>()
    override suspend fun aiTabId(sessionId: String, tabIdArg: Int?): Int = (tabIdArg ?: 1).also { aiTabCalls += tabIdArg }
    override fun tab(tabId: Int): BrowserTab = throw IllegalStateException("no WebView on the JVM (tab $tabId)")
    /** B-09 ② marks set by acquireAiTab: tab id to tool name. */
    val activity = mutableListOf<Pair<Int, String>>()
    /** Same contract as BrowserEngine.acquireAiTab: wait while USER, then check again. */
    override suspend fun acquireAiTab(sessionId: String, tabIdArg: Int?, toolName: String, onUserControl: suspend (Int) -> Unit, onResumed: suspend () -> Unit): Int {
        while (true) {
            val id = aiTabId(sessionId, tabIdArg)
            if (controls.value[id] != Control.USER) return id.also { activity += it to toolName }
            onUserControl(id)
            try {
                controls.first { it[id] != Control.USER }
            } finally {
                onResumed()
            }
        }
    }
    /** Stands in for CookieMasker: the tab's cookie value is SECRETCOOKIE. */
    val cookies = HashMap<String, String>()
    override fun maskCookies(text: String, url: String) = text.replace("SECRETCOOKIE", "***").let { t -> cookies[url]?.let { t.replace(it, "***") } ?: t }
    val cleared = mutableListOf<String>()
    val closedAiTabsOf = mutableListOf<String?>()
    override fun closeAiTabs(sessionId: String?) { closedAiTabsOf += sessionId }
    override fun clearAiActivity(sessionId: String) { cleared += sessionId }
    val opened = mutableListOf<String>()
    val closed = mutableListOf<Int>()
    var openError: Exception? = null
    override suspend fun openAiTab(sessionId: String, url: String): Int {
        openError?.let { throw it }
        opened += url; return 40 + opened.size
    }
    override suspend fun close(tabId: Int) { closed += tabId }
    override fun isSensitive(url: String) = "bank" in url
    val urls = HashMap<Int, String>()
    override fun tabUrl(tabId: Int): String? = urls[tabId] ?: "https://tab$tabId.test/"
    override fun setControl(tabId: Int, control: Control) {
        setCalls += tabId to control
        controls.value = controls.value + (tabId to control)
    }
}

/** A tool whose behaviour is a lambda. */
class FakeTool(
    override val name: String,
    override val approval: ApprovalMode = ApprovalMode.NONE,
    override val groups: Set<ToolGroup> = setOf(ToolGroup.SEARCH, ToolGroup.BROWSER),
    val body: suspend (JsonObject, ToolContext) -> ToolOutcome = { args, _ -> ToolOutcome("ok ${args["q"]?.jsonPrimitive?.content}") },
) : Tool {
    var calls = 0
    override val description = "fake $name"
    override val parameters = JsonObject(emptyMap())
    override val labelRes = 0
    override fun argSummary(args: JsonObject) = ""
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        calls++
        return body(args, ctx)
    }
}

class TickClock : Clock {
    var t = 1_000_000L
    override fun now() = t++
}

/** Full engine over fakes, run in [scope] (the test scope). */
class Wired(val engine: ChatEngine, val registry: RunRegistry, val recovery: StartupRecovery)

/**
 * The di/ graph by hand, in one place (AR F-09): Harness, the real-API test and the device e2e all call
 * this, so a constructor change breaks one file.
 */
fun wireEngine(
    scope: CoroutineScope,
    store: ChatStore,
    llm: LlmPort,
    models: ModelPort,
    tools: ToolPort,
    attachments: AttachmentPort,
    browser: BrowserPort,
    settings: SettingsStore,
    clock: Clock,
    browserEngine: Provider<BrowserEngine> = Provider { throw AssertionError("browser used") },
): Wired {
    val registry = RunRegistry(scope)
    val compactor = Compactor(store, llm, attachments, clock)
    val runner = TurnRunner(
        store, llm, models, tools, attachments, browser, browserEngine, settings, compactor,
        TitleGenerator(store, llm, settings), LinkPrefetcher(tools, browser, attachments), clock, scope,
    )
    val recovery = StartupRecovery(store, clock)
    return Wired(ChatEngine(store, registry, runner, recovery, models, tools, compactor, settings, clock), registry, recovery)
}

class Harness(
    scope: CoroutineScope,
    tools: List<Tool> = emptyList(),
    maxSteps: Int = 25,
    val models: FakeModelPort = FakeModelPort(),
    recovered: Boolean = true,
) {
    val store = FakeChatStore()
    val llm = FakeLlm()
    val browser = FakeBrowserPort()
    val clock = TickClock()
    val settings = SettingsStore(MemoryDataStore(AppSettings(maxSteps = maxSteps)))
    val attachments = FakeAttachments()
    private val tools = FakeToolPort(tools)
    private val wired = wireEngine(scope, store, llm, models, this.tools, attachments, browser, settings, clock)
    val registry = wired.registry
    val recovery = wired.recovery
    val engine = wired.engine

    init {
        if (recovered) scope.launch { recovery.run() }
    }
}

class FakeAttachments : AttachmentPort {
    val saved: MutableMap<String, AttachmentPayload> = Collections.synchronizedMap(LinkedHashMap())
    override suspend fun payload(id: String): AttachmentPayload? = saved[id]
    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = "artifact-$name"
    override suspend fun saveText(name: String, text: String): String {
        val id = "doc${saved.size + 1}"
        saved[id] = AttachmentPayload(
            com.ouor.localchat.core.model.Attachment(id, name, "text/plain", text.length.toLong(),
                com.ouor.localchat.core.model.AttachmentKind.DOCUMENT, com.ouor.localchat.core.model.PrepState.Ready, true, createdAt = 0),
            text = text,
        )
        return id
    }
}
