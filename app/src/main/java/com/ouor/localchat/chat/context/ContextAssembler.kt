package com.ouor.localchat.chat.context

import com.ouor.localchat.chat.AttachmentPayload
import com.ouor.localchat.chat.ResolvedModel
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Summary
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.llm.wire.withReasoning
import com.ouor.localchat.llm.wire.WireFunction
import com.ouor.localchat.llm.wire.WireFunctionCall
import com.ouor.localchat.llm.wire.WireMessage
import com.ouor.localchat.llm.wire.WireTool
import com.ouor.localchat.llm.wire.WireToolCall
import com.ouor.localchat.tools.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Everything one request is built from. Attachments are fetched beforehand so assembly stays pure. */
data class AssemblyInput(
    val system: String,
    /** Visible path from the root to the current turn's user message (current reply excluded). */
    val path: List<MessageNode>,
    /** The reply being produced; its finished steps are sent back (rule 6). */
    val current: MessageBody.Assistant,
    val summaries: List<Summary>,
    val attachments: Map<String, AttachmentPayload>,
    val model: ResolvedModel,
    val options: GenOptions,
    val tools: List<Tool>,
    /** M-03 (rule 10). */
    val continueMode: Boolean = false,
    /** FB3 QA M1: the tools stay listed but no call is allowed (tool_choice "none"). */
    val noCalls: Boolean = false,
    /** FB3 D1 / FB4 E-1: where the hidden tools-off note goes, if anywhere. */
    val note: NotePlacement = NotePlacement.NONE,
    /** The note's text: tools turned off, or the step limit's wrap-up. */
    val noteText: String = SystemPrompt.TOOLS_OFF_NOTE,
    /** Steps left before the step-limit question; 1-3 adds a note to the newest tool result (tools-redesign 5-4). */
    val stepsLeft: Int = 0,
    /** browser-layers 5-1: the tabs as the turn started, after the last user message; never stored. */
    val tabNote: String? = null,
    /** Tabs open now: a closed tab's lists keep only their head (5-3). Null = unknown. */
    val openTabs: Set<Int>? = null,
)

enum class NotePlacement {
    NONE,

    /** The last message, as a user message (DeepSeek's measured form). */
    LAST_USER,

    /** The end of the system message, for servers that reject `user` right after `tool` (Mistral). */
    SYSTEM_END,
}

/** Architecture 5.4: every rule for what is sent lives here, numbered as in the table. */
object ContextAssembler {

