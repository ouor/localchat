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
)

/** Architecture 5.4: every rule for what is sent lives here, numbered as in the table. */
object ContextAssembler {

    fun build(input: AssemblyInput): ChatRequest {
        val caps = input.model.caps
        val messages = mutableListOf(WireMessage("system", JsonPrimitive(input.system)))

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
        val sendable = ToolResultTrimmer.trim(
            replies.flatMapIndexed { reply, body ->
                val results = body.blocks.filterIsInstance<ToolResultBlock>().associateBy { it.key }
                body.blocks.filterIsInstance<ToolCallBlock>()
                    .map { Triple("$reply/${it.key}", it.name, results[it.key]?.content ?: SystemPrompt.ABORTED) }
            },
        )

        val latestImage = replies.flatMap { b -> b.blocks.filterIsInstance<ToolResultBlock>().mapNotNull { it.imageForModelId } }.lastOrNull()

        var reply = 0
        for (node in kept) {
            when (val body = node.body) {
                is MessageBody.User -> messages += userMessage(body, input)
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

        // Architecture 4.3, via llm's single mapping.
        val effort = input.options.reasoningEffort ?: caps.reasoning.value?.defaultEffort
        return ChatRequest(
            model = input.model.ref.modelId,
            messages = messages,
            tools = input.tools.takeIf { it.isNotEmpty() }
                ?.map { WireTool(WireFunction(it.name, it.description, it.parameters)) },
            // FB-6: only a user-set max output is sent; server/built-in values stay the server's own default.
            maxTokens = caps.maxOutputTokens.takeIf { it.source == CapSource.USER }?.value,
        ).withReasoning(input.model.reasoningWire, input.options.reasoning, effort)
    }

    /**
     * M-1: a user max output bigger than the room the input leaves would make vLLM-style servers answer
     * 400 "maximum context length", read as ContextOverflow (a needless compaction). Cut it to the room,
     * or omit it when less than [MIN_OUTPUT] is left.
     */
    fun fitOutput(request: ChatRequest, window: Int?, inputEstimate: Int): ChatRequest {
        val user = request.maxTokens ?: return request
        val room = (window ?: return request) - inputEstimate
        return request.copy(maxTokens = if (room >= MIN_OUTPUT) minOf(user, room) else null)
    }

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
    private fun userMessage(body: MessageBody.User, input: AssemblyInput): WireMessage {
        val texts = mutableListOf<String>()
        if (body.text.isNotBlank()) texts += body.text
        val images = mutableListOf<JsonElement>()
        for (id in body.attachmentIds) {
            val p = input.attachments[id] ?: continue
            val a = p.attachment
            if (!a.hasOriginal) continue
            if (a.kind == AttachmentKind.IMAGE) {
                if (input.model.caps.imageInput.value && p.imageDataUrl != null) images += imagePart(p.imageDataUrl)
                else texts += "[이미지: ${a.name}]"
            } else {
                texts += if (p.text != null) "파일: ${a.name}\n```\n${p.text}\n```" else "[파일: ${a.name}]"
            }
        }
        val text = texts.joinToString("\n\n")
        if (images.isEmpty()) return WireMessage("user", JsonPrimitive(text))
        val parts = buildList {
            if (text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", text) })
            addAll(images)
        }
        return WireMessage("user", JsonArray(parts))
    }

    /** Rules 5-8: one assistant message per step, its tool results, then any screenshot as a user image. */
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
                // Only the newest screenshot is resent; older ones would pile up on every request (SE 13).
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
