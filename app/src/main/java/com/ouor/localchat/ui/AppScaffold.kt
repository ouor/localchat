package com.ouor.localchat.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import com.ouor.localchat.ui.nav.LcNavDisplay
import com.ouor.localchat.core.model.ThemeMode
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.ui.chat.ChatNav
import com.ouor.localchat.ui.chat.ChatRoute
import com.ouor.localchat.ui.chat.ChatViewModel
import com.ouor.localchat.ui.chat.ComposerViewModel
import com.ouor.localchat.ui.components.AdaptiveScaffold
import com.ouor.localchat.ui.components.NavDrawerContent
import com.ouor.localchat.ui.components.isExpandedWidth
import com.ouor.localchat.ui.markdown.JsRenderEngine
import com.ouor.localchat.ui.markdown.LocalJsRenderEngine
import com.ouor.localchat.ui.nav.ChatKey
import com.ouor.localchat.ui.nav.DataKey
import com.ouor.localchat.ui.nav.EndpointKey
import com.ouor.localchat.ui.nav.ModelKey
import com.ouor.localchat.ui.nav.ProvidersKey
import com.ouor.localchat.ui.nav.SettingsKey
import com.ouor.localchat.ui.sessions.SessionDrawer
import com.ouor.localchat.ui.sessions.SessionListViewModel
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.LocalchatTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(private val settings: SettingsStore, val renderEngine: JsRenderEngine, providers: com.ouor.localchat.provider.ProviderRepository) : ViewModel() {
    /** null until loaded; false = no provider yet (SC-01). */
    val hasProviders = providers.endpoints.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val theme = settings.settings.map { it.theme }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    /** null until loaded. */
    val onboarded = settings.settings.map { it.onboarded }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun markOnboarded() { viewModelScope.launch { settings.update { copy(onboarded = true) } } }

    init {
        // BUG-12 (P-07): once the first endpoint has models, make the composer's implicit pick the saved default.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(settings.settings, providers.allVisibleModels) { s, models ->
                if (s.defaultModel == null) models.maxByOrNull { it.lastUsedAt ?: 0L }?.ref else null
            }.collect { pick -> if (pick != null) settings.update { if (defaultModel == null) copy(defaultModel = pick) else this } }
        }
    }
}

/** Root: theme, render engine, navigation, drawer / permanent session panel (C-06, architecture 10.3). */
@Composable
fun AppScaffold(app: AppViewModel = hiltViewModel()) {
    val theme by app.theme.collectAsStateWithLifecycle()
    val dark = when (theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    LocalchatTheme(dark) {
        CompositionLocalProvider(LocalJsRenderEngine provides app.renderEngine) {
            val backStack = rememberNavBackStack(ChatKey())
            ShareRouting { backStack.showChat(null) }
            // SC-01 until finished or skipped; someone who already has a provider (upgrade, restore) never sees it.
            val hasProviders by app.hasProviders.collectAsStateWithLifecycle()
            val onboarded by app.onboarded.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(hasProviders, onboarded) {
                if (onboarded == false && hasProviders == false && backStack.lastOrNull() == ChatKey()) { backStack.clear(); backStack.add(com.ouor.localchat.ui.nav.OnboardingKey) }
            }
            BoxWithConstraints(Modifier.fillMaxSize().background(LcTheme.colors.surface)) {
                val wide = isExpandedWidth(maxWidth)
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val sessionsVm: SessionListViewModel = hiltViewModel()
                val sessions by sessionsVm.state.collectAsStateWithLifecycle()
                val currentSession = (backStack.lastOrNull() as? ChatKey)?.sessionId
                fun closeDrawer() = scope.launch { drawer.close() }
                val drawerContent: @Composable () -> Unit = {
                    NavDrawerContent(permanent = wide) {
                        SessionDrawer(
                            sessions, currentSession, sessionsVm::onQuery,
                            onOpen = { backStack.showChat(it); closeDrawer() },
                            onNewChat = { backStack.showChat(null); closeDrawer() },
                            onRename = { id, t -> sessionsVm.rename(id, t) },
                            onDelete = { id -> sessionsVm.delete(id); if (id == currentSession) backStack.showChat(null) },
                            onSettings = { backStack.add(SettingsKey); closeDrawer() },
                            onProviders = { backStack.add(ProvidersKey); closeDrawer() },
                            // P-STABLE L10: the order refreshes when the drawer opens (or, on a wide screen, the chat changes).
                            orderKey = if (wide) currentSession else drawer.targetValue,
                        )
                    }
                }
                val content: @Composable () -> Unit = {
                    AppNav(backStack, openDrawer = if (wide) null else ({ scope.launch { drawer.open() } }), onOnboarded = app::markOnboarded)
                }
                if (wide) {
                    AdaptiveScaffold(list = drawerContent, main = content)
                } else {
                    CompactDrawer(drawer, drawerContent, content)
                }
            }
        }
    }
}

/** Compact-width session drawer. FB3 QA M3: system back closes an open drawer instead of leaving the app. */
@Composable
internal fun CompactDrawer(drawer: DrawerState, drawerContent: @Composable () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(drawerContent = drawerContent, drawerState = drawer, gesturesEnabled = drawer.isOpen) { content() }
    // Composed when the drawer opens, so it is the newest back callback and wins over the screen's (browser panel).
    if (drawer.targetValue == DrawerValue.Open) BackHandler { scope.launch { drawer.close() } }
}

/**
 * I-02: MainActivity puts shared content into the new-chat draft; here we only move to the new chat.
 * Launch intent once (saveable flag, so rotation doesn't re-route), later shares via onNewIntent.
 */
@Composable
private fun ShareRouting(onShared: () -> Unit) {
    val activity = LocalContext.current as? androidx.activity.ComponentActivity ?: return
    val current by androidx.compose.runtime.rememberUpdatedState(onShared)
    var handledLaunch by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!handledLaunch && com.ouor.localchat.chat.SharedContent.from(activity.intent) != null) current()
        handledLaunch = true
    }
    androidx.compose.runtime.DisposableEffect(activity) {
        val listener = androidx.core.util.Consumer<Intent> { if (com.ouor.localchat.chat.SharedContent.from(it) != null) current() }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
}