    fun build(input: AssemblyInput): ChatRequest {
        val caps = input.model.caps
        // Rule 11 (FB4 E-1): the tools-off note at the end of the system message.
        val system = if (input.note == NotePlacement.SYSTEM_END) input.system + "\n" + input.noteText else input.system
        val messages = mutableListOf(WireMessage("system", JsonPrimitive(system)))

        // Rule 2: summary replaces the path up to and including coversUpToId.
        var path = input.path
        applicableSummary(input.summaries, path)?.let { summary ->
            path = path.drop(path.indexOfFirst { it.id == summary.coversUpToId } + 1)
            messages += WireMessage("user", JsonPrimitive(SystemPrompt.SUMMARY_PREFIX + summary.text))
        }

        // Rule 3: drop whole turns whose reply failed, was refused, never started or stopped empty.
        val kept = mutableListOf<MessageNode>()
        var i = 0
        while (i < path.size) {
            val node = path[i]
            val reply = path.getOrNull(i + 1)?.takeIf { node.body is MessageBody.User && it.body is MessageBody.Assistant }
            if (reply != null) {
                if (!excluded(reply.body as MessageBody.Assistant)) kept += listOf(node, reply)
                i += 2
            } else {
                if (node.body is MessageBody.User) kept += node
                i += 1
            }
        }

        val replies = kept.mapNotNull { it.body as? MessageBody.Assistant } +
            if (input.continueMode) emptyList() else listOf(input.current)
        // Keys are "reply index/step/callId": step + id repeat across replies on servers that renumber (AN 4).
        val trimmed = ToolResultTrimmer.trim(
            replies.flatMapIndexed { reply, body ->
                val results = body.blocks.filterIsInstance<ToolResultBlock>().associateBy { it.key }
                body.blocks.filterIsInstance<ToolCallBlock>()
                    .map { Triple("$reply/${it.key}", it.name, results[it.key]) }
            },
            input.model.caps.contextWindow.value,
            input.openTabs,
        )
        // Rule 12 (tools-redesign 5-4, G-m8): near the step limit the newest result says how many steps are left, outside
        // the page boundary. Only this request has it: it is never stored, so it never goes stale.
        val sendable = if (input.stepsLeft !in 1..3 || input.continueMode) trimmed else {
            val last = input.current.blocks.filterIsInstance<ToolCallBlock>().lastOrNull()?.let { "${replies.size - 1}/${it.key}" }
            if (last == null || last !in trimmed) trimmed else trimmed + (last to trimmed.getValue(last) + "\n\n" + SystemPrompt.stepsLeft(input.stepsLeft))
        }

        val latestImage = replies.flatMap { b -> b.blocks.filterIsInstance<ToolResultBlock>().mapNotNull { it.imageForModelId } }.lastOrNull()

        var reply = 0
        val lastUser = kept.lastOrNull { it.body is MessageBody.User }
        for (node in kept) {
            when (val body = node.body) {
                is MessageBody.User -> messages += userMessage(body, input, input.tabNote.takeIf { node === lastUser && !input.continueMode })
                is MessageBody.Assistant -> messages += assistantSteps(body, reply++, current = false, sendable, latestImage, input)
            }
        }
        if (input.continueMode) {
            // Rule 10: existing text as the assistant turn, then the hidden instruction.
            val text = input.current.blocks.filterIsInstance<TextBlock>().joinToString("") { it.text }
            messages += WireMessage("assistant", JsonPrimitive(text))
            messages += WireMessage("user", JsonPrimitive(SystemPrompt.CONTINUE))
        } else {
            messages += assistantSteps(input.current, reply, current = true, sendable, latestImage, input)
        }
        // Rule 11 (FB3 D1): or as the last user message; never stored.
        if (input.note == NotePlacement.LAST_USER) messages += WireMessage("user", JsonPrimitive(input.noteText))

        // Architecture 4.3, via llm's single mapping.
        // The model's current settings win over the options (a model whose reasoning was switched off never reasons).
        val spec = caps.reasoning.value
        val effort = input.options.reasoningEffort?.takeIf { spec?.efforts?.contains(it) == true } ?: spec?.defaultEffort
        return ChatRequest(
            model = input.model.ref.modelId,
            messages = messages,
            tools = input.tools.takeIf { it.isNotEmpty() }
                ?.map { WireTool(WireFunction(it.name, it.description, it.parameters)) },
            toolChoice = "none".takeIf { input.noCalls },
            // FB-6: only a user-set max output is sent; server/built-in values stay the server's own default.
            maxTokens = caps.maxOutputTokens.takeIf { it.source == CapSource.USER }?.value,
        ).withReasoning(input.model.reasoningWire, input.options.reasoning && spec != null, effort)
    }

    /**
     * M-1: a user max output bigger than the room the input leaves would make vLLM-style servers answer
     * 400 "maximum context length", read as ContextOverflow (a needless compaction). Cut it to the room.
     * Audit #6: under [MIN_OUTPUT] TurnRunner compacts first; whatever room is still left is sent, since
     * omitting the field would let the server default overrun it. No room at all: omitted, the server's overflow
     * error says the rest.
     */
    fun fitOutput(request: ChatRequest, window: Int?, inputEstimate: Int): ChatRequest {
        val user = request.maxTokens ?: return request
        val room = (window ?: return request) - inputEstimate
        return request.copy(maxTokens = if (room > 0) minOf(user, room) else null)
    }

    /** Audit #6: a user max output with under [MIN_OUTPUT] room left asks for compaction before sending. */
    fun tooLittleRoom(request: ChatRequest, window: Int?, inputEstimate: Int): Boolean =
        request.maxTokens != null && window != null && window - inputEstimate < MIN_OUTPUT

    const val MIN_OUTPUT = 256

    /** Non-streaming side request (summary, title): no tools, reasoning off where it can be switched off. */
    fun plain(model: ResolvedModel, system: String, user: String) = ChatRequest(
        model = model.ref.modelId,
        messages = listOf(WireMessage("system", JsonPrimitive(system)), WireMessage("user", JsonPrimitive(user))),
        stream = false,
        streamOptions = null,
    ).withReasoning(model.reasoningWire, on = false, effort = null)

