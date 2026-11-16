package com.ouor.localchat.chat.run

import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.core.model.Block
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolCallState
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.llm.LlmEvent
import com.ouor.localchat.llm.toChatError

/** Pure folding of LlmEvents into the reply's blocks (architecture 5.3). Saving is TurnRunner's job. */
object StreamCollector {

    fun reduce(body: MessageBody.Assistant, event: LlmEvent, step: Int, now: Long): MessageBody.Assistant =
        when (event) {
            is LlmEvent.ReasoningDelta -> {
                val last = body.blocks.lastOrNull()
                if (last is ThinkingBlock && last.step == step && last.endedAt == null && last.wireField == event.field) {
                    body.replaceLast(last.copy(text = last.text + event.text))
                } else {
                    body.closeThinking(now).append(ThinkingBlock(event.text, step, now, null, event.field))
                }
            }
            is LlmEvent.TextDelta -> body.closeThinking(now).appendText(event.text, step)
            is LlmEvent.RefusalDelta -> body.closeThinking(now).appendText(event.text, step).copy(refusal = true)
            is LlmEvent.ToolCallsReady -> event.calls.fold(body.closeThinking(now)) { b, call ->
                b.append(ToolCallBlock(call.id, call.name, call.argumentsJson, step, ToolCallState.RUNNING, now))
            }
            is LlmEvent.Retrying -> body
            is LlmEvent.Finished -> body.closeThinking(now).copy(
                finishReason = event.finishReason,
                usage = event.usage ?: body.usage,
                refusal = body.refusal || event.finishReason == "content_filter",
            )
            is LlmEvent.Failed -> body.closeThinking(now).copy(error = event.error.toChatError())
        }

    /**
     * Ends an unfinished reply (stop, crash recovery): every tool call without a result gets the
     * harness result, open blocks are closed, and PENDING/STREAMING becomes STOPPED.
     * [inFlightCallId] is the call that was executing; with [unknownOutcome] every call is treated so.
     */
    fun close(body: MessageBody.Assistant, inFlightCallId: String?, now: Long, unknownOutcome: Boolean = false): MessageBody.Assistant {
        val answered = body.blocks.filterIsInstance<ToolResultBlock>().map { it.callId }.toSet()
        val blocks = mutableListOf<Block>()
        for (block in body.closeThinking(now).blocks) {
            if (block is ToolCallBlock && block.callId !in answered) {
                val dispatched = unknownOutcome || block.callId == inFlightCallId
                blocks += block.copy(state = ToolCallState.CANCELLED, endedAt = block.endedAt ?: now)
                blocks += ToolResultBlock(
                    callId = block.callId,
                    step = block.step,
                    content = if (dispatched) SystemPrompt.INTERRUPTED else SystemPrompt.ABORTED,
                    isError = true,
                )
            } else {
                blocks += block
            }
        }
        val status = when (body.status) {
            ReplyStatus.PENDING, ReplyStatus.STREAMING -> ReplyStatus.STOPPED
            else -> body.status
        }
        return body.copy(blocks = blocks, status = status)
    }

    private fun MessageBody.Assistant.append(block: Block) = copy(blocks = blocks + block)

    private fun MessageBody.Assistant.replaceLast(block: Block) = copy(blocks = blocks.dropLast(1) + block)

    private fun MessageBody.Assistant.appendText(text: String, step: Int): MessageBody.Assistant {
        val last = blocks.lastOrNull()
        return if (last is TextBlock && last.step == step) replaceLast(last.copy(text = last.text + text))
        else append(TextBlock(text, step))
    }

    private fun MessageBody.Assistant.closeThinking(now: Long): MessageBody.Assistant {
        val last = blocks.lastOrNull()
        return if (last is ThinkingBlock && last.endedAt == null) replaceLast(last.copy(endedAt = now)) else this
    }
}
