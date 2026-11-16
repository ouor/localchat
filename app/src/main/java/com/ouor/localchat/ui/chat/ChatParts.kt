package com.ouor.localchat.ui.chat

import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.ui.components.AttachmentTile
import com.ouor.localchat.ui.components.TileKind
import com.ouor.localchat.ui.components.TileState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import com.ouor.localchat.R
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock as ThinkingData
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.speech.VoiceState
import com.ouor.localchat.ui.components.AssistantMessage
import com.ouor.localchat.ui.components.BranchSwitcher
import com.ouor.localchat.ui.components.CapabilityIcons
import com.ouor.localchat.ui.components.ChatErrorCard
import com.ouor.localchat.ui.components.EndpointGroupHeader
import com.ouor.localchat.ui.components.ErrorAction
import com.ouor.localchat.ui.components.LcBottomSheet
import com.ouor.localchat.ui.components.MessageActionBar
import com.ouor.localchat.ui.components.LcDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Close
import com.ouor.localchat.ui.components.ModelRow
import com.ouor.localchat.ui.components.RefusalCard
import com.ouor.localchat.ui.components.SectionLabel
import com.ouor.localchat.ui.components.SourceCard
import com.ouor.localchat.ui.components.SourceSite
import com.ouor.localchat.ui.components.SourceStack
import com.ouor.localchat.ui.components.StepItemData
import com.ouor.localchat.ui.components.StepList
import com.ouor.localchat.ui.components.StepListState
import com.ouor.localchat.ui.components.StepState
import com.ouor.localchat.ui.components.ThinkingBlock
import com.ouor.localchat.ui.components.ThinkingState
import com.ouor.localchat.ui.components.UserMessage
import com.ouor.localchat.ui.components.UserMessageEditor
import com.ouor.localchat.ui.components.toolVisual
import com.ouor.localchat.ui.markdown.MarkdownMessage
import com.ouor.localchat.ui.theme.endpointDotColor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun UserTurn(user: UserUi, cs: ComposerUiState, chat: ChatActions, attachments: Map<String, Attachment> = emptyMap(), thumbnail: (Attachment) -> Any? = { null }) {
    var editing by rememberSaveable(user.node.id) { mutableStateOf<String?>(null) }
    val e = editing
    if (e != null) {
        UserMessageEditor(e, { editing = it }, { editing = null }, {
            cs.model?.let { m -> chat.editAndResend(user.node.id, e.trim(), user.attachmentIds, m.ref) }
            editing = null
        })
    } else {
        UserMessage(
            user.text,
            onLongClick = { editing = user.text },
            attachments = if (user.attachmentIds.isEmpty()) null else ({
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    user.attachmentIds.forEach { id ->
                        val a = attachments[id]
                        val image = a?.kind == AttachmentKind.IMAGE
                        AttachmentTile(
                            a?.name ?: "…", if (image) TileKind.Image else TileKind.Document,
                            if (a == null || !a.hasOriginal) TileState.Missing else TileState.Ready,
                            thumbnail = if (image && a.hasOriginal) thumbnail(a) else null,
                        )
                    }
                }
            }),
            footer = if (user.versions.size > 1) ({ Versions(user.version, user.versions, chat::selectVersion) }) else null,
        )
    }
}

@Composable
private fun Versions(version: Int, ids: List<String>, onSelect: (String) -> Unit) =
    BranchSwitcher(version, ids.size, { ids.getOrNull(version - 2)?.let(onSelect) }, { ids.getOrNull(version)?.let(onSelect) })

