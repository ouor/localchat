package com.ouor.localchat.ui.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserPrompt
import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.Highlight
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.PageError
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.storage.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** SC-11 compact states. Expanded width shows the panel beside the chat instead (always Half-like). */
enum class PanelState { Hidden, Peek, Half, Full }

data class BrowserUiState(
    val panel: PanelState = PanelState.Hidden,
    val tabs: List<TabInfo> = emptyList(),
    val currentId: Int? = null,
    val sessionId: String? = null,
    /** Tool the AI runs on each tab right now (B-09 ②). */
    val aiActivity: Map<Int, String> = emptyMap(),
    val highlight: Highlight? = null,
    /** Prompts the user answers (AI-tab JS dialogs are answered by the dialog_handle tool, not shown). */
    val prompt: BrowserPrompt? = null,
    /** Set while the run waits for the user in this tab (B-05). */
    val handoff: Pause.Handoff? = null,
    val showTabs: Boolean = false,
    /** B-09 ③: the user touched an AI tab; ask to switch to manual control. */
    val touchedAiTab: Int? = null,
    /** Tabs on a sensitive site: the AI may not read or operate them (B-S3, SE 28). */
    val sensitive: Set<Int> = emptySet(),
    /** This chat's run is going (B-09 ②). */
    val running: Boolean = false,
) {
    /** BUG-04: by default the panel and peek show only user tabs and this chat's AI tabs. */
    val visible: List<TabInfo> get() = tabs.filter { it.owner !is Owner.Ai || isMine(it) }
    /** A picked tab (even another chat's, chosen in the tab list); a closed one falls back to [visible]. */
    val current: TabInfo? get() = tabs.firstOrNull { it.id == currentId } ?: visible.lastOrNull()
    fun isMine(t: TabInfo) = t.owner is Owner.Ai && (t.owner as Owner.Ai).sessionId == sessionId
}

/** Engine state into the panel state. [tabs] are BrowserEngine.visibleTabs; a prompt of any other tab is not shown. */
internal fun BrowserUiState.merge(tabs: List<TabInfo>, ai: Map<Int, String>, h: Highlight?, prompts: List<BrowserPrompt>): BrowserUiState {
    return copy(
        tabs = tabs, aiActivity = ai, highlight = h,
        prompt = prompts.firstOrNull { p ->
            val owner = tabs.firstOrNull { it.id == p.tabId }?.owner ?: return@firstOrNull false
            // AI-tab JS dialogs belong to the dialog_handle tool (api-spec BrowserEngine.prompts).
            !(p is BrowserPrompt.Dialog && owner is Owner.Ai)
        },
    )
}

/** The handoff ended with "AI에게 돌려주기": the panel keeps the height the user has it at (FB4 U-3, P-STABLE). */
internal fun BrowserUiState.handedBack(): BrowserUiState = copy(handoff = null)

/** Re-marks sensitive tabs when the tabs or the rule change (a settings change alone must update the banner). */
internal fun Flow<BrowserUiState>.withSensitive(isSensitive: Flow<(url: String) -> Boolean>): Flow<BrowserUiState> =
    combine(isSensitive) { s, sensitive -> s.copy(sensitive = s.tabs.filter { sensitive(it.url) }.mapTo(HashSet()) { it.id }) }

