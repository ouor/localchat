package com.ouor.localchat.chat

import com.ouor.localchat.chat.context.ContextAssembler
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.chat.context.TokenEstimate
import com.ouor.localchat.chat.context.ToolResultTrimmer
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Summary
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.llm.LlmResult
import com.ouor.localchat.llm.toChatError
import javax.inject.Inject
import javax.inject.Singleton

/** A-06 / A-07 summary failed; [error] is shown like a reply error. */
class CompactionFailed(val error: ChatError) : Exception(error.kind.name)

/** A-06 (architecture 5.5): summarizes the path except the latest two user turns. */
@Singleton
class Compactor @Inject constructor(
    private val store: ChatStore,
    private val llm: LlmPort,
    private val attachments: AttachmentPort,
    private val clock: Clock,
) {
    /**
     * [path] runs from the root to the newest user message (the reply being produced excluded).
     * Returns the saved summary, or null when there is nothing new to summarize.
     */
    suspend fun compact(sessionId: String, path: List<MessageNode>, model: ResolvedModel): Summary? {
        val users = path.indices.filter { path[it].body is MessageBody.User }
        if (users.size < 3) return null
        val cut = users[users.size - 2] - 1
        val previous = ContextAssembler.applicableSummary(store.summaries(sessionId), path)
        val from = previous?.let { s -> path.indexOfFirst { it.id == s.coversUpToId } + 1 } ?: 0
        if (from > cut) return null

        val transcript = buildString {
            if (previous != null) append("Earlier summary:\n").append(previous.text).append("\n\n")
            for (node in path.subList(from, cut + 1)) append(render(node))
        }
        val request = ContextAssembler.plain(model, SystemPrompt.COMPACT, fit(transcript, model.caps.contextWindow.value))
        return when (val result = llm.complete(model.target, request)) {
            is LlmResult.Failed -> throw CompactionFailed(result.error.toChatError())
            is LlmResult.Ok -> {
                val text = result.text.trim()
                if (text.isEmpty()) throw CompactionFailed(ChatError(ChatErrorKind.EMPTY_RESPONSE))
                Summary(Ids.new(), sessionId, path[cut].id, text, clock.now()).also { store.addSummary(it) }
            }
        }
    }

    /**
     * The summary request must fit the window it is meant to relieve: keep the start and the end, drop
     * the middle. shortcut: one request, no chunked map-reduce; split into rounds if summaries get too lossy.
     */
    private fun fit(transcript: String, window: Int?): String {
        val budget = window?.let { (it * INPUT_SHARE * TokenEstimate.CHARS_PER_TOKEN).toInt() } ?: return transcript
        if (transcript.length <= budget) return transcript
        val half = budget / 2
        return transcript.take(half) + "\n…[middle omitted: too long to summarize at once]…\n" + transcript.takeLast(half)
    }

    private suspend fun render(node: MessageNode): String = when (val b = node.body) {
        // Attached documents' text is part of the conversation; without it a paper vanishes after compaction.
        is MessageBody.User -> buildString {
            append("User: ").append(b.text)
            for (id in b.attachmentIds) {
                val p = attachments.payload(id) ?: continue
                val a = p.attachment
                val text = p.text
                when {
                    a.kind == AttachmentKind.IMAGE -> append("\n[image: ${a.name}]")
                    text != null -> {
                        append("\n[attached file: ${a.name}]\n").append(text.take(ATTACHMENT_CHARS))
                        if (text.length > ATTACHMENT_CHARS) append("\n…[truncated]")
                    }
                    else -> append("\n[file: ${a.name}]")
                }
            }
            append("\n\n")
        }
        is MessageBody.Assistant -> if (b.status == ReplyStatus.ERROR || b.refusal) "" else buildString {
            append("Assistant: ")
            for (block in b.blocks) when (block) {
                is TextBlock -> append(block.text)
                is ToolCallBlock -> append("\n[tool ${block.name} ${block.argumentsJson}]\n")
                is ToolResultBlock -> append("[untrusted tool result] ").append(ToolResultTrimmer.cut(block.modelText)).append('\n')
                else -> Unit
            }
            append("\n\n")
        }
    }

    private companion object {
        /** Share of the window the summary request may use; the rest is the system prompt and the summary. */
        const val INPUT_SHARE = 0.7
        const val ATTACHMENT_CHARS = 40_000
    }
}
