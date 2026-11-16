package com.ouor.localchat.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.Endpoint
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.PresetId
import com.ouor.localchat.provider.ConnectionResult
import com.ouor.localchat.provider.EndpointDraft
import com.ouor.localchat.provider.KeyHostChanged
import com.ouor.localchat.provider.Preset
import com.ouor.localchat.provider.Presets
import com.ouor.localchat.provider.ProviderRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProvidersViewModel @Inject constructor(providers: ProviderRepository) : ViewModel() {
    val endpoints: StateFlow<List<Endpoint>?> = providers.endpoints.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Connection check result shown under the key field (P-02). */
sealed interface CheckUi {
    data object Checking : CheckUi
    data class Ok(val models: Int) : CheckUi
    data class Failed(val error: ChatError) : CheckUi
}

data class EndpointUiState(
    val isNew: Boolean,
    val loaded: Boolean = false,
    val name: String = "",
    val baseUrl: String = "",
    val key: String = "",
    val preset: PresetId? = null,
    val allowHttp: Boolean = false,
    val maxRetries: Int = 5,
    val enabled: Boolean = true,
    /** Last 4 of the stored key (SEC-2); null = none / unreadable. */
    val keyHint: String? = null,
    val editingKey: Boolean = false,
    val check: CheckUi? = null,
    val refreshing: Boolean = false,
    /** SE 25: the base URL origin changed but no new key was typed; ask before sending the old key there. */
    val confirmKeyHost: Boolean = false,
) {
    val isHttp get() = baseUrl.trim().startsWith("http://", ignoreCase = true)
    val urlValid get() = Regex("^https?://[^\\s/]+.*", RegexOption.IGNORE_CASE).matches(baseUrl.trim())
    val canSave get() = name.isNotBlank() && urlValid && (!isHttp || allowHttp)
}

sealed interface EndpointEffect {
    data class Saved(val endpointId: String) : EndpointEffect
    data object Deleted : EndpointEffect
}

/** SC-02 add / detail (architecture 10.3). */
@HiltViewModel(assistedFactory = EndpointEditViewModel.Factory::class)
class EndpointEditViewModel @AssistedInject constructor(
    @Assisted val endpointId: String?,
    @Assisted private val preset: PresetId?,
    @Assisted private val custom: Boolean,
    private val providers: ProviderRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory { fun create(endpointId: String?, preset: PresetId?, custom: Boolean): EndpointEditViewModel }

    private val _state = MutableStateFlow(EndpointUiState(isNew = endpointId == null))
    val state = _state.asStateFlow()
    val models: StateFlow<List<ModelInfo>> = (endpointId?.let(providers::models) ?: emptyFlow())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _effects = Channel<EndpointEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            if (endpointId == null) {
                // SC-01 "직접 입력" starts blank; otherwise the chosen (or DeepSeek) preset fills name and URL.
                if (!custom) pickPreset(Presets.of(preset ?: PresetId.DEEPSEEK))
                _state.update { it.copy(loaded = true) }
                return@launch
            }
            val e = providers.endpoints.first().firstOrNull { it.id == endpointId } ?: return@launch
            _state.update {
                it.copy(loaded = true, name = e.name, baseUrl = e.baseUrl, preset = e.preset, allowHttp = e.allowHttp, maxRetries = e.maxRetries,
                    enabled = e.enabled, keyHint = providers.keyHint(e.id))
            }
        }
    }

    fun pickPreset(p: Preset) = _state.update { it.copy(name = p.name, baseUrl = p.baseUrl, preset = p.id, allowHttp = p.baseUrl.startsWith("http:"), check = null) }
    fun onName(v: String) = _state.update { it.copy(name = v) }
    fun onUrl(v: String) = _state.update { it.copy(baseUrl = v, check = null) }
    fun onKey(v: String) = _state.update { it.copy(key = v, check = null) }
    fun onAllowHttp(v: Boolean) = _state.update { it.copy(allowHttp = v) }
    fun editKey() = _state.update { it.copy(editingKey = true) }

    private fun draft(s: EndpointUiState) =
        EndpointDraft(s.name.trim(), s.baseUrl.trim(), s.key.trim().takeIf { it.isNotEmpty() }?.let(::ApiKey), s.preset, s.allowHttp, s.maxRetries)

    /** The call that hit [KeyHostChanged]; rerun with keepKeyForNewHost = true once the user confirms. */
    private var pendingKeyHost: (suspend () -> Unit)? = null

    /** Runs [op]; on KeyHostChanged asks the user (SE 25) and remembers [retry] for "보내기". */
    private fun keyGuarded(retry: suspend () -> Unit, op: suspend () -> Unit) = viewModelScope.launch {
        try { op() } catch (_: KeyHostChanged) {
            pendingKeyHost = retry
            _state.update { it.copy(confirmKeyHost = true, check = null) }
        }
    }

    /** "보내기" = keep the stored key for the new host; "키 다시 입력" = open the key field. */
    fun confirmKeyHost(send: Boolean) {
        val retry = pendingKeyHost
        pendingKeyHost = null
        _state.update { it.copy(confirmKeyHost = false, editingKey = it.editingKey || !send) }
        if (send && retry != null) viewModelScope.launch { retry() }
    }

    fun check() {
        val s = _state.value
        _state.update { it.copy(check = CheckUi.Checking) }
        suspend fun run(keep: Boolean) { _state.update { it.copy(check = providers.checkConnection(draft(s), endpointId, keep).toUi()) } }
        keyGuarded({ _state.update { it.copy(check = CheckUi.Checking) }; run(true) }) { run(false) }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        suspend fun run(keep: Boolean) {
            val id = endpointId?.also { providers.update(it, draft(s), keep) } ?: providers.add(draft(s))
            _effects.send(EndpointEffect.Saved(id))
            providers.refreshModels(id)
        }
        keyGuarded({ run(true) }) { run(false) }
    }

    /** Detail screen: key replaced in place (P-02 "키 교체"). */
    fun saveKey() {
        val id = endpointId ?: return
        val k = _state.value.key.trim().takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            providers.setKey(id, ApiKey(k))
            _state.update { it.copy(key = "", editingKey = false, keyHint = providers.keyHint(id)) }
            check()
        }
    }

    fun refresh() {
        val id = endpointId ?: return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val r = providers.refreshModels(id)
            _state.update { it.copy(refreshing = false, check = r.toUi()) }
        }
    }

    fun addModel(modelId: String) { endpointId?.let { id -> viewModelScope.launch { providers.addManualModel(id, modelId) } } }
    fun setHidden(m: ModelInfo, hidden: Boolean) = viewModelScope.launch { providers.setHidden(m.endpointId, m.modelId, hidden) }
    fun setEnabled(v: Boolean) { _state.update { it.copy(enabled = v) }; endpointId?.let { viewModelScope.launch { providers.setEnabled(it, v) } } }
    fun retries(delta: Int) {
        _state.update { it.copy(maxRetries = (it.maxRetries + delta).coerceIn(0, 10)) }
        val id = endpointId ?: return
        val d = draft(_state.value).copy(apiKey = null)
        keyGuarded({ providers.update(id, d, true) }) { providers.update(id, d) }
    }
    fun delete() {
        val id = endpointId ?: return
        viewModelScope.launch { providers.delete(id); _effects.send(EndpointEffect.Deleted) }
    }

    private fun ConnectionResult.toUi() = when (this) {
        is ConnectionResult.Ok -> CheckUi.Ok(modelCount)
        is ConnectionResult.Failed -> CheckUi.Failed(error)
    }
}