@HiltViewModel(assistedFactory = BrowserViewModel.Factory::class)
class BrowserViewModel @AssistedInject constructor(
    @Assisted private val sessionId: String?,
    private val engine: BrowserEngine,
    private val chat: ChatEngine,
    private val settings: SettingsStore,
) : ViewModel(), BrowserActions {

    @AssistedFactory
    interface Factory { fun create(sessionId: String?): BrowserViewModel }

    private val local = MutableStateFlow(BrowserUiState(sessionId = sessionId))

    // B-09 ②: only this chat's AI activity drives its "AI is operating" bar.
    private val activity = sessionId?.let(engine::sessionActivity) ?: flowOf(emptyMap())

    val state: StateFlow<BrowserUiState> = combine(local, engine.visibleTabs, activity, engine.highlight, engine.prompts) { l, tabs, ai, h, prompts ->
        l.merge(tabs, ai, h, prompts)
    }.withSensitive(engine.sensitive.map { it::isSensitive }).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            engine.userTouches.collect { id -> local.update { it.copy(touchedAiTab = id) } }
        }
    }

    /** Called with the chat's run phase: auto-peek when the AI browses, full screen on handoff (B-02, B-05). */
    fun onRunPhase(phase: RunPhase?) {
        // The auto-peek belongs to the run; it goes away with it (an opened panel stays).
        local.update { it.copy(running = phase != null, panel = if (phase == null && it.panel == PanelState.Peek) PanelState.Hidden else it.panel) }
        when (phase) {
            is RunPhase.RunningTool -> phase.tabId?.let { id ->
                local.update { it.copy(currentId = id, panel = if (it.panel == PanelState.Hidden) PanelState.Peek else it.panel) }
            }
            is RunPhase.Paused -> (phase.pause as? Pause.Handoff)?.let { h ->
                local.update { it.copy(handoff = h, currentId = h.tabId, panel = PanelState.Full) }
            } ?: local.update { it.copy(handoff = null) }
            else -> local.update { it.copy(handoff = null) }
        }
    }

    override fun setPanel(p: PanelState) = local.update { it.copy(panel = p) }

    /** Top-bar browser button / link tap: show the current tab, or open a user tab. */
    override fun open(url: String?) {
        if (url != null || state.value.visible.isEmpty()) newTab(url)
        else local.update { it.copy(panel = maxOf(it.panel, PanelState.Half)) }
    }

    /** At the tab limit the tab list opens instead, so the user can close one (api-spec: UI checks tabs.size). */
    override fun newTab(url: String?) {
        if (state.value.tabs.size >= BrowserEngine.MAX_TABS) { local.update { it.copy(showTabs = true) }; return }
        viewModelScope.launch {
            val home = searchUrl(settings.settings.first().searchEngine, null)
            // The AI may have filled the last slot since the check: show the tab list so the user can close one.
            val id = try { engine.openUserTab(url ?: home) } catch (_: PageError) { local.update { it.copy(showTabs = true) }; return@launch }
            local.update { it.copy(currentId = id, showTabs = false, panel = maxOf(it.panel, PanelState.Half)) }
        }
    }

    override fun select(id: Int) = local.update { it.copy(currentId = id, showTabs = false, panel = maxOf(it.panel, PanelState.Half)) }
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
                else -> searchUrl(settings.settings.first().searchEngine, text)
            }
            engine.navigate(t.id, url)
        }
    }

    override fun back() { state.value.current?.let { engine.goBack(it.id) } }
    override fun forward() { state.value.current?.let { engine.goForward(it.id) } }
    override fun reload() { state.value.current?.let { engine.reload(it.id) } }

    /** B-09 ②: AI tab → manual control; the AI's tools wait on this tab. */
    override fun takeOver(id: Int) { engine.setControl(id, Control.USER); local.update { it.copy(touchedAiTab = null) } }
    override fun dismissTouch() = local.update { it.copy(touchedAiTab = null) }
    /** Give manual control back; during a handoff this also resumes the run (B-05). */
    override fun giveBack(id: Int) {
        engine.setControl(id, Control.AI)
        val h = local.value.handoff
        if (h != null && h.tabId == id) { sessionId?.let { chat.resolve(it, Resolution.HandoffReturned) }; local.update { it.handedBack() } }
    }
    /** B-04: hand a user tab to this chat's AI, or take an AI tab as a user tab. */
    override fun handToAi(id: Int) { sessionId?.let { engine.setOwner(id, Owner.Ai(it)) } }
    override fun takeTab(id: Int) = engine.setOwner(id, Owner.User)
    override fun stopRun() { sessionId?.let(chat::stop); local.update { it.copy(handoff = null) } }

    override fun answerDialog(p: BrowserPrompt.Dialog, accept: Boolean, text: String?) { engine.answerDialog(p.id, accept, text) }
    override fun answerFiles(p: BrowserPrompt.FileChooser, uris: List<Uri>?) { engine.answerFileChooser(p.id, uris) }
    override fun answerPermission(p: BrowserPrompt.Permission, grant: Boolean) { engine.answerPermission(p.id, grant) }
    override fun answerDownload(p: BrowserPrompt.Download, allow: Boolean) { engine.answerDownload(p.id, allow) }
    override fun webView(id: Int) = runCatching { engine.unpark(id) }.getOrNull()
    override fun park(id: Int) = engine.park(id)

    private companion object {
        // shortcut: mirrors browser.SearchEngines (not in the ui surface); use it once it is part of 10.2.
        fun searchUrl(engine: com.ouor.localchat.core.model.SearchEngine, query: String?): String {
            val q = query?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            return when (engine) {
                com.ouor.localchat.core.model.SearchEngine.DUCKDUCKGO -> if (q == null) "https://duckduckgo.com" else "https://duckduckgo.com/?q=$q"
                com.ouor.localchat.core.model.SearchEngine.GOOGLE -> if (q == null) "https://www.google.com" else "https://www.google.com/search?q=$q"
                com.ouor.localchat.core.model.SearchEngine.BING -> if (q == null) "https://www.bing.com" else "https://www.bing.com/search?q=$q"
                com.ouor.localchat.core.model.SearchEngine.NAVER -> if (q == null) "https://www.naver.com" else "https://search.naver.com/search.naver?query=$q"
            }
        }
    }
}