@Composable
internal fun AssistantTurn(
    reply: AssistantUi,
    state: ChatUiState,
    voice: VoiceState,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onVersion: (String) -> Unit,
    onSources: () -> Unit,
    onLink: (String) -> Unit,
    onErrorAction: (ErrorAction) -> Unit,
    onReadAloud: ((String) -> Unit)? = null,
    toolStep: (ToolCallBlock, ToolResultBlock?) -> com.ouor.localchat.chat.ToolStep? = { _, _ -> null },
    thumbnail: (Attachment) -> Any? = { null },
    onOpenFile: ((Attachment) -> Unit)? = null,
    onShareFile: ((Attachment) -> Unit)? = null,
    onSaveFile: ((Attachment) -> Unit)? = null,
) {
    val body = reply.body
    val running = reply.live && state.running
    val text = replyText(body.blocks)
    val sources = sources(body)
    var stepsOpen by rememberSaveable(reply.node.id) { mutableStateOf(false) }
    var cite by remember { mutableStateOf<Source?>(null) }
    var usageOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Any?>(null) }
    AssistantMessage {
        val calls = body.blocks.filterIsInstance<ToolCallBlock>()
        if (calls.isNotEmpty()) {
            val results = body.blocks.filterIsInstance<ToolResultBlock>().associateBy { it.key }
            // BUG-08: the result goes to toolStep too, or counts stay the raw "458 nodes".
            val steps = calls.map { call ->
                val r = results[call.key]
                // BUG-17 (B-08): a screenshot step shows its image; tap opens it full screen.
                val image = r?.artifactIds.orEmpty().firstNotNullOfOrNull { id -> state.attachments[id]?.takeIf { it.mime.startsWith("image/") }?.let(thumbnail) }
                stepOf(call, r, toolStep(call, r)).copy(thumbnail = image, onClick = image?.let { m -> { viewing = m } })
            }
            val failed = steps.count { it.state == StepState.Failed }
            val browser = calls.any { toolVisual(it.name).agent }
            val listState = when {
                running && state.pause != null -> StepListState.Paused
                running -> StepListState.Running
                body.status == ReplyStatus.STOPPED -> StepListState.Stopped
                failed > 0 -> StepListState.FailedSome
                else -> StepListState.Done
            }
            val title = when (listState) {
                StepListState.Running, StepListState.Paused -> stringResource(if (browser) R.string.ui_browser_working else R.string.ui_working)
                StepListState.Stopped -> stringResource(R.string.ui_steps_stopped, steps.size)
                StepListState.FailedSome -> stringResource(R.string.ui_steps_failed, steps.size, failed)
                StepListState.Done -> stringResource(R.string.ui_steps_done, steps.size)
            }
            val caption = when {
                running -> stringResource(R.string.ui_step_of, state.step + 1, state.maxSteps)
                listState == StepListState.Stopped && browser -> stringResource(R.string.ui_stopped_no_undo) // BUG-16 (A-03)
                else -> null
            }
            StepList(listState, title, steps, stepsOpen, { stepsOpen = !stepsOpen }, caption = caption)
            val files = body.blocks.filterIsInstance<ToolResultBlock>().flatMap { it.artifactIds }
                .mapNotNull { state.attachments[it] }.filterNot { it.mime.startsWith("image/") }
            val context = androidx.compose.ui.platform.LocalContext.current
            files.forEach { a ->
                Column {
                    com.ouor.localchat.ui.components.FileCard(
                        a.name, android.text.format.Formatter.formatShortFileSize(context, a.size), a.name.substringAfterLast('.', ""),
                        onClick = onOpenFile?.let { { it(a) } },
                    )
                    androidx.compose.foundation.layout.Row {
                        onShareFile?.let { f -> com.ouor.localchat.ui.components.LcButton(stringResource(R.string.ui_share), { f(a) }, variant = com.ouor.localchat.ui.components.ButtonVariant.Text, size = com.ouor.localchat.ui.components.ButtonSize.Sm) }
                        onSaveFile?.let { f -> com.ouor.localchat.ui.components.LcButton(stringResource(R.string.ui_save), { f(a) }, variant = com.ouor.localchat.ui.components.ButtonVariant.Text, size = com.ouor.localchat.ui.components.ButtonSize.Sm) }
                    }
                }
            }
        }
        viewing?.let { m -> ImageViewer(m) { viewing = null } }
        body.blocks.filterIsInstance<ThinkingData>().lastOrNull()?.let { t -> Thinking(t, running && t.endedAt == null, body.status == ReplyStatus.STOPPED) }
        // BUG-15 (S-04): a badge opens a preview first, not the browser.
        if (text.isNotEmpty()) MarkdownMessage(text, sources, running, onLink, { s -> cite = s }, activeCitation = cite?.n)
        if (sources.isNotEmpty()) SourceStack(sources.map { SourceSite(it.siteName ?: siteName(it.url)) }, sources.size, onSources)
        when {
            body.refusal -> RefusalCard({ onCopy(text) })
            body.error != null -> ChatErrorCard(body.error!!, onErrorAction, usedOptionalFeatures = body.options.run { reasoning || search || browser })
        }
        cite?.let { s -> CitationSheet(s, { cite = null; onLink(s.url) }) { cite = null } }
        body.usage?.takeIf { usageOpen }?.let { u ->
            val fmt = { n: Int -> "%,d".format(n) }
            LcDialog(
                stringResource(R.string.ui_usage_title), { usageOpen = false }, stringResource(R.string.ui_ok), { usageOpen = false },
                text = listOfNotNull(
                    stringResource(R.string.ui_usage_body, fmt(u.promptTokens), fmt(u.completionTokens)),
                    u.cachedPromptTokens?.let { stringResource(R.string.ui_usage_cached, fmt(it)) },
                ).joinToString(LINE),
                dismissLabel = null,
            )
        }
        if (!running) {
            MessageActionBar(
                { onCopy(text) },
                onReadAloud = if (onReadAloud != null && text.isNotBlank()) ({ onReadAloud(text) }) else null,
                reading = state.readingId == reply.node.id,
                onRegenerate = if (reply.canRegenerate) onRegenerate else null,
                onContinue = if (reply.canContinue) onContinue else null,
                branch = if (reply.versions.size > 1) ({ Versions(reply.version, reply.versions, onVersion) }) else null,
                modelName = body.model?.displayName,
                onModelLongPress = if (body.usage != null) ({ usageOpen = true }) else null, // BUG-14 (M-11)
            )
        }
    }
}

