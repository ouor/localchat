package com.ouor.localchat.ui.chat

import com.ouor.localchat.ui.components.gaugePercent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.chat.ChatRejected
import com.ouor.localchat.chat.SaveHealth
import com.ouor.localchat.chat.SessionRepository
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.core.MessageTree
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.provider.ProviderRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UserUi(
    val node: MessageNode,
    val text: String,
    val attachmentIds: List<String>,
    val version: Int,
    val versions: List<String>,
)

data class AssistantUi(
    val node: MessageNode,
    val body: MessageBody.Assistant,
    val version: Int,
    val versions: List<String>,
    val live: Boolean,
    val canRegenerate: Boolean,
    val canContinue: Boolean,
)

/** One user turn and the reply under it, as drawn (SC-08). */
data class TurnUi(val user: UserUi, val reply: AssistantUi?)

data class ChatUiState(
    val loading: Boolean = true,
    val title: String = "",
    val turns: List<TurnUi> = emptyList(),
    /** Metadata of the user messages' attachments by id; ids without a row show a placeholder tile. */
    val attachments: Map<String, com.ouor.localchat.core.model.Attachment> = emptyMap(),
    /** A-06: divider goes after the turn whose reply id this is. */
    val summaryAfterId: String? = null,
    val runPhase: RunPhase? = null,
    val step: Int = 0,
    val maxSteps: Int = 0,
    val saveFailing: Boolean = false,
    /** A-07: null = unknown context window. */
    val gaugePercent: Int? = null,
    val usageTokens: Int? = null,
    val contextWindow: Int? = null,
    val lastModel: ModelRef? = null,
    /** M-09: reply being read aloud. */
    val readingId: String? = null,
) {
    val running get() = runPhase != null
    val pause: Pause? get() = (runPhase as? RunPhase.Paused)?.pause
}

sealed interface ChatEffect {
    data class Rejected(val reason: ChatRejected.Reason) : ChatEffect
    data object Deleted : ChatEffect
    data object CompactFailed : ChatEffect
    data object NothingToCompact : ChatEffect
    data object ReadAloudUnavailable : ChatEffect
}

