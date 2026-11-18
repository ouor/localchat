package com.ouor.localchat.chat.context

import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.llm.wire.ChatRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** A-06 trigger estimate (architecture 5.5): known usage plus chars / 3.5 for what came after it. */
object TokenEstimate {
    const val CHARS_PER_TOKEN = 3.5
    /** An image part counts as about 1,000 tokens instead of its base64 length. */
    private const val IMAGE_CHARS = 3500

    /**
     * [nodes] = path including the current reply. Usage reported before [summaryAt] is stale
     * (it counted the summarized turns), so then the whole [request] is counted instead.
     */
    fun estimate(request: ChatRequest, nodes: List<MessageNode>, summaryAt: Long?): Int {
        val idx = nodes.indexOfLast {
            val b = it.body
            b is MessageBody.Assistant && b.usage != null && (summaryAt == null || it.createdAt > summaryAt)
        }
        if (idx < 0) return (requestChars(request) / CHARS_PER_TOKEN).toInt()
        val anchor = nodes[idx].body as MessageBody.Assistant
        val usage = anchor.usage!!
        // Tool results of the anchor's last step were added after its usage was reported.
        val lastStep = anchor.blocks.maxOfOrNull { it.step }
        val after = anchor.blocks.filter { it is ToolResultBlock && it.step == lastStep }.sumOf { chars(it) } +
            nodes.drop(idx + 1).sumOf { chars(it) }
        return usage.promptTokens + usage.completionTokens + (after / CHARS_PER_TOKEN).toInt()
    }

    fun requestChars(request: ChatRequest): Int = request.messages.sumOf { m ->
        contentChars(m.content) + (m.reasoningContent?.length ?: 0) + (m.reasoning?.length ?: 0) +
            (m.toolCalls?.sumOf { it.function.name.length + it.function.arguments.length } ?: 0)
    } + (request.tools?.sumOf { it.function.description.length + it.function.parameters.toString().length } ?: 0)

    private fun contentChars(e: JsonElement?): Int = when (e) {
        null -> 0
        is JsonPrimitive -> e.content.length
        is JsonArray -> e.sumOf { part ->
            val obj = part as? JsonObject
            if (obj?.get("type")?.jsonPrimitive?.content == "image_url") IMAGE_CHARS else contentChars(obj?.get("text"))
        }
        else -> e.toString().length
    }

    private fun chars(node: MessageNode): Int = when (val b = node.body) {
        is MessageBody.User -> b.text.length
        is MessageBody.Assistant -> b.blocks.sumOf { chars(it) }
    }

    private fun chars(block: com.ouor.localchat.core.model.Block): Int = when (block) {
        is TextBlock -> block.text.length
        is ThinkingBlock -> block.text.length
        is ToolCallBlock -> block.name.length + block.argumentsJson.length
        // Only the newest page picture is sent, but counting each is a safe upper bound (SE 13).
        is ToolResultBlock -> block.content.length + (if (block.imageForModelId != null) IMAGE_CHARS else 0)
    }
}
