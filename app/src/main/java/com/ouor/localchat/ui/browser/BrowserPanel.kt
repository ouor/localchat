package com.ouor.localchat.ui.browser

import com.ouor.localchat.ui.components.reserved
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import kotlin.math.abs
import com.ouor.localchat.ui.components.reducedMotion
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserPrompt
import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.DialogKind
import com.ouor.localchat.browser.Highlight
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.ui.components.BrowserOwner
import com.ouor.localchat.ui.components.BrowserPanelFrame
import com.ouor.localchat.ui.components.BrowserStatusStrip
import com.ouor.localchat.ui.components.BrowserToolbar
import com.ouor.localchat.ui.components.ElementHighlightLayer
import com.ouor.localchat.ui.components.HandoffBanner
import com.ouor.localchat.ui.components.LcBottomSheet
import com.ouor.localchat.ui.components.LcDialog
import com.ouor.localchat.ui.components.LcIconButton
import com.ouor.localchat.ui.components.LcSnackbar
import com.ouor.localchat.ui.components.LcTextField
import com.ouor.localchat.ui.components.OwnershipBar
import com.ouor.localchat.ui.components.TabCard
import com.ouor.localchat.ui.components.TabGrid
import com.ouor.localchat.ui.components.TabOwner
import com.ouor.localchat.ui.theme.LcTheme

/** Callbacks of the browser panel; [BrowserViewModel] implements all of them (pure-state screen, architecture 11). */
interface BrowserActions {
    fun setPanel(p: PanelState)
    fun select(id: Int)
    fun showTabs(v: Boolean)
    fun close(id: Int)
    fun open(url: String?)
    fun newTab(url: String?)
    fun navigate(input: String)
    fun back()
    fun forward()
    fun reload()
    fun takeOver(id: Int)
    fun dismissTouch()
    fun giveBack(id: Int)
    fun handToAi(id: Int)
    fun takeTab(id: Int)
    fun stopRun()
    fun answerDialog(p: BrowserPrompt.Dialog, accept: Boolean, text: String?)
    fun answerFiles(p: BrowserPrompt.FileChooser, uris: List<android.net.Uri>?)
    fun answerPermission(p: BrowserPrompt.Permission, grant: Boolean)
    fun answerDownload(p: BrowserPrompt.Download, allow: Boolean)
    /** Engine-owned WebView for [BrowserSurface]; null in tests (a blank page is drawn). */
    fun webView(id: Int): android.webkit.WebView?
    fun park(id: Int)
}

private fun TabInfo.ownerUi(s: BrowserUiState) = when {
    owner is Owner.User -> BrowserOwner.Me
    control == Control.USER -> BrowserOwner.Waiting
    else -> BrowserOwner.Ai
}

private fun TabInfo.tabOwner(s: BrowserUiState) = when {
    owner is Owner.User -> TabOwner.Me
    s.isMine(this) -> TabOwner.Ai
    else -> TabOwner.OtherChat
}

private fun TabInfo.shortUrl() = url.substringAfter("://").removePrefix("www.")

private fun TabInfo.host() = url.substringAfter("://").substringBefore('/').removePrefix("www.")

/** FB-1c: the tab the Peek strip shows — only while this chat's run operates it (user-tab Peek shows nothing, lead decision). */
internal val BrowserUiState.stripTab: TabInfo?
    get() = current?.takeIf { panel == PanelState.Peek && running && isMine(it) && it.control == Control.AI }

/** The panel's Peek state above the composer (FB-1c): a one-line AI status strip. */
@Composable
fun BrowserPeek(s: BrowserUiState, actions: BrowserActions, modifier: Modifier = Modifier) {
    val t = s.stripTab ?: return
    BrowserStatusStrip(
        t.title.ifEmpty { t.host() }, t.host(), onOpen = { actions.setPanel(PanelState.Half) },
        onTakeOver = { actions.takeOver(t.id); actions.setPanel(PanelState.Half) }, modifier = modifier,
    )
}

@Composable
private fun Thumbnail(t: TabInfo) {
    val bmp = t.thumbnail ?: return
    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
}

