package com.ouor.localchat.ui.chat

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ouor.localchat.R
import com.ouor.localchat.chat.ChatRejected
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.attach.PrepFailure
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.speech.VoiceState
import com.ouor.localchat.ui.components.AttachmentTile
import com.ouor.localchat.ui.components.Banner
import com.ouor.localchat.ui.components.BannerAction
import com.ouor.localchat.ui.components.BannerVariant
import com.ouor.localchat.ui.components.ApprovalContent
import com.ouor.localchat.ui.components.CapabilityIcons
import com.ouor.localchat.ui.components.ComposerMode
import com.ouor.localchat.ui.components.ComposerPanel
import com.ouor.localchat.ui.components.ComposerRail
import com.ouor.localchat.ui.components.ContextGauge
import com.ouor.localchat.ui.components.ContextUsageCard
import com.ouor.localchat.ui.components.DividerLabel
import com.ouor.localchat.ui.components.ErrorAction
import com.ouor.localchat.ui.components.IconButtonVariant
import com.ouor.localchat.ui.components.LcBottomSheet
import com.ouor.localchat.ui.components.LcDialog
import com.ouor.localchat.ui.components.LcIconButton
import com.ouor.localchat.ui.components.LcSnackbarHost
import com.ouor.localchat.ui.components.LcTextField
import com.ouor.localchat.ui.components.LcTopBar
import com.ouor.localchat.ui.components.DialogKind
import com.ouor.localchat.ui.components.ModelChip
import com.ouor.localchat.ui.components.ModelChipState
import com.ouor.localchat.ui.components.RetryStatus
import com.ouor.localchat.ui.components.ScrollToBottomButton
import com.ouor.localchat.ui.components.TileKind
import com.ouor.localchat.ui.components.TileState
import com.ouor.localchat.ui.components.ToggleChip
import com.ouor.localchat.ui.components.ToggleKind
import com.ouor.localchat.ui.components.VoiceRecorderRow
import com.ouor.localchat.ui.components.reasoningChipLabel
import com.ouor.localchat.ui.components.toolVisual
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.endpointDotColor
import kotlinx.coroutines.launch
import com.ouor.localchat.ui.browser.BrowserActions
import com.ouor.localchat.ui.browser.BrowserOverlay
import com.ouor.localchat.ui.browser.BrowserPeek
import com.ouor.localchat.ui.browser.BrowserUiState
import com.ouor.localchat.ui.components.IconButtonSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow

/** What the chat needs from the app shell (navigation lives in AppScaffold). */
class ChatNav(
    val openDrawer: (() -> Unit)?,
    val newChat: () -> Unit,
    val openSession: (String) -> Unit,
    val closeSession: () -> Unit,
    val addProvider: () -> Unit,
    val editEndpoint: (String) -> Unit,
    val modelDetail: (String, String) -> Unit,
)

@Composable
fun ChatRoute(chat: ChatViewModel, composer: ComposerViewModel, browser: com.ouor.localchat.ui.browser.BrowserViewModel, nav: ChatNav) {
    val state by chat.state.collectAsStateWithLifecycle()
    val cs by composer.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    fun rejectText(r: ChatRejected.Reason) = context.getString(
        when (r) {
            ChatRejected.Reason.RUN_ACTIVE -> R.string.ui_reject_run_active
            ChatRejected.Reason.MODEL_UNAVAILABLE -> R.string.ui_reject_model
            else -> R.string.ui_reject_not_allowed
        },
    )
    LaunchedEffect(chat) {
        chat.effects.collect { e ->
            when (e) {
                is ChatEffect.Rejected -> snackbar.showSnackbar(rejectText(e.reason))
                ChatEffect.Deleted -> nav.closeSession()
                ChatEffect.CompactFailed -> snackbar.showSnackbar(context.getString(R.string.ui_compact_failed))
                ChatEffect.NothingToCompact -> snackbar.showSnackbar(context.getString(R.string.ui_compact_nothing))
                ChatEffect.ReadAloudUnavailable -> snackbar.showSnackbar(context.getString(R.string.ui_read_aloud_unavailable))
                is ChatEffect.FileSaved -> snackbar.showSnackbar(context.getString(R.string.ui_file_saved, e.name))
                ChatEffect.FileSaveFailed -> snackbar.showSnackbar(context.getString(R.string.ui_file_save_failed))
            }
        }
    }
    LaunchedEffect(composer) {
        composer.effects.collect { e ->
            when (e) {
                is ComposerEffect.Sent -> if (chat.sessionId == null) { NewChatOptions.carry(e.sessionId); nav.openSession(e.sessionId) }
                is ComposerEffect.Rejected -> snackbar.showSnackbar(rejectText(e.reason))
            }
        }
    }
    val bs by browser.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.runPhase) { browser.onRunPhase(state.runPhase) }
    ChatScreen(state, cs, bs, chat, composer, browser, nav, snackbar)
}