@Composable
private fun Thinking(t: ThinkingData, live: Boolean, stopped: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (live) LaunchedEffect(Unit) { while (true) { delay(100); now = System.currentTimeMillis() } }
    val start = t.startedAt ?: now
    val ms = ((t.endedAt ?: if (live) now else start) - start).coerceAtLeast(0)
    val state = when { live -> ThinkingState.Thinking; stopped && t.endedAt == null -> ThinkingState.Stopped; else -> ThinkingState.Done }
    val timer = if (live) "%.1fs".format(ms / 1000f) else stringResource(R.string.ui_seconds, (ms / 1000).toInt().coerceAtLeast(1))
    ThinkingBlock(state, timer, open, { open = !open }, t.text)
}

/** BUG-06: text written in different agent steps is separate paragraphs, not "first.Here are". */
internal fun replyText(blocks: List<com.ouor.localchat.core.model.Block>): String {
    val texts = blocks.filterIsInstance<TextBlock>()
    return buildString {
        texts.forEachIndexed { i, t ->
            if (i > 0 && t.step != texts[i - 1].step && isNotBlank() && !endsWith("\n\n")) append("\n\n")
            append(t.text)
        }
    }
}

@Composable
private fun stepOf(call: ToolCallBlock, result: ToolResultBlock?, step: com.ouor.localchat.chat.ToolStep?): StepItemData {
    val v = toolVisual(call.name)
    val state = when (call.state) {
        ToolCallState.RUNNING -> StepState.Running
        ToolCallState.AWAITING_APPROVAL -> StepState.AwaitingApproval
        ToolCallState.DONE -> if (result?.isError == true) StepState.Failed else StepState.Done
        ToolCallState.FAILED -> StepState.Failed
        ToolCallState.DENIED -> StepState.Denied
        ToolCallState.CANCELLED -> StepState.Stopped
    }
    val detail = when {
        state == StepState.Failed && step?.failureRes != null -> stringResource(step.failureRes) // BUG-10
        step?.countRes != null -> androidx.compose.ui.res.pluralStringResource(step.countRes, step.count, step.count) // BUG-08
        else -> result?.summary
    }
    return StepItemData(v.icon, step?.let { stringResource(it.labelRes) } ?: call.name, state, step?.argSummary?.ifEmpty { null }, detail, v.agent)
}

@Composable
internal fun toolLabel(name: String): String = toolVisual(name).label?.let { stringResource(it) } ?: name


internal fun sources(body: MessageBody.Assistant): List<Source> =
    body.blocks.filterIsInstance<ToolResultBlock>().flatMap { it.sources }.distinctBy { it.n }.sortedBy { it.n }

