package com.ouor.localchat.ui.browser

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.ouor.localchat.ui.components.BrowserPeekCard
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
import com.ouor.localchat.ui.components.TextFieldKind
import com.ouor.localchat.ui.components.toolVisual
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

/** Peek card shown above the composer (SC-11 collapsed). */
@Composable
fun BrowserPeek(s: BrowserUiState, actions: BrowserActions, modifier: Modifier = Modifier) {
    val t = s.current ?: return
    if (s.panel != PanelState.Peek) return
    val tool = s.aiActivity[t.id]
    BrowserPeekCard(
        t.ownerUi(s), tool?.let { n -> toolVisual(n).label?.let { stringResource(it) } ?: n } ?: t.title.ifEmpty { t.shortUrl() }, t.shortUrl(),
        onExpand = { actions.setPanel(PanelState.Half) }, modifier = modifier, detail = tool,
        onTakeOver = if (t.owner is Owner.Ai && t.control == Control.AI) ({ actions.takeOver(t.id) }) else null,
    ) { Thumbnail(t) }
}

@Composable
private fun Thumbnail(t: TabInfo) {
    val bmp = t.thumbnail ?: return
    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
}

/** Half / full panel overlay plus the tab list, prompts and the B-09 ③ snackbar. Place in a full-size Box. */
@Composable
fun BrowserOverlay(s: BrowserUiState, actions: BrowserActions) {
    val t = s.current
    if (t != null && s.panel >= PanelState.Half) {
        BackHandler { actions.setPanel(if (s.panel == PanelState.Full) PanelState.Half else PanelState.Peek) }
        val full = s.panel == PanelState.Full
        var editing by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // shortcut: no drag gestures between states; the handle toggles half/full, the chevron collapses.
            Column(
                (if (full) Modifier.fillMaxSize().statusBarsPadding() else Modifier.fillMaxWidth().fillMaxHeight(0.55f))
                    .background(LcTheme.colors.surfaceRaised).navigationBarsPadding(),
            ) {
                if (!full) Box(Modifier.fillMaxWidth().height(20.dp).clickable { actions.setPanel(PanelState.Full) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).background(LcTheme.colors.borderStrong, com.ouor.localchat.ui.theme.LcRadius.full))
                }
                BrowserPanelFrame(
                    toolbar = {
                        BrowserToolbar(
                            t.shortUrl(), s.tabs.size,
                            onCollapse = { actions.setPanel(if (full) PanelState.Half else PanelState.Peek) },
                            onBack = actions::back, onForward = actions::forward, onReload = actions::reload,
                            onUrlClick = { if (t.owner is Owner.User || t.control == Control.USER) editing = true },
                            onTabs = { actions.showTabs(true) },
                            secure = !t.insecure, canGoBack = t.canGoBack, canGoForward = t.canGoForward,
                            progress = if (t.loading) 0.5f else null,
                        )
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
                    Column(Modifier.fillMaxSize()) {
                        if (t.id in s.sensitive) com.ouor.localchat.ui.components.Banner(
                            stringResource(R.string.ui_sensitive_site), com.ouor.localchat.ui.components.BannerVariant.Info, Modifier.padding(8.dp),
                        )
                        Box(Modifier.weight(1f)) { BrowserSurface(t.id, s.highlight, actions) }
                    }
                }
            }
            if (s.touchedAiTab == t.id) LcSnackbar(
                stringResource(R.string.ui_switch_manual), Modifier.padding(12.dp).navigationBarsPadding(),
                icon = Icons.Outlined.FrontHand, actionLabel = stringResource(R.string.ui_switch), onAction = { actions.takeOver(t.id) },
            )
        }
        if (editing) {
            var text by remember { mutableStateOf(t.url) }
            LcDialog(stringResource(R.string.ui_edit_url), { editing = false }, stringResource(R.string.ui_ok), { actions.navigate(text); editing = false }) {
                LcTextField(text, { text = it }, kind = TextFieldKind.Url)
            }
        }
    }
    if (s.showTabs) TabsSheet(s, actions)
    s.prompt?.let { PromptDialog(it, actions) }
}

/** Engine-owned WebView + highlight overlay (api-spec ui.browser.BrowserSurface). */
@Composable
fun BrowserSurface(tabId: Int, highlight: Highlight?, actions: BrowserActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        androidx.compose.runtime.key(tabId) {
            val view = remember(tabId) { actions.webView(tabId) }
            if (view != null) AndroidView(factory = { view }, Modifier.fillMaxSize(), onRelease = { actions.park(tabId) })
            else Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White))
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
        TabGrid(s.tabs, Modifier.fillMaxWidth().height(600.dp)) { t ->
            TabCard(t.title.ifEmpty { t.shortUrl() }, t.shortUrl(), t.tabOwner(s), { actions.select(t.id) }, onClose = { actions.close(t.id) }) { Thumbnail(t) }
        }
    }
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