/** Pure screen: state in, callbacks out (architecture 11). Compose tests drive it with fixed states. */
@Composable
internal fun ChatScreen(
    state: ChatUiState,
    cs: ComposerUiState,
    browserState: BrowserUiState,
    chat: ChatActions,
    composer: ComposerActions,
    browserActions: BrowserActions,
    nav: ChatNav,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val c = LcTheme.colors
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var contextCard by remember { mutableStateOf(false) }
    var sourcesOf by remember { mutableStateOf<AssistantUi?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember { androidx.compose.runtime.derivedStateOf { !list.canScrollForward } }
    // M-10 / BUG-03: follow the bottom while content grows; a user drag stops following, reaching the bottom resumes it.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { i ->
            when (i) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> follow = false
                is androidx.compose.foundation.interaction.DragInteraction.Stop, is androidx.compose.foundation.interaction.DragInteraction.Cancel -> if (!list.canScrollForward) follow = true
            }
        }
    }
    // P-STABLE H3, M3: any touch on the list stops following, streaming or not, so a block the user expands or a link they
    // aim at stays under the finger. Reaching the bottom (scroll, FAB), a new message or a new run at the bottom re-arms it.
    // Layout-state flows can emit while a measure pass runs; scrolling then forces a nested remeasure (crash), so wait for
    // the next frame's animation phase, before layout, and check again.
    suspend fun toBottom() {
        androidx.compose.runtime.withFrameNanos { }
        if (list.canScrollForward && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
    }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(list) { androidx.compose.runtime.snapshotFlow { list.canScrollForward }.collect { if (!it) { follow = true; touched = false } } }
    LaunchedEffect(state.turns.size) { follow = true; touched = false }
    LaunchedEffect(state.running) { if (state.running && !list.canScrollForward) touched = false }
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { follow && !touched && list.canScrollForward && !list.isScrollInProgress }.collect { if (it) toBottom() }
    }
    // The list itself shrinking (options, a caption, the keyboard) keeps a followed bottom in view.
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.viewportSize.height }.collect { if (follow && list.canScrollForward) toBottom() }
    }

    val context = LocalContext.current
    // BUG-17: tool artifacts (PDF) open in another app, share, or save through SAF.
    var saving by remember { mutableStateOf<com.ouor.localchat.core.model.Attachment?>(null) }
    val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val a = saving
        saving = null
        if (uri != null && a != null) chat.saveFile(a, uri)
    }
    fun openFile(a: com.ouor.localchat.core.model.Attachment, share: Boolean) {
        val uri = chat.fileUri(a) ?: run { scope.launch { snackbar.showSnackbar(context.getString(R.string.ui_file_missing)) }; return }
        val intent = if (share) {
            android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType(a.mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
        } else {
            android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, a.mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.ui_no_app_to_open)) }
        }
    }
    // FB-1b: options open by default only in an empty chat; a user toggle sticks for this chat, including across the first
    // send (null → id), which may also move the chat to its own nav entry (see NewChatOptions). Another chat resets it.
    val carried = remember { NewChatOptions.take(chat.sessionId) }
    // P-STABLE L11: a new chat starts open as an explicit state, and hands it to its first send, so sending never folds them.
    var userOpen by rememberSaveable { mutableStateOf(carried?.open ?: if (chat.sessionId == null) true else null) }
    if (chat.sessionId == null) androidx.compose.runtime.SideEffect { NewChatOptions.pending = userOpen }
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (carried?.focus == true) LaunchedEffect(Unit) { inputFocus.requestFocus() }
    var openFor by rememberSaveable { mutableStateOf(chat.sessionId) }
    if (openFor != chat.sessionId) {
        if (openFor != null) userOpen = null
        openFor = chat.sessionId
    }
    val optionsOpen = userOpen ?: (state.turns.isEmpty() && !state.loading)

    // FB-1c: at Half the browser panel covers the screen bottom; pad the list so its last line can scroll above the panel.
    // The panel reports only settled heights (not Full), so the list never relayouts while the panel moves.
    val density = LocalDensity.current
    var bottomPx by remember { mutableIntStateOf(0) }
    var cover by remember { mutableIntStateOf(0) }
    val panelPad = with(density) { (cover - bottomPx).coerceAtLeast(0).toDp() }

    val picks = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { composer.attach(it) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) composer.startVoice() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            LcTopBar(
                title = state.title.ifEmpty { if (chat.sessionId == null) "" else stringResource(R.string.ui_new_chat) },
                navigationIcon = { nav.openDrawer?.let { LcIconButton(Icons.Rounded.Menu, stringResource(R.string.ui_menu), it) } },
                actions = {
                    LcIconButton(Icons.Outlined.Web, stringResource(R.string.ui_browser), { browserActions.open(null) })
                    LcIconButton(Icons.Outlined.EditNote, stringResource(R.string.ui_new_chat), nav.newChat, enabled = chat.sessionId != null)
                    // P-STABLE H1: ⋮ is always there (disabled in a new chat) so 🗔 and ✎ keep their slots after the first send.
                    Box {
                        LcIconButton(Icons.Rounded.MoreVert, stringResource(R.string.ui_more), { menu = true }, enabled = chat.sessionId != null)
                        DropdownMenu(menu && chat.sessionId != null, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.ui_rename)) }, { menu = false; renaming = state.title })
                            DropdownMenuItem({ Text(stringResource(R.string.ui_delete), color = c.dangerText) }, { menu = false; deleting = true })
                        }
                    }
                },
                scrolled = list.canScrollBackward,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.turns.isEmpty() && !state.loading) {
                    Welcome(cs, Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); touched = true }
                    }, list, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + panelPad), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(state.turns, key = { it.user.node.id }) { turn ->
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                UserTurn(turn.user, cs, chat, state.attachments, composer::thumbnail)
                                turn.reply?.let { reply ->
                                    AssistantTurn(
                                        reply, state, cs.voice,
                                        onCopy = { text -> clipboardSet(context, text) },
                                        onRegenerate = { cs.model?.let { m -> chat.regenerate(reply.node.id, m.ref, cs.options) } },
                                        onContinue = { chat.continueReply(reply.node.id) },
                                        onVersion = chat::selectVersion,
                                        onSources = { sourcesOf = reply },
                                        onLink = { browserActions.open(it) },
                                        toolStep = chat::toolStep,
                                        onReadAloud = { chat.readAloud(reply.node.id, it) },
                                        thumbnail = composer::thumbnail,
                                        onOpenFile = { a -> openFile(a, false) },
                                        onShareFile = { a -> openFile(a, true) },
                                        onSaveFile = { a -> saving = a; saveDoc.launch(a.name) },
                                        onErrorAction = { a ->
                                            when (a) {
                                                ErrorAction.Retry -> cs.model?.let { m -> chat.regenerate(reply.node.id, m.ref, cs.options) }
                                                ErrorAction.ChangeKey -> reply.body.model?.let { nav.editEndpoint(it.endpointId) }
                                                ErrorAction.TopUp -> reply.body.model?.let { nav.editEndpoint(it.endpointId) }
                                                ErrorAction.OpenModelSettings -> reply.body.model?.let { nav.modelDetail(it.endpointId, it.modelId) }
                                                ErrorAction.SummarizeNow -> chat.compactNow()
                                                ErrorAction.ChooseModel -> { picker = true }
                                                ErrorAction.OpenProvider -> reply.body.model?.let { nav.editEndpoint(it.endpointId) }
                                            }
                                        },
                                    )
                                }
                                if (turn.reply != null && turn.reply.node.id == state.summaryAfterId) DividerLabel(stringResource(R.string.ui_summary_divider))
                            }
                        }
                    }
                    if (!atBottom) ScrollToBottomButton(
                        { scope.launch { list.animateScrollToItem(state.turns.lastIndex.coerceAtLeast(0), Int.MAX_VALUE) } },
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                        hasNew = state.running,
                    )
                }
                // P-STABLE L1: banners float over the top of the list / welcome instead of pushing it down.
                Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.saveFailing) {
                        Banner(stringResource(R.string.ui_save_failed), BannerVariant.Err, actions = listOf(BannerAction(stringResource(R.string.ui_retry), chat::retryWrites)))
                    }
                    // modelsLoaded: no "no provider" flash before the model list first arrives.
                    if (cs.models.isEmpty() && cs.modelsLoaded && !state.loading) {
                        Banner(stringResource(R.string.ui_no_provider), BannerVariant.Info, actions = listOf(BannerAction(stringResource(R.string.ui_add_provider), nav.addProvider)))
                    }
                }
            }
            // Status strip + composer: a sibling below the list, so they never cover it (FB-1c).
            Column(Modifier.onSizeChanged { bottomPx = it.height }) {
                // 8dp gap to the composer (screens-fb1 1b ②).
                BrowserPeek(browserState, browserActions, Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp))
                ComposerArea(cs, state, composer, chat, nav, onPicker = { picker = true }, onAttach = { picks.launch(arrayOf("*/*")) },
                    onMic = { mic.launch(android.Manifest.permission.RECORD_AUDIO) }, onGauge = { contextCard = true },
                    open = optionsOpen, onToggleOpen = { userOpen = !optionsOpen; if (chat.sessionId == null) NewChatOptions.pending = userOpen },
                    inputModifier = Modifier.focusRequester(inputFocus).onFocusChanged { if (chat.sessionId == null) NewChatOptions.focused = it.hasFocus })
            }
        }
        // P-STABLE L3: snackbars sit above the "scroll to bottom" button's slot (8dp + 40dp + 8dp), never over it.
        LcSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = with(density) { bottomPx.toDp() } + 56.dp))
        BrowserOverlay(browserState, browserActions) { cover = it }
    }

    // ---- sheets and dialogs ----
    if (picker) ModelPickerSheet(cs.models, cs.model?.ref, { cs.endpointNames[it].orEmpty() }, { picker = false }, endpointIds = cs.endpointNames.keys.toList()) { composer.selectModel(it); picker = false }
    if (contextCard) LcBottomSheet({ contextCard = false }, null) {
        ContextUsageCard(
            state.gaugePercent ?: 0,
            stringResource(R.string.ui_tokens, "%,d".format(state.usageTokens ?: 0)),
            state.contextWindow?.let { "/ " + com.ouor.localchat.ui.providers.limitLabel(it, com.ouor.localchat.ui.providers.CONTEXT_PRESETS) } ?: "", // same "128K" as the limit menu
            stringResource(R.string.ui_context_note),
            if (chat.sessionId != null && !state.running) ({ contextCard = false; chat.compactNow() }) else null,
        )
    }
    sourcesOf?.let { reply -> SourcesSheet(sources(reply.body), { sourcesOf = null; browserActions.open(it) }) { sourcesOf = null } }
    renaming?.let { current ->
        var t by remember { mutableStateOf(current) }
        LcDialog(stringResource(R.string.ui_rename), { renaming = null }, stringResource(R.string.ui_save), { chat.rename(t.trim()); renaming = null }, confirmEnabled = t.isNotBlank()) {
            LcTextField(t, { t = it })
        }
    }
    if (deleting) LcDialog(
        stringResource(R.string.ui_delete_chat_title), { deleting = false }, stringResource(R.string.ui_delete), { deleting = false; chat.delete() },
        text = stringResource(R.string.ui_delete_chat_body), kind = DialogKind.Destructive,
    )
    when (val p = state.pause) {
        // Locked sheet: swipe and outside taps do nothing; back = deny (SC-13).
        is Pause.Approval -> LcBottomSheet({ chat.resolve(Resolution.ApprovalAnswer(p.id, false)) }, null, locked = true) {
            val r = p.request
            ApprovalContent(
                toolVisual(r.toolName).icon,
                stringResource(R.string.ui_approval_title, toolLabel(r.toolName)),
                r.targetText ?: stringResource(R.string.ui_approval_body),
                listOfNotNull(r.tabId?.let { stringResource(R.string.ui_tab_n, it) }, r.url).joinToString(" · "),
                onDeny = { chat.resolve(Resolution.ApprovalAnswer(p.id, false)) },
                onAllow = { chat.resolve(Resolution.ApprovalAnswer(p.id, true)) },
                code = r.code,
                preview = r.screenshotId?.let(state.attachments::get)?.let(composer::thumbnail),
            )
        }
        is Pause.StepLimit -> LcDialog(
            // BUG-18: "중지" and back end the run (A-02).
            stringResource(R.string.ui_step_limit_title, p.reached), { chat.resolve(Resolution.StepLimitAnswer(false)) }, stringResource(R.string.ui_continue),
            { chat.resolve(Resolution.StepLimitAnswer(true)) }, text = stringResource(R.string.ui_step_limit_body),
            dismissLabel = stringResource(R.string.ui_stop),
        )
        else -> Unit
    }
}

