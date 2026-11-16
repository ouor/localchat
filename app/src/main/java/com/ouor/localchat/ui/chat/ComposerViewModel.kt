package com.ouor.localchat.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ouor.localchat.attach.AttachmentIngest
import com.ouor.localchat.attach.ValidationIssue
import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.chat.ChatRejected
import com.ouor.localchat.chat.DraftRepository
import com.ouor.localchat.chat.SessionRepository
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.provider.ProviderRepository
import com.ouor.localchat.speech.VoiceInput
import com.ouor.localchat.speech.VoiceState
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Toggle availability from the model (P-05). [reason] non-null = disabled. */
data class ToggleUi(val on: Boolean, val enabled: Boolean, val locked: Boolean = false)

data class ComposerUiState(
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val model: ModelInfo? = null,
    /** The conversation's model is gone (P-08): chip shows "모델 없음". */
    val modelMissing: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
    val endpointNames: Map<String, String> = emptyMap(),
    val reasoning: ToggleUi = ToggleUi(false, false),
    val effort: String? = null,
    val efforts: List<String> = emptyList(),
    val search: ToggleUi = ToggleUi(false, false),
    val browser: ToggleUi = ToggleUi(false, false),
    val running: Boolean = false,
    val voice: VoiceState = VoiceState.Idle,
    /** I-03: images that the model cannot read. */
    val imageIssue: Boolean = false,
    /** I-05: send waits for preparing files. */
    val waitingForFiles: Boolean = false,
) {
    val canSend: Boolean get() = !running && model != null && (text.isNotBlank() || attachments.isNotEmpty()) &&
        attachments.none { it.state is PrepState.Failed }
}

sealed interface ComposerEffect {
    data class Sent(val sessionId: String) : ComposerEffect
    data class Rejected(val reason: ChatRejected.Reason) : ComposerEffect
}