/** [autoContext]/[autoOutput]: the value the limit falls back to without a user override ("자동"). */
data class ModelDetailUiState(
    val model: ModelInfo? = null,
    val overrides: ModelOverrides = ModelOverrides(),
    val endpointName: String = "",
    val autoContext: Sourced<Int?> = Sourced(null, CapSource.UNKNOWN),
    val autoOutput: Sourced<Int?> = Sourced(null, CapSource.UNKNOWN),
)

// FB-6 limit menu: K = 1024, 1M = 1,048,576.
val CONTEXT_PRESETS = listOf(4, 8, 16, 32, 64, 128, 200, 256).map { it * 1024 } + 1_048_576
val OUTPUT_PRESETS = listOf(1, 2, 4, 8, 16, 32, 64, 128).map { it * 1024 }
val CONTEXT_RANGE = 1_024..10_000_000
val OUTPUT_RANGE = 256..1_000_000

sealed interface LimitInput {
    data object Empty : LimitInput
    data object NotNumber : LimitInput
    data object OutOfRange : LimitInput
    data class Ok(val value: Int) : LimitInput
}

/**
 * Custom limit text: ASCII digits only (not full-width ones), whole numbers inside [range]. Thousands may be
 * grouped the way any locale prints them ("1,024", de "1.024", fr "1 048 576", ch "1'024"); "1.5" is not a number.
 */