/**
 * FB-1b: the options choice made in a new chat ([pending]) outlives the first send, which opens the chat in a new nav entry.
 * Process memory only: a lost choice falls back to the default, nothing else depends on it.
 */
internal object NewChatOptions {
    var pending: Boolean? = null
    /** P-STABLE H6: the new chat's input had focus, so the opened chat takes it back and the keyboard stays up. */
    var focused = false
    data class Carry(val open: Boolean?, val focus: Boolean)
    private val carried = mutableMapOf<String, Carry>()
    fun carry(sessionId: String) { carried[sessionId] = Carry(pending, focused); pending = null; focused = false }
    /** Initial state for a screen: a fresh new chat clears what is pending; an opened chat takes what its first send carried. */
    fun take(sessionId: String?): Carry? = if (sessionId == null) { pending = null; focused = false; null } else carried.remove(sessionId)
}

/** Greeting + the model's capability icons; the model chip lives only under the composer (FB-1, lead decision 1). */
@Composable
private fun Welcome(cs: ComposerUiState, modifier: Modifier) {
    val c = LcTheme.colors
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(c.surfaceRaised, com.ouor.localchat.ui.theme.LcRadius.md), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp), tint = c.accent)
        }
        Text(stringResource(R.string.ui_welcome_title), style = LcTheme.text.display, color = c.textPrimary, textAlign = TextAlign.Center)
        cs.model?.let { m -> CapabilityIcons(m.caps.toolCalling.value, m.caps.imageInput.value, m.caps.reasoning.value != null, labeled = true) }
    }
}

