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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// In-memory stand-ins for other tracks (architecture 12: FakeLlm, fake tools).

class MemoryDataStore<T>(initial: T) : DataStore<T> {
    val state = MutableStateFlow(initial)
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(state.value).also { state.value = it }
}

class FakeChatStore : ChatStore {
    val sessions = LinkedHashMap<String, Session>()
    val messages = LinkedHashMap<String, MessageNode>()
    val summaryList = mutableListOf<Summary>()
    val saves = mutableListOf<MessageNode>()
    val deletedDrafts = mutableListOf<String>()
    var gcCutoff: Long? = null

    override suspend fun session(id: String) = sessions[id]
    override suspend fun nodes(sessionId: String) = messages.values.filter { it.sessionId == sessionId }
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

    override suspend fun unfinishedAssistants() = messages.values.filter {
        val b = it.body
        b is MessageBody.Assistant && (b.status == ReplyStatus.PENDING || b.status == ReplyStatus.STREAMING)
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
    override suspend fun resolve(ref: ModelRef): ResolvedModel {
        if (resolveDelayMs > 0) kotlinx.coroutines.delay(resolveDelayMs)
        return ResolvedModel(ref, LlmTarget("http://fake/v1", null, 0, true), caps)
    }
    override suspend fun markUsed(ref: ModelRef) { used += ref }
}

class FakeToolPort(val tools: List<Tool>) : ToolPort {
    override fun exposed(caps: ModelCapabilities, search: Boolean, browser: Boolean) = if (caps.toolCalling.value) tools else emptyList()
    override fun byName(name: String) = tools.firstOrNull { it.name == name }
}

class FakeBrowserPort : BrowserPort {
    val controls = MutableStateFlow<Map<Int, Control>>(emptyMap())
    val setCalls = mutableListOf<Pair<Int, Control>>()
    val aiTabCalls = mutableListOf<Int?>()
    override suspend fun aiTabId(sessionId: String, tabIdArg: Int?): Int = (tabIdArg ?: 1).also { aiTabCalls += tabIdArg }
    override fun tab(tabId: Int): BrowserTab = throw IllegalStateException("no WebView on the JVM (tab $tabId)")
    /** Stands in for CookieMasker: the tab's cookie value is SECRETCOOKIE. */
    override fun maskCookies(text: String, url: String) = text.replace("SECRETCOOKIE", "***")
    override fun clearAiActivity(sessionId: String) = Unit
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
    override fun control(tabId: Int) = controls.map { it[tabId] ?: Control.AI }
    override fun setControl(tabId: Int, control: Control) {
        setCalls += tabId to control
        controls.value = controls.value + (tabId to control)
    }
}

/** A tool whose behaviour is a lambda. */
class FakeTool(
    override val name: String,
    override val approval: ApprovalMode = ApprovalMode.NONE,
    val body: suspend (JsonObject, ToolContext) -> ToolOutcome = { args, _ -> ToolOutcome("ok ${args["q"]?.jsonPrimitive?.content}") },
) : Tool {
    var calls = 0
    override val groups = setOf(ToolGroup.LINK)
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
    val registry = RunRegistry(scope)
    private val compactor = Compactor(store, llm, clock)
    private val titles = TitleGenerator(store, llm)
    val attachments = FakeAttachments()
    private val runner = TurnRunner(
        store, llm, models, FakeToolPort(tools), attachments, browser, { throw AssertionError("browser used") },
        settings, compactor, titles, LinkPrefetcher(FakeToolPort(tools), browser, attachments), clock, scope,
    )
    val recovery = StartupRecovery(store, clock)
    val engine = ChatEngine(store, registry, runner, recovery, models, FakeToolPort(tools), compactor, settings, clock)

    init {
        if (recovered) scope.launch { recovery.run() }
    }
}

class FakeAttachments : AttachmentPort {
    val saved = LinkedHashMap<String, AttachmentPayload>()
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