fun parseLimit(text: String, range: IntRange): LimitInput {
    val t = text.trim()
    if (t.all { it in GROUP_SEPARATORS }) return LimitInput.Empty
    if (!t.all { it in '0'..'9' } && !GROUPED.matches(t)) return LimitInput.NotNumber
    val n = t.filter { it in '0'..'9' }.toLongOrNull() ?: return LimitInput.OutOfRange
    return if (n in range.first..range.last) LimitInput.Ok(n.toInt()) else LimitInput.OutOfRange
}

// Space, NBSP and narrow NBSP (fr), apostrophe and its typographic form (ch).
private const val GROUP_SEPARATORS = ",. '  ’"
private val GROUPED = Regex("""[0-9]{1,3}([$GROUP_SEPARATORS])[0-9]{3}(?:\1[0-9]{3})*""")

/** Preset values read as "128K" / "1M"; anything else as the exact number "128,000". */
fun limitLabel(n: Int, presets: List<Int>): String = when {
    n !in presets -> "%,d".format(n)
    n % 1_048_576 == 0 -> "${n / 1_048_576}M"
    else -> "${n / 1024}K"
}

/** SC-02a: values with their source badges; edits replace the whole override set. */
@HiltViewModel(assistedFactory = ModelDetailViewModel.Factory::class)
class ModelDetailViewModel @AssistedInject constructor(
    @Assisted("endpoint") private val endpointId: String,
    @Assisted("model") private val modelId: String,
    private val providers: ProviderRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory { fun create(@Assisted("endpoint") endpointId: String, @Assisted("model") modelId: String): ModelDetailViewModel }

    private val _state = MutableStateFlow(ModelDetailUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val name = providers.endpoints.first().firstOrNull { it.id == endpointId }?.name.orEmpty()
            _state.update { it.copy(overrides = providers.overrides(endpointId, modelId), endpointName = name) }
            providers.models(endpointId).collect { list ->
                val m = list.firstOrNull { it.modelId == modelId }
                // Re-read on every model change: a refresh can bring a new server value.
                val auto = providers.autoCaps(endpointId, modelId)
                _state.update { s ->
                    s.copy(
                        model = m,
                        autoContext = auto?.contextWindow ?: Sourced(null, CapSource.UNKNOWN),
                        autoOutput = auto?.maxOutputTokens ?: Sourced(null, CapSource.UNKNOWN),
                    )
                }
            }
        }
    }

    fun edit(transform: ModelOverrides.() -> ModelOverrides) {
        val next = _state.value.overrides.transform()
        _state.update { it.copy(overrides = next) }
        viewModelScope.launch { providers.updateModel(endpointId, modelId, next) }
    }

    fun reset() = viewModelScope.launch {
        providers.resetModel(endpointId, modelId)
        _state.update { it.copy(overrides = ModelOverrides()) }
    }
}