    /** The newest summary whose cut point is on [path] (branch safety, 5.5). */
    fun applicableSummary(summaries: List<Summary>, path: List<MessageNode>): Summary? {
        val ids = path.map { it.id }.toSet()
        return summaries.filter { it.coversUpToId in ids }.maxByOrNull { it.createdAt }
    }

    private fun excluded(body: MessageBody.Assistant): Boolean =
        // An ERROR turn that already ran tools is kept like STOPPED: the model must know what it did (SE 12).
        (body.status == ReplyStatus.ERROR && body.blocks.none { it is ToolCallBlock }) ||
            body.refusal ||
            body.status == ReplyStatus.PENDING ||
            body.status == ReplyStatus.STREAMING ||
            (body.status == ReplyStatus.STOPPED &&
                body.blocks.none { (it is TextBlock && it.text.isNotBlank()) || it is ToolCallBlock })

    /** Rule 4. */
    private fun userMessage(body: MessageBody.User, input: AssemblyInput, tabNote: String? = null): WireMessage {
        val texts = mutableListOf<String>()
        if (body.text.isNotBlank()) texts += body.text
        val images = mutableListOf<JsonElement>()
        for (id in body.attachmentIds) {
            val p = input.attachments[id] ?: continue
            val a = p.attachment
            if (!a.hasOriginal) continue
            if (a.kind == AttachmentKind.IMAGE) {
                if (input.model.caps.imageInput.value && p.imageDataUrl != null) images += imagePart(p.imageDataUrl)
                else texts += "[Image: ${a.name}]"
            } else {
                // Model-facing labels stay English: a Korean label made models answer English questions in Korean (v1.0.1 QA).
                texts += if (p.text != null) "File: ${a.name}\n```\n${p.text}\n```" else "[File: ${a.name}]"
            }
        }
        if (tabNote != null) texts += tabNote
        val text = texts.joinToString("\n\n")
        if (images.isEmpty()) return WireMessage("user", JsonPrimitive(text))
        val parts = buildList {
            if (text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", text) })
            addAll(images)
        }
        return WireMessage("user", JsonArray(parts))
    }

    /** Rules 5-8: one assistant message per step, its tool results, then any page picture as a user image. */
    private fun assistantSteps(
        body: MessageBody.Assistant,
        reply: Int,
        current: Boolean,
        sendable: Map<String, String>,
        latestImage: String?,
        input: AssemblyInput,
    ): List<WireMessage> = buildList {
        for ((_, blocks) in body.blocks.groupBy { it.step }.toSortedMap()) {
            val text = blocks.filterIsInstance<TextBlock>().joinToString("") { it.text }
            val calls = blocks.filterIsInstance<ToolCallBlock>()
            if (text.isEmpty() && calls.isEmpty()) continue
            // Rule 6: reasoning goes back only for the current turn's tool-calling steps, in its own field.
            val thinking = blocks.filterIsInstance<ThinkingBlock>().filter { current && calls.isNotEmpty() && it.wireField != "think_tag" }
            val reasoning = thinking.joinToString("") { it.text }.takeIf { it.isNotEmpty() }
            val field = thinking.firstOrNull()?.wireField
            add(
                WireMessage(
                    role = "assistant",
                    content = if (text.isEmpty()) null else JsonPrimitive(text),
                    toolCalls = calls.map { WireToolCall(it.callId, WireFunctionCall(it.name, it.argumentsJson)) }
                        .takeIf { it.isNotEmpty() },
                    reasoningContent = reasoning.takeIf { field == "reasoning_content" },
                    reasoning = reasoning.takeIf { field == "reasoning" },
                ),
            )
            val results = body.blocks.filterIsInstance<ToolResultBlock>().associateBy { it.key }
            for (call in calls) {
                add(WireMessage("tool", JsonPrimitive(sendable["$reply/${call.key}"] ?: SystemPrompt.ABORTED), toolCallId = call.callId))
            }
            // Rule 7: tool messages cannot carry images.
            if (input.model.caps.imageInput.value) {
                // Only the newest page picture is resent; older ones would pile up on every request (SE 13).
                val images = calls.mapNotNull { results[it.key]?.imageForModelId }.filter { it == latestImage }
                    .mapNotNull { input.attachments[it]?.imageDataUrl }
                if (images.isNotEmpty()) add(WireMessage("user", JsonArray(images.map(::imagePart))))
            }
        }
    }

    private fun imagePart(dataUrl: String) = buildJsonObject {
        put("type", "image_url")
        putJsonObject("image_url") { put("url", dataUrl) }
    }
}