private val PanelSnap = spring<Float>(dampingRatio = 1f, stiffness = 400f)

/** One step up / down the drag anchors (FB-5 handle accessibility actions, back = down). */
internal fun PanelState.up() = if (this >= PanelState.Half) PanelState.Full else PanelState.Half
internal fun PanelState.down() = if (this == PanelState.Full) PanelState.Half else PanelState.Peek

private fun panelAnchors(h: Float) = DraggableAnchors { PanelState.Full at 0f; PanelState.Half at h * 0.45f; PanelState.Peek at h }

/**
 * Half / full panel overlay plus the tab list, prompts and the B-09 ③ snackbar. Place in a full-size Box.
 * FB-5: the handle + toolbar drag the panel between Peek (gone), Half (55%) and Full. [onCoverHeight] gets the px of the
 * screen bottom the panel covers once it settles at Peek (0) or Half; Full is not reported (the chat is hidden anyway).
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun BrowserOverlay(s: BrowserUiState, actions: BrowserActions, onCoverHeight: (Int) -> Unit = {}) {
    val t = s.current
    val cover by rememberUpdatedState(onCoverHeight)
    if (t != null) BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val h = constraints.maxHeight.toFloat()
        val target = maxOf(s.panel, PanelState.Peek)
        val panelNow by rememberUpdatedState(target)
        val snapSpec = if (reducedMotion()) snap() else PanelSnap
        // Starts where the panel already is, so a rotation keeps Half/Full instead of sliding up from Peek again.
        val state = remember { AnchoredDraggableState(target, panelAnchors(h)) }
        // BoxWithConstraints composes its content during layout, so a new height reaches the anchors in the same frame.
        val anchors = remember(h) { panelAnchors(h) }
        if (state.anchors != anchors) state.updateAnchors(anchors)
        LaunchedEffect(target) { state.animateTo(target, snapSpec) }
        // A drag that settles on another anchor becomes the panel state.
        LaunchedEffect(state) { snapshotFlow { state.settledValue }.drop(1).collect { if (it != panelNow) actions.setPanel(it) } }
        LaunchedEffect(state, h) {
            snapshotFlow { state.settledValue }.collect { if (it != PanelState.Full) cover((h - state.anchors.positionOf(it)).roundToInt().coerceAtLeast(0)) }
        }
        DisposableEffect(Unit) { onDispose { cover(0) } }
        BackHandler(s.panel >= PanelState.Half && !com.ouor.localchat.ui.LocalDrawerOpen.current) { actions.setPanel(s.panel.down()) }

        // Stay composed until a drag to the bottom settles; removing the drag node mid-gesture would cancel the settle.
        val shown by remember(h) { derivedStateOf { state.offset < h - 0.5f || state.settledValue != PanelState.Peek } }
        // Resizing the WebView every frame janks: while the panel moves it keeps full height and only slides.
        // "Settled" allows 1px so an offset an ulp off its anchor never leaves the WebView taller than the screen.
        val top by remember { derivedStateOf { state.anchors.positionOf(state.settledValue).takeIf { abs(state.offset - it) < 1f } ?: 0f } }
        if (shown) Box(Modifier.fillMaxSize()) {
            val drag = remember { MutableInteractionSource() }
            // FB4 U-2: with the keyboard up the panel rises by its height (as far as the top allows), so a focused page
            // field and the panel's controls stay above it; it settles back when the keyboard closes (P-STABLE keyboard
            // exception). The rise is that of the anchor the panel last settled on, so a drag follows the finger from the
            // first pixel: from Half it was already risen (FB4 fix M1), from Full it rises nothing (QA F4, no dead zone).
            // Leaving Full with the keyboard up closes the keyboard, so landing on Half adds no rise (no jump). Height and
            // bottom padding follow the keyboard's target, not its animation, so the page resizes once per open/close (m2).
            // Both are read in the same layout pass (QA F6): when the padding grew a frame before the height, the page was
            // laid out 0px tall at Half, and Android drops a 0-sized view's focus, so the keyboard typed into nothing.
            val ime = WindowInsets.ime // read only in the offset lambda: placement, not recomposition, each frame
            val imeTarget = WindowInsets.imeAnimationTarget
            val settledAt by remember { derivedStateOf { state.anchors.positionOf(state.settledValue) } }
            val view = LocalView.current
            LaunchedEffect(state) {
                snapshotFlow { state.settledValue == PanelState.Full && state.offset > 1f }.collect { leaving ->
                    if (leaving) ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
                }
            }
            Column(
                Modifier.fillMaxWidth()
                    .layout { m, c ->
                        val height = (h - top + minOf(top, imeTarget.getBottom(this).toFloat())).roundToInt()
                        val p = m.measure(Constraints.fixed(c.maxWidth, height))
                        layout(p.width, p.height) { p.place(0, 0) }
                    }
                    .offset { IntOffset(0, (state.offset - minOf(settledAt, ime.getBottom(this).toFloat())).coerceAtLeast(0f).roundToInt()) }
                    .background(LcTheme.colors.surfaceRaised)
                    // QA O5: the panel is a hit target over its whole area, so a tap on a gap (banner, handle row) never
                    // reaches the chat behind it; it consumes nothing, so its children keep their own gestures.
                    .pointerInput(Unit) {}
                    .windowInsetsPadding(WindowInsets.navigationBars.union(imeTarget)),
            ) {
                BrowserPanelFrame(
                    toolbar = {
                        // Only the handle and the toolbar drag the panel; the WebView below keeps its own gestures.
                        Column(
                            Modifier.anchoredDraggable(
                                state, Orientation.Vertical, interactionSource = drag,
                                flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, positionalThreshold = { it * 0.5f }, animationSpec = snapSpec),
                            ),
                        ) {
                            PanelHandle(target, drag, actions)
                            BrowserToolbar(
                                t.shortUrl(), s.tabs.size, onCollapse = null,
                                onBack = actions::back, onForward = actions::forward, onReload = actions::reload,
                                onNavigate = if (t.owner is Owner.User || t.control == Control.USER) actions::navigate else null,
                                onTabs = { actions.showTabs(true) },
                                secure = !t.insecure, canGoBack = t.canGoBack, canGoForward = t.canGoForward,
                                progress = if (t.loading) 0.5f else null, fullUrl = t.url,
                            )
                        }
                    },
                    ownership = {
                        OwnershipBar(
                            t.ownerUi(s), toolName = s.aiActivity[t.id], idle = !(s.running && s.isMine(t)),
                            onTakeOver = { actions.takeOver(t.id) },
                            onGiveBack = { if (t.owner is Owner.User) actions.handToAi(t.id) else actions.giveBack(t.id) },
                        )
                    },
                    banner = s.handoff?.takeIf { it.tabId == t.id }?.let { h ->
                        { HandoffBanner(h.reason, stringResource(R.string.ui_handoff_body), actions::stopRun, { actions.giveBack(t.id) }) }
                    },
                ) {
                    // P-STABLE M6: the sensitive notice floats over the page top (closable) instead of pushing the page down.
                    Box(Modifier.fillMaxSize()) {
                        BrowserSurface(t.id, s.highlight, actions)
                        // FB3 ui m2: closed per (tab, site); moving between pages of the same bank keeps it closed.
                        var closed by remember(t.id, t.host()) { mutableStateOf(false) }
                        if (t.id in s.sensitive && !closed) com.ouor.localchat.ui.components.Banner(
                            stringResource(R.string.ui_sensitive_site), com.ouor.localchat.ui.components.BannerVariant.Info, Modifier.padding(8.dp),
                            actions = listOf(com.ouor.localchat.ui.components.BannerAction(stringResource(R.string.ui_close)) { closed = true }),
                        )
                    }
                }
            }
            // QA F2: only while the AI controls the tab; in a handoff (the user already controls it) it never covers the banner.
            if (s.touchedAiTab == t.id && t.control == Control.AI) LcSnackbar(
                stringResource(R.string.ui_switch_manual), Modifier.align(Alignment.BottomCenter).padding(12.dp).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                icon = Icons.Outlined.FrontHand, actionLabel = stringResource(R.string.ui_switch), onAction = { actions.takeOver(t.id) },
            )
        }
    }
    if (s.showTabs) TabsSheet(s, actions)
    s.prompt?.let { PromptDialog(it, actions) }
}

/** FB-5 handle: 24dp row, 36×4 bar (48×4 textTertiary while dragged), resize actions for TalkBack. */
@Composable
private fun PanelHandle(panel: PanelState, drag: MutableInteractionSource, actions: BrowserActions) {
    val dragged by drag.collectIsDraggedAsState()
    val reduced = reducedMotion()
    val w by animateDpAsState(if (dragged) 48.dp else 36.dp, if (reduced) snap() else tween(120), label = "handleW")
    val color by animateColorAsState(if (dragged) LcTheme.colors.textTertiary else LcTheme.colors.borderStrong, if (reduced) snap() else tween(120), label = "handleC")
    val desc = stringResource(R.string.fb_e4_handle_cd)
    val state = stringResource(if (panel == PanelState.Full) R.string.fb_e4_state_full else R.string.fb_e4_state_half)
    val expand = stringResource(R.string.ui_expand)
    val collapse = stringResource(R.string.ui_collapse)
    Box(
        Modifier.fillMaxWidth().height(24.dp).semantics {
            contentDescription = desc
            stateDescription = state
            // TalkBack double tap = one step up, Full goes back to Half.
            onClick(if (panel == PanelState.Full) collapse else expand) { actions.setPanel(if (panel == PanelState.Full) PanelState.Half else PanelState.Full); true }
            customActions = listOfNotNull(
                CustomAccessibilityAction(expand) { actions.setPanel(panel.up()); true }.takeIf { panel != PanelState.Full },
                CustomAccessibilityAction(collapse) { actions.setPanel(panel.down()); true },
            )
        },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(w, 4.dp).background(color, com.ouor.localchat.ui.theme.LcRadius.full)) }
}

