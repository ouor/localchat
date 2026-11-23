package com.ouor.localchat.chat

import com.ouor.localchat.agent.BrowserPort
import com.ouor.localchat.agent.FakeBrowserPort
import androidx.datastore.core.DataStore
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
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
import com.ouor.localchat.policy.Policies
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolFacts
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

class FakeModelPort(var caps: ModelCapabilities = caps(), private val baseUrl: String = "http://fake/v1") : ModelPort {
    val used = mutableListOf<ModelRef>()
    /** Virtual delay in resolve, to let two sends interleave. */
    var resolveDelayMs = 0L
    /** resolve returns null from this call on (model deleted mid-flight, P-08). */
    var goneAfter = Int.MAX_VALUE
    private var resolves = 0
    override suspend fun resolve(ref: ModelRef): ResolvedModel? {
        if (resolveDelayMs > 0) kotlinx.coroutines.delay(resolveDelayMs)
        if (++resolves > goneAfter) return null
        return ResolvedModel(ref, LlmTarget(baseUrl, null, 0, true), caps)
    }
    override suspend fun markUsed(ref: ModelRef) { used += ref }
}

/** Same switch rule as ToolExposure (groups, tool calling), over the given tools instead of the catalog. */
class FakeToolPort(val tools: List<Tool>) : ToolPort {
    override fun exposed(caps: ModelCapabilities, search: Boolean, browser: Boolean): List<Tool> {
        if (!caps.toolCalling.value) return emptyList()
        val on = buildSet { if (search) add(ToolGroup.SEARCH); if (browser) add(ToolGroup.BROWSER) }
        return tools.filter { t -> t.groups.any(on::contains) }
    }
    override fun byName(name: String) = tools.firstOrNull { it.name == name }
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
    policies: Policies = Policies(emptyList(), emptyList()),
): Wired {
    val registry = RunRegistry(scope)
    val compactor = Compactor(store, llm, attachments, clock)
    val runner = TurnRunner(
        store, llm, models, tools, attachments, browser, browserEngine, policies, settings, compactor,
        TitleGenerator(store, llm, settings), LinkPrefetcher(attachments), clock, scope,
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
    policies: Policies = Policies(emptyList(), emptyList()),
) {
    val store = FakeChatStore()
    val llm = FakeLlm()
    val browser = FakeBrowserPort()
    val clock = TickClock()
    val settings = SettingsStore(MemoryDataStore(AppSettings(maxSteps = maxSteps)))
    val attachments = FakeAttachments()
    private val tools = FakeToolPort(tools)
    private val wired = wireEngine(scope, store, llm, models, this.tools, attachments, browser, settings, clock, policies = policies)
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
