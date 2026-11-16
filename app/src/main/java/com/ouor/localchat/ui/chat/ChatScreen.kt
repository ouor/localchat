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
import com.ouor.localchat.ui.components.ComposerCaveat
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
import com.ouor.localchat.ui.components.ModelChipVariant
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
                is ComposerEffect.Sent -> if (chat.sessionId == null) nav.openSession(e.sessionId)
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
    LaunchedEffect(list) { androidx.compose.runtime.snapshotFlow { list.canScrollForward }.collect { if (!it) follow = true } }
    LaunchedEffect(state.turns.size) { follow = true }
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { follow && list.canScrollForward && !list.isScrollInProgress }.collect {
            if (it && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
        }
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
                    if (chat.sessionId != null) {
                        Box {
                            LcIconButton(Icons.Rounded.MoreVert, stringResource(R.string.ui_more), { menu = true })
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(stringResource(R.string.ui_rename)) }, { menu = false; renaming = state.title })
                                DropdownMenuItem({ Text(stringResource(R.string.ui_delete), color = c.dangerText) }, { menu = false; deleting = true })
                            }
                        }
                    }
                },
                scrolled = list.canScrollBackward,
            )
            if (state.saveFailing) {
                Banner(stringResource(R.string.ui_save_failed), BannerVariant.Err, Modifier.padding(horizontal = 12.dp), actions = listOf(BannerAction(stringResource(R.string.ui_retry), chat::retryWrites)))
            }
            if (cs.models.isEmpty() && !state.loading) {
                Banner(stringResource(R.string.ui_no_provider), BannerVariant.Info, Modifier.padding(horizontal = 12.dp), actions = listOf(BannerAction(stringResource(R.string.ui_add_provider), nav.addProvider)))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.turns.isEmpty() && !state.loading) {
                    Welcome(cs, { picker = true }, Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(state.turns, key = { it.user.node.id }) { turn ->
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                UserTurn(turn.user, cs, chat, state.attachments, composer::thumbnail)
                                turn.reply?.let { reply ->
                                    AssistantTurn(
                                        reply, state, cs.voice,
                                        onCopy = { text -> clipboardSet(context, text) },
                                        onRegenerate = { cs.model?.let { m -> chat.regenerate(reply.node.id, m.ref) } },
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
                                                ErrorAction.Retry -> cs.model?.let { m -> chat.regenerate(reply.node.id, m.ref) }
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
            }
            BrowserPeek(browserState, browserActions, Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp))
            ComposerArea(cs, state, composer, chat, nav, onPicker = { picker = true }, onAttach = { picks.launch(arrayOf("*/*")) },
                onMic = { mic.launch(android.Manifest.permission.RECORD_AUDIO) }, onGauge = { contextCard = true })
        }
        LcSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
        BrowserOverlay(browserState, browserActions)
    }

    // ---- sheets and dialogs ----
    if (picker) ModelPickerSheet(cs.models, cs.model?.ref, { cs.endpointNames[it].orEmpty() }, { picker = false }) { composer.selectModel(it); picker = false }
    if (contextCard) LcBottomSheet({ contextCard = false }, null) {
        ContextUsageCard(
            state.gaugePercent ?: 0,
            stringResource(R.string.ui_tokens, "%,d".format(state.usageTokens ?: 0)),
            state.contextWindow?.let { "/ " + compact(it) } ?: "",
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

@Composable
private fun Welcome(cs: ComposerUiState, onPicker: () -> Unit, modifier: Modifier) {
    val c = LcTheme.colors
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(c.surfaceRaised, com.ouor.localchat.ui.theme.LcRadius.md), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp), tint = c.accent)
        }
        Text(stringResource(R.string.ui_welcome_title), style = LcTheme.text.display, color = c.textPrimary, textAlign = TextAlign.Center)
        val m = cs.model
        if (m != null) {
            ModelChip(m.endpointName(cs), m.displayName, endpointDotColor(m.endpointName(cs)), onPicker, variant = ModelChipVariant.Hero)
            CapabilityIcons(m.caps.toolCalling.value, m.caps.imageInput.value, m.caps.reasoning.value != null, labeled = true)
        } else {
            ModelChip("", stringResource(R.string.ui_choose_model), c.textTertiary, onPicker, variant = ModelChipVariant.Hero, state = if (cs.modelMissing) ModelChipState.Missing else ModelChipState.Normal)
        }
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
) {
    val c = LcTheme.colors
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cs.imageIssue) {
            val n = cs.attachments.count { it.kind == AttachmentKind.IMAGE }
            Banner(
                stringResource(R.string.ui_images_unsupported, cs.model?.displayName.orEmpty(), n), BannerVariant.Warn,
                actions = listOf(BannerAction(stringResource(R.string.ui_send_without_images), composer::sendWithoutImages), BannerAction(stringResource(R.string.ui_change_model), onPicker)),
                stackedActions = true,
            )
        }
        val voice = cs.voice
        val mode = when {
            cs.running -> ComposerMode.Running
            voice is VoiceState.Listening -> ComposerMode.Voice
            else -> ComposerMode.Idle
        }
        Column {
            ComposerPanel(
                cs.text, composer::onText, composer::send, { chat.stop() }, onAttach, onMic,
                mode = mode, canSend = cs.canSend,
                attachments = if (cs.attachments.isEmpty()) null else ({
                    cs.attachments.forEach { a ->
                        AttachmentTile(
                            a.name.ifEmpty { "…" },
                            if (a.kind == AttachmentKind.IMAGE) TileKind.Image else TileKind.Document,
                            when (a.state) { PrepState.Preparing -> TileState.Preparing; is PrepState.Failed -> TileState.Failed; PrepState.Ready -> if (a.hasOriginal) TileState.Ready else TileState.Missing },
                            thumbnail = composer.thumbnail(a),
                            onRemove = { composer.remove(a.id) },
                            onRetry = { composer.retry(a.id) },
                        )
                    }
                }),
                voice = if (voice is VoiceState.Listening) ({
                    VoiceRecorderRow(
                        "%d:%02d".format(voice.elapsedMs / 60000, voice.elapsedMs / 1000 % 60),
                        rmsLevels(voice.rmsDb), stringResource(R.string.ui_voice_input), composer::cancelVoice, composer::stopVoice,
                    )
                }) else null,
            ) {
                Toggles(cs, composer, nav)
            }
            ComposerRail(
                start = start@{
                    if (state.turns.isEmpty()) return@start // SC-04: the welcome hero chip is the only model chip
                    val m = cs.model
                    if (m != null) ModelChip(m.endpointName(cs), m.displayName, endpointDotColor(m.endpointName(cs)), onPicker)
                    else ModelChip("", stringResource(R.string.ui_choose_model), c.textTertiary, onPicker, state = if (cs.modelMissing) ModelChipState.Missing else ModelChipState.Normal)
                },
                end = {
                    val failed = cs.attachments.count { it.state is PrepState.Failed }
                    when {
                        failed > 0 -> Text(stringResource(R.string.ui_files_failed, failed), style = LcTheme.text.caption, color = c.dangerText)
                        cs.waitingForFiles -> Text(stringResource(R.string.ui_files_waiting), style = LcTheme.text.caption, color = c.textTertiary)
                    }
                    (state.runPhase as? RunPhase.Retrying)?.let { RetryStatus(it.attempt, it.max) }
                    (state.runPhase as? RunPhase.RunningTool)?.takeIf { it.toolName == "read_link" }?.let {
                        Text(stringResource(R.string.ui_reading_link, siteOf(it.callId)), style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1)
                    }
                    if (chat.sessionId != null) ContextGauge(state.gaugePercent, onGauge, tokensText = state.usageTokens?.let { stringResource(R.string.ui_tokens, compact(it)) })
                },
            )
        }
        ComposerCaveat()
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