@Composable
private fun ComposerArea(
    cs: ComposerUiState,
    state: ChatUiState,
    composer: ComposerActions,
    chat: ChatActions,
    nav: ChatNav,
    onPicker: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onGauge: () -> Unit,
    open: Boolean,
    onToggleOpen: () -> Unit,
    inputModifier: Modifier,
) {
    val c = LcTheme.colors
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cs.imageIssue) {
            val n = cs.attachments.count { it.kind == AttachmentKind.IMAGE }
            Banner(
                stringResource(R.string.ui_images_unsupported, cs.model?.displayName.orEmpty(), n), BannerVariant.Warn,
                actions = listOf(BannerAction(stringResource(R.string.ui_send_without_images), composer::sendWithoutImages), BannerAction(stringResource(R.string.ui_change_model), onPicker)),
                stackedActions = true,
            )
        }
        // Order, top to bottom: status captions, options (model row, toggle row), input. Everything that comes and goes sits
        // above the input, so a caption or opening the options never moves the input or its buttons (P-STABLE H2; the list shrinks).
        val failed = cs.attachments.count { it.state is PrepState.Failed }
        val unsupported = cs.attachments.firstOrNull(::isUnsupported)
        val retry = state.runPhase as? RunPhase.Retrying
        val reading = (state.runPhase as? RunPhase.RunningTool)?.takeIf { it.toolName == "read_link" }
        if (failed > 0 || cs.waitingForFiles || retry != null || reading != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                when {
                    unsupported != null -> Text(stringResource(if ((unsupported.state as PrepState.Failed).reason == PrepFailure.TOO_LARGE) R.string.fb3_d_file_too_large else R.string.fb2_q_file_unsupported, unsupported.name), style = LcTheme.text.caption, color = c.dangerText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    failed > 0 -> Text(stringResource(R.string.ui_files_failed, failed), style = LcTheme.text.caption, color = c.dangerText)
                    cs.waitingForFiles -> Text(stringResource(R.string.ui_files_waiting), style = LcTheme.text.caption, color = c.textTertiary)
                }
                retry?.let { RetryStatus(it.attempt, it.max) }
                reading?.let { Text(stringResource(R.string.ui_reading_link, siteOf(it.callId)), style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        val reduced = com.ouor.localchat.ui.components.reducedMotion()
        AnimatedVisibility(
            open,
            enter = if (reduced) EnterTransition.None else expandVertically(tween(200), expandFrom = Alignment.Bottom) + fadeIn(tween(200)),
            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(120), shrinkTowards = Alignment.Bottom) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ComposerRail(
                    start = {
                        val m = cs.model
                        if (m != null) ModelChip(m.endpointName(cs), m.displayName, endpointDotColor(m.endpointName(cs)), onPicker, Modifier.weight(1f, fill = false))
                        else ModelChip("", stringResource(R.string.ui_choose_model), c.textTertiary, onPicker, Modifier.weight(1f, fill = false), state = if (cs.modelMissing) ModelChipState.Missing else ModelChipState.Normal)
                    },
                    // P-STABLE L11: the gauge holds its place in a new chat too (disabled), so the model chip keeps its width.
                    end = { ContextGauge(state.gaugePercent, onGauge, tokensText = state.usageTokens?.let { stringResource(R.string.ui_tokens, compact(it)) }, enabled = chat.sessionId != null) },
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Toggles(cs, composer, nav)
                }
            }
        }
        val voice = cs.voice
        val mode = when {
            cs.running -> ComposerMode.Running
            voice is VoiceState.Listening -> ComposerMode.Voice
            else -> ComposerMode.Idle
        }
        ComposerPanel(
            cs.text, composer::onText, composer::send, { chat.stop() }, onAttach, onMic,
            modifier = inputModifier, mode = mode, canSend = cs.canSend,
            attachments = if (cs.attachments.isEmpty()) null else ({
                cs.attachments.forEach { a ->
                    AttachmentTile(
                        a.name.ifEmpty { "…" },
                        if (a.kind == AttachmentKind.IMAGE) TileKind.Image else TileKind.Document,
                        when (a.state) { PrepState.Preparing -> TileState.Preparing; is PrepState.Failed -> TileState.Failed; PrepState.Ready -> if (a.hasOriginal) TileState.Ready else TileState.Missing },
                        thumbnail = composer.thumbnail(a),
                        onRemove = { composer.remove(a.id) },
                        onRetry = if (isUnsupported(a)) null else ({ composer.retry(a.id) }),
                    )
                }
            }),
            voice = if (voice is VoiceState.Listening) ({
                VoiceRecorderRow(
                    "%d:%02d".format(voice.elapsedMs / 60000, voice.elapsedMs / 1000 % 60),
                    rmsLevels(voice.rmsDb), stringResource(R.string.ui_voice_input),
                )
            }) else null,
            onVoiceCancel = composer::cancelVoice, onVoiceDone = composer::stopVoice,
        ) {
            // FB-1b: the bar is [+][options] field [mic|send]; the options button never takes focus so the keyboard stays.
            // LcIconButton's modifier sits outside its clickable node, so this one node carries label, state and click.
            val stateDesc = stringResource(if (open) R.string.fb_e3_group_expanded else R.string.fb_e3_group_collapsed)
            val label = stringResource(if (open) R.string.fb_e1_options_hide else R.string.fb_e1_options_show)
            LcIconButton(
                Icons.Rounded.Tune, label, onToggleOpen,
                Modifier.focusProperties { canFocus = false }.clearAndSetSemantics {
                    contentDescription = label; stateDescription = stateDesc; role = Role.Button
                    onClick { onToggleOpen(); true }
                },
                variant = if (open) IconButtonVariant.Tonal else IconButtonVariant.Ghost, size = IconButtonSize.Sm,
            )
        }
    }
}