/** Test tag of the page area (P-STABLE bounds tests). */
internal const val PAGE_TAG = "browser-page"

/** The page's focused element (inside open shadow roots too), scrolled into view only if it is out of view. */
private const val SCROLL_FOCUSED_INTO_VIEW =
    "(function(){var e=document.activeElement;while(e&&e.shadowRoot&&e.shadowRoot.activeElement)e=e.shadowRoot.activeElement;" +
        "if(e&&e!==document.body&&e.scrollIntoView)e.scrollIntoView({block:'nearest'});})()"

/** Engine-owned WebView + highlight overlay (api-spec ui.browser.BrowserSurface). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BrowserSurface(tabId: Int, highlight: Highlight?, actions: BrowserActions) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag(PAGE_TAG)) {
        androidx.compose.runtime.key(tabId) {
            val view = remember(tabId) { actions.webView(tabId) }
            if (view != null) AndroidView(factory = { view }, Modifier.fillMaxSize(), onRelease = { actions.park(tabId) })
            else Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White))
            // QA F1: WebView scrolls a focused field into view only when the keyboard's show result comes before the
            // page shrinks; the panel may shrink first. Once the page has its keyboard-time height, bring the focused
            // field (if any) back into view. Scrolling only; nothing is read from the page.
            val keyboardUp = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
            LaunchedEffect(view, constraints.maxHeight, keyboardUp) {
                if (view != null && keyboardUp) {
                    kotlinx.coroutines.delay(200) // the renderer takes the new size first
                    view.evaluateJavascript(SCROLL_FOCUSED_INTO_VIEW, null)
                }
            }
        }
        val h = highlight?.takeIf { it.tabId == tabId && it.cssViewportWidth > 0f }
        val scale = h?.let { constraints.maxWidth / it.cssViewportWidth } ?: 1f
        ElementHighlightLayer(
            h?.let { Rect(it.left * scale, it.top * scale, (it.left + it.width) * scale, (it.top + it.height) * scale) },
            h?.let { stringResource(it.labelRes) }.orEmpty(), null, h,
        )
    }
}

/** SC-12. */
@Composable
private fun TabsSheet(s: BrowserUiState, actions: BrowserActions) {
    LcBottomSheet({ actions.showTabs(false) }, stringResource(R.string.ui_tabs_title, s.tabs.size), fullHeight = true) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
            LcIconButton(Icons.Rounded.Add, stringResource(R.string.ui_new_tab), { actions.newTab(null) })
        }
        // P-STABLE L8: a closed tab keeps an empty slot while the sheet is open, so the next card's X never slides under the finger.
        // Snapshot state written after composition, never during it (FB3 arch m7); slots hold no thumbnail Bitmap.
        var kept by remember { mutableStateOf(tabSlots(emptyList(), s.tabs)) }
        val slots = tabSlots(kept, s.tabs)
        LaunchedEffect(slots) { kept = slots }
        val live = s.tabs.associateBy { it.id }
        TabGrid(slots, Modifier.fillMaxWidth().height(600.dp)) { slot ->
            val t = live[slot.id]
            if (t != null) TabCard(t.title.ifEmpty { t.shortUrl() }, t.shortUrl(), t.tabOwner(s), { actions.select(t.id) }, onClose = { actions.close(t.id) }) { Thumbnail(t) }
            // OtherChat = a disabled card, so the invisible slot takes no tap or key focus (same size: fixed grid cell, one badge line).
            else Box(Modifier.reserved()) { TabCard(slot.title, slot.shortUrl(), TabOwner.OtherChat, {}) }
        }
    }
}