/** SC-03/04 (architecture 10.3). [sessionId] null = new chat: nothing to observe until the first send. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted override val sessionId: String?,
    private val engine: ChatEngine,
    private val sessions: SessionRepository,
    providers: ProviderRepository,
    private val tts: com.ouor.localchat.speech.ReadAloud,
    private val settings: com.ouor.localchat.storage.SettingsStore,
) : ViewModel(), ChatActions {

    @AssistedFactory
    interface Factory { fun create(sessionId: String?): ChatViewModel }

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val attachmentIds = MutableStateFlow<List<String>>(emptyList())

    val state: StateFlow<ChatUiState> = if (sessionId == null) {
        sessions.saveHealth.map { ChatUiState(loading = false, saveFailing = it == SaveHealth.FAILING) }
    } else {
        val tree = combine(sessions.observeSession(sessionId), sessions.observeMessages(sessionId), engine.runs) { s, nodes, runs ->
            val run = runs[sessionId]
            val live = run?.live
            val merged = if (live == null) nodes else nodes.map { if (it.id == live.id) live else it }
            Triple(s, merged, run)
        }.sample(100) // R-05
        combine(tree, sessions.observeSummaries(sessionId), sessions.saveHealth, providers.allVisibleModels) { (s, nodes, run), summaries, health, models ->
            val path = MessageTree.path(s?.rootSelectedId, nodes)
            val turns = toTurns(path, nodes, run?.live?.id)
            val pathIds = path.map { it.id }.toSet()
            val summary = summaries.filter { it.coversUpToId in pathIds }.maxByOrNull { it.createdAt }
            val window = models.firstOrNull { it.endpointId == s?.lastModel?.endpointId && it.modelId == s.lastModel.modelId }?.caps?.contextWindow?.value
            val usage = path.lastOrNull { (it.body as? MessageBody.Assistant)?.usage != null }
                ?.let { (it.body as MessageBody.Assistant).usage }?.let { it.promptTokens + it.completionTokens }
            ChatUiState(
                loading = false,
                title = s?.title.orEmpty(),
                turns = turns,
                summaryAfterId = summary?.coversUpToId,
                runPhase = run?.phase,
                step = run?.step ?: 0,
                maxSteps = run?.maxSteps ?: 0,
                saveFailing = health == SaveHealth.FAILING,
                gaugePercent = gaugePercent(usage, window),
                usageTokens = usage,
                contextWindow = window,
                lastModel = s?.lastModel,
            )
        }
    }.onEach { s ->
        // + the SC-13 approval preview, an ARTIFACT attachment (track C 9).
        val preview = ((s.runPhase as? RunPhase.Paused)?.pause as? Pause.Approval)?.request?.screenshotId
        attachmentIds.value = s.turns.flatMap { it.user.attachmentIds } + listOfNotNull(preview)
    }
        .combine(attachmentIds.flatMapLatest { sessions.observeAttachments(it) }) { s, rows -> s.copy(attachments = rows.associateBy { it.id }) }
        .combine(tts.state) { s, r -> s.copy(readingId = (r as? com.ouor.localchat.speech.ReadAloudState.Speaking)?.messageId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    override fun stop() { sessionId?.let(engine::stop) }
    override fun toolStep(block: com.ouor.localchat.core.model.ToolCallBlock) = engine.toolStep(block)
    override fun selectVersion(messageId: String) = launch { sessions.selectVersion(messageId) }
    override fun regenerate(assistantId: String, model: ModelRef) = guarded { engine.regenerate(assistantId, model) }
    override fun continueReply(assistantId: String) = guarded { engine.continueReply(assistantId) }
    override fun editAndResend(userId: String, text: String, attachmentIds: List<String>, model: ModelRef) =
        guarded { engine.editAndResend(userId, text, attachmentIds, model) }
    override fun resolve(resolution: Resolution) { sessionId?.let { engine.resolve(it, resolution) } }
    override fun retryWrites() = launch { sessions.retryPendingWrites() }
    override fun rename(title: String) = launch { sessionId?.let { sessions.rename(it, title) } }
    override fun delete() = launch {
        val id = sessionId ?: return@launch
        engine.stop(id)
        sessions.delete(id)
        _effects.send(ChatEffect.Deleted)
    }
    override fun compactNow() = guarded {
        val id = sessionId ?: return@guarded
        try { if (!engine.compactNow(id)) _effects.send(ChatEffect.NothingToCompact) } catch (e: Exception) {
            if (e is ChatRejected) throw e
            _effects.send(ChatEffect.CompactFailed)
        }
    }

    override fun readAloud(messageId: String, text: String) {
        if (state.value.readingId == messageId) { tts.stop(); return }
        launch {
            val s = settings.settings.first()
            tts.setVoice(s.ttsVoice, s.ttsRate)
            tts.speak(messageId, text)
        }
    }

    init {
        viewModelScope.launch {
            tts.state.drop(1).collect { if (it == com.ouor.localchat.speech.ReadAloudState.Unavailable) _effects.send(ChatEffect.ReadAloudUnavailable) }
        }
    }

    private fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }
    private fun guarded(block: suspend () -> Unit) = launch {
        try { block() } catch (e: ChatRejected) { _effects.send(ChatEffect.Rejected(e.reason)) }
    }

    companion object {
        /** Pure: path → turns with sibling versions (M-06). */
        fun toTurns(path: List<MessageNode>, all: List<MessageNode>, liveId: String?): List<TurnUi> {
            val out = mutableListOf<TurnUi>()
            var i = 0
            while (i < path.size) {
                val u = path[i]
                val ub = u.body as? MessageBody.User
                if (ub == null) { i++; continue }
                val us = MessageTree.siblings(all, u.parentId).map { it.id }
                val user = UserUi(u, ub.text, ub.attachmentIds, us.indexOf(u.id) + 1, us)
                val a = path.getOrNull(i + 1)
                val ab = a?.body as? MessageBody.Assistant
                val reply = if (a != null && ab != null) {
                    val s = MessageTree.siblings(all, a.parentId).map { it.id }
                    AssistantUi(a, ab, s.indexOf(a.id) + 1, s, a.id == liveId, MessageTree.canRegenerate(ab), MessageTree.canContinue(ab))
                } else null
                out += TurnUi(user, reply)
                i += if (reply != null) 2 else 1
            }
            return out
        }
    }
}
