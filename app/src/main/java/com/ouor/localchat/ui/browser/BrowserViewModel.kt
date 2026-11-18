package com.ouor.localchat.ui.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ouor.localchat.browser.Answer
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.Highlight
import com.ouor.localchat.browser.SearchEngines
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.policy.Notice
import com.ouor.localchat.policy.Policies
import com.ouor.localchat.storage.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** SC-11 compact states. Expanded width shows the panel beside the chat instead (always Half-like). */
enum class PanelState { Hidden, Peek, Half, Full }

/** Layer 1's state for the drawer (browser-layers 6). Every tab shows; no tab belongs to a chat. */
data class BrowserUiState(
    val panel: PanelState = PanelState.Hidden,
    val tabs: List<TabInfo> = emptyList(),
    /** The tab the user picked last ([BrowserEngine.front]); only the user's input changes it (P-STABLE). */
    val front: Int? = null,
    val sessionId: String? = null,
    /** The tool an AI runs on each tab right now, and for which chat (6-2). */
    val activity: Map<Int, ToolActivity> = emptyMap(),
    val highlight: Highlight? = null,
    /** Events waiting for the user ([BrowserEngine.prompts]). */
    val prompts: List<BrowserEvent> = emptyList(),
    /** This chat's run waits for the user in a tab (ask_user, 6-4). */
    val userStep: Pause.UserStep? = null,
    /** The policies' badges and banners. */
    val notices: List<Notice> = emptyList(),
    val showTabs: Boolean = false,
    /** This chat's run is going (B-09 ②). */
    val running: Boolean = false,
    /** FB5 B1: the height a keyboard for the panel lifted it from to Full; it goes back there when the keyboard closes. */
    val keyboardFrom: PanelState? = null,
    /** A new tab could not be opened: a snackbar says so (6-1). */
    val openFailed: Boolean = false,
) {
    /** The tab the drawer shows: the front, else the tab used last (6-1). */
    val current: TabInfo? get() = tabs.firstOrNull { it.id == front } ?: tabs.maxByOrNull { it.lastUsedAt }
    /** The shown tab's event is asked in place. */
    val prompt: BrowserEvent? get() = prompts.firstOrNull { it.tabId == current?.id }
    /** Another tab's event (a policy handed it to the user): a banner over the drawer. */
    val otherPrompt: BrowserEvent? get() = prompts.firstOrNull { it.tabId != current?.id }
    /** A policy's banner over [tabId] when it shows (one at a time; null tabId = about the whole browser). */
    fun banner(tabId: Int): Notice? = notices.firstOrNull { it.style == Notice.Style.BANNER && (it.tabId == tabId || it.tabId == null) }
    /** The policies' badges on [tabId]'s card. */
    fun badges(tabId: Int): List<String> = notices.filter { it.style == Notice.Style.BADGE && it.tabId == tabId }.map { it.text }
}

/**
 * FB5 B1: a keyboard for the panel opens ([up]) → Full, remembering the user's height; it closes → back to that height,
 * but only while the panel is still Full. Any height set meanwhile ([withPanel]: a drag, Back, a user step, a tab picked)
 * drops the memory, so that height wins.
 */
internal fun BrowserUiState.keyboard(up: Boolean): BrowserUiState = when {
    up -> if (panel < PanelState.Full) copy(keyboardFrom = panel, panel = PanelState.Full) else this
    else -> copy(panel = keyboardFrom?.takeIf { panel == PanelState.Full } ?: panel, keyboardFrom = null)
}

/** A height the user or the run chose: it replaces the keyboard's return height (FB5 B1). */
internal fun BrowserUiState.withPanel(p: PanelState): BrowserUiState = copy(panel = p, keyboardFrom = null)

/**
 * The run's phase into the drawer (6-2, 6-4). The tab shown never changes here (P-STABLE): a running tool only brings
 * the Peek strip when the drawer is hidden; an ask_user wait opens its tab Full only when the drawer is hidden,
 * otherwise it is a banner ([select] is then the drawer's job).
 */
internal fun BrowserUiState.onPhase(phase: RunPhase?, select: (Int) -> Unit): BrowserUiState {
    val base = copy(running = phase != null, panel = if (phase == null && panel == PanelState.Peek) PanelState.Hidden else panel)
    return when (phase) {
        is RunPhase.RunningTool -> base.copy(userStep = null).let { if (phase.tabId != null && it.panel == PanelState.Hidden) it.withPanel(PanelState.Peek) else it }
        is RunPhase.Paused -> when (val p = phase.pause) {
            is Pause.UserStep -> if (base.userStep == p) base else if (base.panel <= PanelState.Peek) {
                select(p.tabId)
                base.copy(userStep = p, front = p.tabId).withPanel(PanelState.Full)
            } else base.copy(userStep = p)
            else -> base.copy(userStep = null)
        }
        else -> base.copy(userStep = null)
    }
}