@Composable
private fun Toggles(cs: ComposerUiState, composer: ComposerActions, nav: ChatNav) {
    val noTools = stringResource(R.string.ui_reason_no_tools)
    val settings = stringResource(R.string.ui_model_settings)
    val open = { cs.model?.let { nav.modelDetail(it.endpointId, it.modelId) } ?: Unit }
    var effortMenu by remember { mutableStateOf(false) }
    Box {
        ToggleChip(
            ToggleKind.Reasoning,
            if (cs.reasoning.on) reasoningChipLabel(cs.effort, stringResource(R.string.ui_reasoning)) else stringResource(R.string.ui_reasoning),
            cs.reasoning.on,
            { if (cs.efforts.isNotEmpty()) effortMenu = true else composer.toggleReasoning() },
            enabled = cs.reasoning.enabled || cs.reasoning.locked, locked = cs.reasoning.locked,
            disabledReason = stringResource(R.string.ui_reason_no_reasoning), reasonActionLabel = settings, onReasonAction = open,
        )
        // M-08: effort levels come from the server (supported_levels); "끔" first.
        com.ouor.localchat.ui.components.LcSelectMenu(
            effortMenu, { effortMenu = false },
            listOf(com.ouor.localchat.ui.components.MenuOption(stringResource(R.string.ui_off))) +
                cs.efforts.map { com.ouor.localchat.ui.components.MenuOption(it, if (it == cs.model?.caps?.reasoning?.value?.defaultEffort) stringResource(R.string.ui_default) else null) },
            if (!cs.reasoning.on) 0 else cs.efforts.indexOf(cs.effort) + 1,
            { i -> effortMenu = false; composer.setEffort(if (i == 0) null else cs.efforts[i - 1]) },
        )
    }
    ToggleChip(ToggleKind.Search, stringResource(R.string.ui_search), cs.search.on, composer::toggleSearch, enabled = cs.search.enabled,
        disabledReason = noTools, reasonActionLabel = settings, onReasonAction = open)
    ToggleChip(ToggleKind.Browser, stringResource(R.string.ui_browser), cs.browser.on, composer::toggleBrowser, enabled = cs.browser.enabled,
        disabledReason = noTools, reasonActionLabel = settings, onReasonAction = open)
}

private fun ModelInfo.endpointName(cs: ComposerUiState): String = cs.endpointNames[endpointId] ?: ""

/** 851234 → "851.2k". */
internal fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(java.util.Locale.ROOT, n / 1_000_000f).replace(".0M", "M")
    n >= 1_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1_000f).replace(".0k", "k")
    else -> n.toString()
}

/** Retrying cannot fix an unsupported type, an old Office format or a file over 64 MB, so its tile offers no retry (FB2 QA Minor-2). */
internal fun isUnsupported(a: Attachment) = (a.state as? PrepState.Failed)?.reason in FINAL_FAILURES

private val FINAL_FAILURES = setOf(PrepFailure.UNSUPPORTED_TYPE, PrepFailure.LEGACY_OFFICE, PrepFailure.TOO_LARGE)

private fun siteOf(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

/** 16 bars from the latest RMS (dB ~ -2..10). */
private fun rmsLevels(db: Float): List<Float> {
    val base = ((db + 2f) / 12f).coerceIn(0.1f, 1f)
    return List(16) { i -> (base * (0.55f + 0.45f * kotlin.math.sin(i * 1.3f + db).let { kotlin.math.abs(it) })).coerceIn(0.1f, 1f) }
}

private fun clipboardSet(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("message", text))
}
