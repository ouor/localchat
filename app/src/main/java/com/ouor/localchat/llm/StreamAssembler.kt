package com.ouor.localchat.llm

import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.llm.wire.ChatChunk
import java.util.UUID

/**
 * Turns the chunks of one attempt into [LlmEvent] deltas (architecture 4.3, 4.4). Not thread-safe;
 * one instance per attempt. End-of-stream rules (empty reply, missing finish) are the client's.
 */
internal class StreamAssembler {

    var finishReason: String? = null
        private set
    var usage: Usage? = null
        private set

    /** Anything at all arrived (reasoning included): from here on the attempt is not retried (P-09). */
    var hasContent = false
        private set

    /** Text, refusal or a tool call arrived, i.e. the reply is not empty. */
    var hasAnswer = false
        private set

    private val calls = LinkedHashMap<Int, CallBuilder>()
    private var thinkMode = ThinkMode.DETECT
    private val held = StringBuilder()

    fun accept(chunk: ChatChunk): List<LlmEvent> {
        chunk.usage?.let { usage = Usage(it.promptTokens, it.completionTokens, it.promptCacheHitTokens ?: it.promptTokensDetails?.cachedTokens) }
        val out = mutableListOf<LlmEvent>()
        for (choice in chunk.choices) {
            choice.finishReason?.let { finishReason = it }
            val delta = choice.delta ?: continue
            delta.reasoningContent?.let { reasoning(out, it, "reasoning_content") }
            delta.reasoning?.let { reasoning(out, it, "reasoning") }
            delta.content?.let { content(out, it) }
            delta.refusal?.takeIf { it.isNotEmpty() }?.let {
                hasContent = true; hasAnswer = true
                out += LlmEvent.RefusalDelta(it)
            }
            delta.toolCalls?.forEach { d ->
                hasContent = true; hasAnswer = true
                val key = d.index ?: run {
                    // No index: a new id starts a new call, anything else continues the last one.
                    val last = calls.entries.lastOrNull()
                    if (last == null || (d.id != null && d.id != last.value.id)) calls.size else last.key
                }
                val b = calls.getOrPut(key) { CallBuilder() }
                if (b.id == null) b.id = d.id
                d.function?.name?.let { if (b.name.isEmpty()) b.name = it }
                d.function?.arguments?.let { b.args.append(it) }
            }
        }
        return out
    }

    /** Releases text held back while looking for `<think>` tags. Call once at the end. */
    fun flush(): List<LlmEvent> {
        val out = mutableListOf<LlmEvent>()
        if (held.isNotEmpty()) {
            val rest = held.toString().also { held.clear() }
            if (thinkMode == ThinkMode.THINK) reasoning(out, rest, THINK_TAG) else text(out, rest)
        }
        thinkMode = ThinkMode.BODY
        return out
    }

    /** Assembled calls in index order; empty arguments become `{}`, a missing id gets `call_xxxxxxxx`. */
    fun toolCalls(): List<ToolCallRequest> = calls.toSortedMap().values.map {
        ToolCallRequest(
            id = it.id ?: "call_" + UUID.randomUUID().toString().replace("-", "").take(8),
            name = it.name,
            argumentsJson = it.args.toString().ifBlank { "{}" },
        )
    }

    private fun reasoning(out: MutableList<LlmEvent>, text: String, field: String) {
        if (text.isEmpty()) return
        hasContent = true
        out += LlmEvent.ReasoningDelta(text, field)
    }

    private fun text(out: MutableList<LlmEvent>, text: String) {
        if (text.isEmpty()) return
        hasContent = true; hasAnswer = true
        out += LlmEvent.TextDelta(text)
    }

    /** Splits a leading `<think>…</think>` (Qwen3/R1 style local servers) into reasoning. */
    private fun content(out: MutableList<LlmEvent>, piece: String) {
        when (thinkMode) {
            ThinkMode.BODY -> text(out, piece)
            ThinkMode.DETECT -> {
                held.append(piece)
                val s = held.toString().trimStart()
                when {
                    s.isEmpty() || (OPEN.startsWith(s) && s.length < OPEN.length) -> Unit // undecided yet
                    s.startsWith(OPEN) -> {
                        val rest = s.substring(OPEN.length)
                        held.clear()
                        thinkMode = ThinkMode.THINK
                        content(out, rest)
                    }
                    else -> {
                        thinkMode = ThinkMode.BODY
                        text(out, held.toString())
                        held.clear()
                    }
                }
            }
            ThinkMode.THINK -> {
                held.append(piece)
                val buf = held.toString()
                val end = buf.indexOf(CLOSE)
                if (end >= 0) {
                    held.clear()
                    thinkMode = ThinkMode.BODY
                    reasoning(out, buf.substring(0, end), THINK_TAG)
                    text(out, buf.substring(end + CLOSE.length).trimStart())
                } else {
                    // Keep a possible partial "</think>" at the end for the next piece.
                    val keep = (CLOSE.length - 1 downTo 1).firstOrNull { buf.endsWith(CLOSE.substring(0, it)) } ?: 0
                    held.setLength(0)
                    held.append(buf, buf.length - keep, buf.length)
                    reasoning(out, buf.substring(0, buf.length - keep), THINK_TAG)
                }
            }
        }
    }

    private class CallBuilder {
        var id: String? = null
        var name: String = ""
        val args = StringBuilder()
    }

    private enum class ThinkMode { DETECT, THINK, BODY }

    companion object {
        const val THINK_TAG = "think_tag"
        private const val OPEN = "<think>"
        private const val CLOSE = "</think>"
    }
}