/**
 * Tab list slots: [kept] in their order (a closed tab stays as a placeholder), refreshed from [now], new tabs appended.
 * No slot keeps a thumbnail: a closed tab's Bitmap must not outlive it.
 */
internal fun tabSlots(kept: List<TabInfo>, now: List<TabInfo>): List<TabInfo> {
    val byId = now.associateBy { it.id }
    val old = kept.mapTo(HashSet()) { it.id }
    return (kept.map { byId[it.id] ?: it } + now.filter { it.id !in old }).map { it.copy(thumbnail = null) }
}

/** B-06: user-facing page requests. */
@Composable
private fun PromptDialog(p: BrowserPrompt, actions: BrowserActions) {
    val context = LocalContext.current
    when (p) {
        is BrowserPrompt.Download -> LcDialog(
            stringResource(R.string.ui_download_title), { actions.answerDownload(p, false) }, stringResource(R.string.ui_allow), { actions.answerDownload(p, true) },
            text = stringResource(R.string.ui_download_body, p.fileName, if (p.contentLength > 0) Formatter.formatShortFileSize(context, p.contentLength) else "?"),
            dismissLabel = stringResource(R.string.ui_deny),
        )
        is BrowserPrompt.Permission -> LcDialog(
            stringResource(R.string.ui_permission_title), { actions.answerPermission(p, false) }, stringResource(R.string.ui_allow), { actions.answerPermission(p, true) },
            text = stringResource(R.string.ui_permission_body, p.origin, p.resources.joinToString { it.substringAfterLast('.') }),
            dismissLabel = stringResource(R.string.ui_deny),
        )
        is BrowserPrompt.Dialog -> {
            var text by remember(p.id) { mutableStateOf(p.defaultText.orEmpty()) }
            LcDialog(
                p.url.substringAfter("://").substringBefore('/'), { actions.answerDialog(p, false, null) }, stringResource(R.string.ui_ok),
                { actions.answerDialog(p, true, text.takeIf { p.kind == DialogKind.PROMPT }) }, text = p.message,
                kind = if (p.kind == DialogKind.ALERT) com.ouor.localchat.ui.components.DialogKind.Info else com.ouor.localchat.ui.components.DialogKind.Confirm,
            ) { if (p.kind == DialogKind.PROMPT) LcTextField(text, { text = it }) }
        }
        is BrowserPrompt.FileChooser -> {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { actions.answerFiles(p, it.takeIf { u -> u.isNotEmpty() }) }
            LaunchedEffect(p.id) {
                val types = p.acceptTypes.filter { '/' in it }.ifEmpty { listOf("*/*") }
                picker.launch(types.toTypedArray())
            }
        }
    }
}
