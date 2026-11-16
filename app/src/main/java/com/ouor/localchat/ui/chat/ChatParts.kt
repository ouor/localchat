package com.ouor.localchat.ui.chat

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
internal fun UserTurn(user: UserUi, cs: ComposerUiState, chat: ChatViewModel) {
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
) {
    val body = reply.body
    val running = reply.live && state.running
    val text = body.blocks.filterIsInstance<TextBlock>().joinToString("") { it.text }
    val sources = sources(body)
    var stepsOpen by rememberSaveable(reply.node.id) { mutableStateOf(false) }
    AssistantMessage {
        val calls = body.blocks.filterIsInstance<ToolCallBlock>()
        if (calls.isNotEmpty()) {
            val results = body.blocks.filterIsInstance<ToolResultBlock>().associateBy { it.callId }
            val steps = calls.map { stepOf(it, results[it.callId]) }
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
            val caption = if (running) stringResource(R.string.ui_step_of, state.step + 1, state.maxSteps) else null
            StepList(listState, title, steps, stepsOpen, { stepsOpen = !stepsOpen }, caption = caption)
        }
        body.blocks.filterIsInstance<ThinkingData>().lastOrNull()?.let { t -> Thinking(t, running && t.endedAt == null, body.status == ReplyStatus.STOPPED) }
        if (text.isNotEmpty()) MarkdownMessage(text, sources, running, onLink, { s -> onLink(s.url) })
        if (sources.isNotEmpty()) SourceStack(sources.map { SourceSite(it.siteName ?: siteName(it.url)) }, sources.size, onSources)
        when {
            body.refusal -> RefusalCard({ onCopy(text) })
            body.error != null -> ChatErrorCard(body.error!!, onErrorAction, usedOptionalFeatures = body.options.run { reasoning || search || browser })
        }
        if (!running) {
            MessageActionBar(
                { onCopy(text) },
                onRegenerate = if (reply.canRegenerate) onRegenerate else null,
                onContinue = if (reply.canContinue) onContinue else null,
                branch = if (reply.versions.size > 1) ({ Versions(reply.version, reply.versions, onVersion) }) else null,
                modelName = body.model?.displayName,
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

@Composable
private fun stepOf(call: ToolCallBlock, result: ToolResultBlock?): StepItemData {
    val v = toolVisual(call.name)
    val state = when (call.state) {
        ToolCallState.RUNNING -> StepState.Running
        ToolCallState.AWAITING_APPROVAL -> StepState.AwaitingApproval
        ToolCallState.DONE -> if (result?.isError == true) StepState.Failed else StepState.Done
        ToolCallState.FAILED -> StepState.Failed
        ToolCallState.DENIED -> StepState.Denied
        ToolCallState.CANCELLED -> StepState.Stopped
    }
    return StepItemData(v.icon, toolLabel(call.name), state, argSummary(call.argumentsJson), result?.summary, v.agent)
}

@Composable
internal fun toolLabel(name: String): String = toolVisual(name).label?.let { stringResource(it) } ?: name

/** The most telling argument (query, url, element text …) for the step chip. */
private fun argSummary(json: String): String? = runCatching {
    val o = Json.parseToJsonElement(json) as? JsonObject ?: return null
    listOf("query", "url", "text", "element", "description", "selector", "key").firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.content }
        ?: o.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}.getOrNull()?.take(60)

internal fun sources(body: MessageBody.Assistant): List<Source> =
    body.blocks.filterIsInstance<ToolResultBlock>().flatMap { it.sources }.distinctBy { it.n }.sortedBy { it.n }

private fun siteName(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

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

/** SC-06: recent first, then by endpoint. */
@Composable
internal fun ModelPickerSheet(models: List<ModelInfo>, current: ModelRef?, endpointName: (String) -> String, onDismiss: () -> Unit, onPick: (ModelRef) -> Unit) {
    LcBottomSheet(onDismiss, stringResource(R.string.ui_choose_model)) {
        val recent = models.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(3)
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
            models.groupBy { it.endpointId }.forEach { (endpointId, list) ->
                item(key = "h$endpointId") { val n = endpointName(endpointId); EndpointGroupHeader(n, endpointDotColor(n), list.size) }
                items(list, key = { it.endpointId + "/" + it.modelId }) { Row(it) }
            }
        }
    }
}