@HiltViewModel(assistedFactory = BrowserViewModel.Factory::class)
class BrowserViewModel @AssistedInject constructor(
    @Assisted private val sessionId: String?,
    private val engine: BrowserEngine,
    private val chat: ChatEngine,
    private val settings: SettingsStore,
    policies: Policies,
) : ViewModel(), BrowserActions {

    @AssistedFactory
    interface Factory { fun create(sessionId: String?): BrowserViewModel }

    private val local = MutableStateFlow(BrowserUiState(sessionId = sessionId))

    private val facts = combine(engine.tabs, engine.front, engine.activity, engine.highlight, engine.prompts) { tabs, front, activity, h, prompts ->
        { s: BrowserUiState -> s.copy(tabs = tabs, front = front?.tabId, activity = activity, highlight = h, prompts = prompts) }
    }

    val state: StateFlow<BrowserUiState> = combine(local, facts, policies.notices) { l, f, notices -> f(l).copy(notices = notices) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    /** Called with the chat's run phase. */
    fun onRunPhase(phase: RunPhase?) = local.update { it.onPhase(phase, engine::select) }

    override fun savePdf(id: Int, uri: Uri, done: (Boolean) -> Unit) {
        viewModelScope.launch { done(engine.savePdf(id, uri)) }
    }

    override fun setPanel(p: PanelState) {
        if (p >= PanelState.Half) pickShown()
        local.update { it.withPanel(p) }
    }

    override fun keyboard(up: Boolean) = local.update { it.keyboard(up) }

    /** Opening the drawer with no front is picking the tab it shows (6-1). */
    private fun pickShown() {
        val s = state.value
        if (s.front == null || s.tabs.none { it.id == s.front }) s.current?.let { engine.select(it.id) }
    }

    /** Top-bar browser button / link tap: a link opens a tab; otherwise the drawer shows the current tab, or a new one. */
    override fun open(url: String?) {
        if (url != null || state.value.tabs.isEmpty()) newTab(url) else setPanel(maxOf(local.value.panel, PanelState.Half))
    }

    /** No limit check (6-1); a failure is said in a snackbar. */
    override fun newTab(url: String?) {
        val id = try { engine.open(url) } catch (_: Exception) { local.update { it.copy(openFailed = true) }; return }
        engine.select(id)
        local.update { it.copy(showTabs = false).withPanel(maxOf(it.panel, PanelState.Half)) }
    }

    override fun dismissOpenFailed() = local.update { it.copy(openFailed = false) }

    override fun select(id: Int) {
        engine.select(id)
        local.update { it.copy(showTabs = false).withPanel(maxOf(it.panel, PanelState.Half)) }
    }

    override fun showTabs(v: Boolean) = local.update { it.copy(showTabs = v) }
    override fun close(id: Int) = engine.close(id)

    override fun navigate(input: String) {
        val t = state.value.current ?: return
        val text = input.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val url = when {
                Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text) -> text
                ' ' !in text && '.' in text -> "https://$text"
                else -> SearchEngines.url(settings.settings.first().searchEngine, text)
            }
            engine.navigate(t.id, url)
        }
    }

    override fun back() { state.value.current?.let { engine.goBack(it.id) } }
    override fun forward() { state.value.current?.let { engine.goForward(it.id) } }
    override fun reload() { state.value.current?.let { engine.reload(it.id) } }
    override fun reload(id: Int) = engine.reload(id)

    /** Stops the run of the chat working on a tab (the activity bar), or this chat's. */
    override fun stopRun(sessionId: String?) { (sessionId ?: this.sessionId)?.let(chat::stop) }

    /** The user did what ask_user asked: the run goes on (6-4). */
    override fun userStepDone() {
        sessionId?.let { chat.resolve(it, Resolution.UserStepDone) }
        local.update { it.copy(userStep = null) }
    }

    override fun answer(e: BrowserEvent, answer: Answer) = engine.answer(e.id, answer)
    override fun webView(id: Int): StateFlow<android.webkit.WebView?>? = runCatching { engine.webView(id) }.getOrNull()
    override fun show(id: Int) = engine.show(id)
    override fun park(id: Int) = engine.park(id)
}