/** Chat entries are replaced, not stacked: back from a chat leaves the app (one chat at a time, C-01). */
private fun NavBackStack<NavKey>.showChat(sessionId: String?) {
    clear()
    add(ChatKey(sessionId))
}

@Composable
private fun AppNav(backStack: NavBackStack<NavKey>, openDrawer: (() -> Unit)?, onOnboarded: () -> Unit) {
    val context = LocalContext.current
    LcNavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<ChatKey> { key ->
                val chat = hiltViewModel<ChatViewModel, ChatViewModel.Factory> { it.create(key.sessionId) }
                val composer = hiltViewModel<ComposerViewModel, ComposerViewModel.Factory> { it.create(key.sessionId) }
                ChatRoute(
                    chat, composer, hiltViewModel<com.ouor.localchat.ui.browser.BrowserViewModel, com.ouor.localchat.ui.browser.BrowserViewModel.Factory> { it.create(key.sessionId) },
                    ChatNav(
                        openDrawer = openDrawer,
                        newChat = { backStack.showChat(null) },
                        openSession = { id -> backStack[backStack.lastIndex] = ChatKey(id) },
                        closeSession = { backStack.showChat(null) },
                        addProvider = { backStack.add(EndpointKey()) },
                        editEndpoint = { backStack.add(EndpointKey(it)) },
                        modelDetail = { e, m -> backStack.add(ModelKey(e, m)) },
                    ),
                )
            }
            entry<EndpointKey> { key ->
                val vm = hiltViewModel<com.ouor.localchat.ui.providers.EndpointEditViewModel, com.ouor.localchat.ui.providers.EndpointEditViewModel.Factory> { it.create(key.endpointId, key.preset, key.custom) }
                com.ouor.localchat.ui.providers.EndpointRoute(
                    vm, onBack = { backStack.removeLastOrNull() },
                    // New provider: replace the form with its detail page.
                    onSaved = { id ->
                        when {
                            key.endpointId != null -> Unit
                            com.ouor.localchat.ui.nav.OnboardingKey in backStack -> { onOnboarded(); backStack.showChat(null) }
                            else -> { backStack.removeLastOrNull(); backStack.add(EndpointKey(id)) }
                        }
                    },
                    onModel = { e, m -> backStack.add(ModelKey(e, m)) },
                )
            }
            entry<ModelKey> { key ->
                val vm = hiltViewModel<com.ouor.localchat.ui.providers.ModelDetailViewModel, com.ouor.localchat.ui.providers.ModelDetailViewModel.Factory> { it.create(key.endpointId, key.modelId) }
                com.ouor.localchat.ui.providers.ModelDetailRoute(vm) { backStack.removeLastOrNull() }
            }
            entry<com.ouor.localchat.ui.nav.OnboardingKey> {
                com.ouor.localchat.ui.providers.OnboardingScreen(
                    onPreset = { backStack.add(EndpointKey(preset = it)) }, onCustom = { backStack.add(EndpointKey(custom = true)) },
                    onLater = { onOnboarded(); backStack.showChat(null) },
                )
            }
            entry<ProvidersKey> {
                com.ouor.localchat.ui.providers.ProvidersRoute(
                    hiltViewModel(), onBack = { backStack.removeLastOrNull() },
                    onOpen = { backStack.add(EndpointKey(it)) }, onAdd = { backStack.add(EndpointKey()) },
                )
            }
            entry<SettingsKey> {
                com.ouor.localchat.ui.settings.SettingsHomeRoute(
                    hiltViewModel(), onBack = { backStack.removeLastOrNull() },
                    onProviders = { backStack.add(ProvidersKey) }, onAgent = { backStack.add(com.ouor.localchat.ui.nav.AgentKey) },
                    onData = { backStack.add(DataKey) }, onAbout = { backStack.add(com.ouor.localchat.ui.nav.AboutKey) },
                )
            }
            entry<com.ouor.localchat.ui.nav.AgentKey> { com.ouor.localchat.ui.settings.AgentSettingsRoute(hiltViewModel()) { backStack.removeLastOrNull() } }
            entry<com.ouor.localchat.ui.nav.AboutKey> { com.ouor.localchat.ui.settings.AboutRoute { backStack.removeLastOrNull() } }
            entry<DataKey> { com.ouor.localchat.ui.settings.DataRoute(hiltViewModel(), onBack = { backStack.removeLastOrNull() }, onRestored = { backStack.showChat(null) }) }
        },
    )
}