private fun siteName(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

private const val LINE = "\n"

/** Full-screen image (tool screenshot): fit to screen, close button. */
@Composable
private fun ImageViewer(model: Any, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(com.ouor.localchat.ui.theme.LcTheme.colors.surface)) {
            coil3.compose.AsyncImage(model, stringResource(R.string.ui_screenshot), Modifier.fillMaxSize().padding(16.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            com.ouor.localchat.ui.components.LcIconButton(
                androidx.compose.material.icons.Icons.Rounded.Close, stringResource(R.string.ui_close), onClose,
                Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(8.dp),
            )
        }
    }
}

/** S-04 citation preview: title, site, snippet and [열기]. */
@Composable
private fun CitationSheet(s: Source, onOpen: () -> Unit, onDismiss: () -> Unit) {
    LcBottomSheet(onDismiss, null) {
        SourceCard(s.n, s.title, SourceSite(s.siteName ?: siteName(s.url)), s.url, onOpen, snippet = s.snippet)
        com.ouor.localchat.ui.components.LcButton(
            stringResource(R.string.ui_open_page), onOpen, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
internal fun SourcesSheet(sources: List<Source>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    LcBottomSheet(onDismiss, stringResource(R.string.ui_sources)) {
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            items(sources, key = { it.n }) { s ->
                SourceCard(s.n, s.title, SourceSite(s.siteName ?: siteName(s.url)), s.url, { onOpen(s.url) }, snippet = s.snippet)
            }
        }
    }
}

/**
 * SC-06 / FB-3: recent (expanded, chat only), then one collapsible group per endpoint, all collapsed on open.
 * [endpointIds] adds groups for endpoints with 0 models; collapse state lives only while the sheet is open.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ModelPickerSheet(
    models: List<ModelInfo>,
    current: ModelRef?,
    endpointName: (String) -> String,
    onDismiss: () -> Unit,
    showRecent: Boolean = true,
    endpointIds: List<String> = emptyList(),
    onPick: (ModelRef) -> Unit,
) {
    LcBottomSheet(onDismiss, stringResource(R.string.ui_choose_model)) {
        ModelPickerList(models, current, endpointName, showRecent, endpointIds, onPick)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ModelPickerList(
    models: List<ModelInfo>,
    current: ModelRef?,
    endpointName: (String) -> String,
    showRecent: Boolean,
    endpointIds: List<String>,
    onPick: (ModelRef) -> Unit,
) {
    val c = com.ouor.localchat.ui.theme.LcTheme.colors
    val recent = if (showRecent) models.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(3) else emptyList()
    val byEndpoint = models.groupBy { it.endpointId }
    val groups = (endpointIds + byEndpoint.keys).distinct()
    val open = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    LazyColumn(Modifier.heightIn(max = 600.dp)) {
        @Composable fun Row(m: ModelInfo) = ModelRow(
            m.displayName, { onPick(m.ref) },
            subtitle = if (m.displayName != m.modelId) m.modelId else null,
            selected = current?.endpointId == m.endpointId && current.modelId == m.modelId,
        ) { CapabilityIcons(m.caps.toolCalling.value, m.caps.imageInput.value, m.caps.reasoning.value != null) }
        if (recent.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.ui_recent)) }
            items(recent, key = { "r" + it.endpointId + it.modelId }) { Row(it) }
        }
        groups.forEachIndexed { i, endpointId ->
            val list = byEndpoint[endpointId].orEmpty()
            val expanded = open[endpointId] == true
            val header: @Composable () -> Unit = {
                val n = endpointName(endpointId)
                Box(Modifier.background(c.surfaceRaised)) {
                    EndpointGroupHeader(
                        n, endpointDotColor(n), list.size, expanded = expanded, onToggle = { open[endpointId] = !expanded },
                        hasSelected = current?.endpointId == endpointId && list.any { it.modelId == current.modelId },
                    )
                    if (i > 0 || recent.isNotEmpty()) androidx.compose.material3.HorizontalDivider(color = c.border)
                }
            }
            // Expanded header sticks so a long group can be collapsed from its middle.
            if (expanded) stickyHeader(key = "h$endpointId") { header() } else item(key = "h$endpointId") { header() }
            if (expanded) {
                if (list.isEmpty()) item(key = "e$endpointId") {
                    androidx.compose.material3.Text(
                        stringResource(R.string.fb_e3_group_empty), Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = com.ouor.localchat.ui.theme.LcTheme.text.caption, color = c.textTertiary,
                    )
                }
                items(list, key = { it.endpointId + "/" + it.modelId }) { Row(it) }
            }
        }
    }
}