/** I-01 composer (architecture 10.3). Draft key = session id or [Draft.NEW_CHAT]. */
@OptIn(FlowPreview::class)
@HiltViewModel(assistedFactory = ComposerViewModel.Factory::class)
class ComposerViewModel @AssistedInject constructor(
    @Assisted private val sessionId: String?,
    private val engine: ChatEngine,
    private val drafts: DraftRepository,
    private val ingest: AttachmentIngest,
    private val settings: SettingsStore,
    private val voiceInput: VoiceInput,
    private val files: AttachmentFiles,
    providers: ProviderRepository,
    sessions: SessionRepository,
) : ViewModel(), ComposerActions {

    @AssistedFactory
    interface Factory { fun create(sessionId: String?): ComposerViewModel }

    private val key = sessionId ?: Draft.NEW_CHAT
    private val text = MutableStateFlow("")
    private val attachments = MutableStateFlow<List<Attachment>>(emptyList())
    private val picked = MutableStateFlow<ModelRef?>(null)
    private val local = MutableStateFlow(Local())
    private var lastSaved: Draft? = null

    private data class Local(val imageIssue: Boolean = false, val waiting: Boolean = false, val handledVoice: VoiceState? = null)

    private val _effects = Channel<ComposerEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val sessionModel = sessionId?.let { id -> sessions.observeSession(id).map { it?.lastModel } } ?: flowOf(null)

    val state: StateFlow<ComposerUiState> = combine(
        combine(text, attachments, local, ::Triple),
        combine(providers.allVisibleModels, picked, sessionModel, settings.settings, ::Quad),
        combine(engine.runs, voiceInput.state, providers.endpoints, ::Triple),
    ) { (t, atts, l), (models, pick, sModel, app), (runs, voice, endpoints) ->
        val wanted = pick ?: sModel ?: app.defaultModel
        val model = wanted?.let { w -> models.firstOrNull { it.endpointId == w.endpointId && it.modelId == w.modelId } }
            ?: if (wanted == null) models.maxByOrNull { it.lastUsedAt ?: 0L } else null
        val caps = model?.caps
        val reasoningSpec = caps?.reasoning?.value
        val alwaysOn = reasoningSpec?.wire == ReasoningWire.ALWAYS_ON
        val tools = caps?.toolCalling?.value == true
        val o = app.lastOptions
        ComposerUiState(
            text = t,
            attachments = atts,
            model = model,
            modelMissing = wanted != null && model == null,
            models = models,
            endpointNames = endpoints.associate { it.id to it.name },
            reasoning = ToggleUi(alwaysOn || (o.reasoning && reasoningSpec != null), reasoningSpec != null && !alwaysOn, locked = alwaysOn),
            effort = o.reasoningEffort?.takeIf { reasoningSpec?.efforts?.contains(it) == true } ?: reasoningSpec?.defaultEffort,
            efforts = reasoningSpec?.efforts.orEmpty(),
            search = ToggleUi(o.search && tools, tools),
            browser = ToggleUi(o.browser && tools, tools),
            running = sessionId != null && runs.containsKey(sessionId),
            voice = voice.takeUnless { it == l.handledVoice } ?: VoiceState.Idle,
            imageIssue = l.imageIssue,
            waitingForFiles = l.waiting,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ComposerUiState())

    init {
        viewModelScope.launch {
            val d = drafts.observe(key).first()
            lastSaved = d
            text.value = d.text
            attachments.value = d.attachmentIds.map(::unknown)
            // Shared content (I-02) lands in the new-chat draft while this screen is open.
            drafts.observe(key).drop(1).collect { incoming ->
                if (incoming == lastSaved) return@collect
                if (incoming.text != lastSaved?.text) text.value = incoming.text
                val known = attachments.value.associateBy { it.id }
                attachments.value = incoming.attachmentIds.map { known[it] ?: unknown(it) }
                lastSaved = incoming
            }
        }
        viewModelScope.launch {
            combine(text, attachments) { t, a -> t to a.map { it.id } }.drop(1).debounce(300).collect { (t, ids) ->
                drafts.update(key, t, ids)
                lastSaved = drafts.observe(key).first()
            }
        }
        viewModelScope.launch {
            // I-05: send once everything is ready.
            attachments.collect { list ->
                if (local.value.waiting && list.none { it.state is PrepState.Preparing }) { local.update { it.copy(waiting = false) }; send() }
            }
        }
        viewModelScope.launch {
            voiceInput.state.collect { v ->
                if (v is VoiceState.Result && v != local.value.handledVoice) {
                    text.update { (if (it.isBlank()) "" else "$it ") + v.text }
                    local.update { it.copy(handledVoice = v) }
                }
            }
        }
    }

    override fun onText(value: String) { text.value = value; local.update { it.copy(imageIssue = false) } }

    override fun attach(uris: List<Uri>) = uris.forEach { uri -> collect(ingest.ingest(uri)) }

    override fun retry(id: String) { runCatching { collect(ingest.retry(id)) } }

    override fun remove(id: String) = attachments.update { list -> list.filterNot { it.id == id } }

    override fun selectModel(ref: ModelRef) {
        picked.value = ref
        local.update { it.copy(imageIssue = false) }
        if (sessionId == null) viewModelScope.launch { settings.update { copy(defaultModel = ref) } }
    }

    override fun toggleReasoning() = options { copy(reasoning = !reasoning) }
    override fun setEffort(effort: String?) = options { copy(reasoning = effort != null, reasoningEffort = effort ?: reasoningEffort) }
    override fun toggleSearch() = options { copy(search = !search) }
    override fun toggleBrowser() = options { copy(browser = !browser) }

    override fun startVoice() = voiceInput.start(Locale.getDefault().toLanguageTag())
    override fun stopVoice() = voiceInput.stop()
    override fun cancelVoice() = voiceInput.cancel()

    fun stop() { sessionId?.let(engine::stop) }

    /** Coil model for an image tile (the original file). */
    override fun thumbnail(id: String): Any = files.original(id)

    /** I-03 "이미지 빼고 보내기". */
    override fun sendWithoutImages() {
        attachments.update { list -> list.filterNot { it.kind == AttachmentKind.IMAGE } }
        local.update { it.copy(imageIssue = false) }
        send()
    }

    override fun send() {
        val s = state.value
        val model = s.model ?: return
        val atts = attachments.value
        if (atts.any { it.state is PrepState.Preparing }) { local.update { it.copy(waiting = true) }; return }
        val issues = ingest.validate(atts.filter { it.name.isNotEmpty() }, model.caps).issues
        if (issues.any { it.kind == ValidationIssue.Kind.IMAGES_UNSUPPORTED }) { local.update { it.copy(imageIssue = true) }; return }
        val options = GenOptions(
            reasoning = s.reasoning.on,
            reasoningEffort = if (s.reasoning.on) s.effort else null,
            search = s.search.on,
            browser = s.browser.on,
        )
        val draft = Draft(key, text.value.trim(), atts.map { it.id }, System.currentTimeMillis())
        viewModelScope.launch {
            try {
                val sid = engine.send(sessionId, draft, options, model.ref)
                text.value = ""
                attachments.value = emptyList()
                lastSaved = Draft(key, "", emptyList(), 0)
                _effects.send(ComposerEffect.Sent(sid))
            } catch (e: ChatRejected) {
                _effects.send(ComposerEffect.Rejected(e.reason))
            }
        }
    }

    private fun options(transform: GenOptions.() -> GenOptions) {
        viewModelScope.launch { settings.update { copy(lastOptions = lastOptions.transform()) } }
    }

    /** First emission adds the tile; later ones update it unless the user removed it meanwhile. */
    private fun collect(flow: kotlinx.coroutines.flow.Flow<Attachment>) {
        viewModelScope.launch {
            var first = true
            flow.collect { a ->
                attachments.update { list ->
                    when {
                        list.any { it.id == a.id } -> list.map { if (it.id == a.id) a else it }
                        first -> list + a
                        else -> list
                    }
                }
                first = false
            }
        }
    }

    /** Draft ids without metadata (restored draft, shared file still preparing elsewhere). */
    private fun unknown(id: String) = Attachment(id, "", "", 0, AttachmentKind.DOCUMENT, PrepState.Ready, true, createdAt = 0)
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
