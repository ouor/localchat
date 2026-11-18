package com.ouor.localchat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A piece of an assistant reply. [step] is the agent loop step (from 0): the current turn is
 * re-sent to the server split by step (architecture 3.2, 5.4).
 */
@Serializable
sealed interface Block {
    val step: Int
}

@Serializable
@SerialName("text")
data class TextBlock(
    val text: String,
    override val step: Int,
    /** DeepSeek TEMPLATE_RESPONSE (imported) or refusal template. */
    val isTemplate: Boolean = false,
) : Block

@Serializable
@SerialName("thinking")
data class ThinkingBlock(
    val text: String,
    override val step: Int,
    val startedAt: Long?,
    val endedAt: Long?,
    /** Wire field the reasoning arrived in; sent back under the same name ("think_tag" is never sent). */
    val wireField: String = "reasoning_content",
) : Block

@Serializable
@SerialName("tool_call")
data class ToolCallBlock(
    val callId: String,
    val name: String,
    val argumentsJson: String,
    override val step: Int,
    val state: ToolCallState,
    val startedAt: Long,
    val endedAt: Long? = null,
) : Block {
    /** Internal identity: some servers restart ids every step, so [callId] alone can repeat. [callId] stays the wire id. */
    val key: String get() = toolKey(step, callId)
}

internal fun toolKey(step: Int, callId: String) = "$step/$callId"

@Serializable
enum class ToolCallState { RUNNING, AWAITING_APPROVAL, DONE, FAILED, DENIED, CANCELLED }

@Serializable
@SerialName("tool_result")
data class ToolResultBlock(
    val callId: String,
    override val step: Int,
    /**
     * Page-derived text as the tool returned it, capped (browser-layers 3-3); the boundary is added when it is sent
     * ([modelText]). Stored as "content": a record from before keeps its whole text here and is wrapped once more.
     * A-05 trimming happens only when building a request.
     */
    @SerialName("content") val page: String,
    val isError: Boolean,
    /** A-04 short summary shown when expanded. */
    val summary: String? = null,
    /** S-04 citation sources, numbered per message. */
    val sources: List<Source> = emptyList(),
    /** Page picture / PDF attachment ids. */
    val artifactIds: List<String> = emptyList(),
    /** A page picture (vision models): attached as an image to the next request. */
    val imageForModelId: String? = null,
    /** App-written line before the page text. */
    val lead: String = "",
    /** App-written guidance after the page text. */
    val note: String = "",
    /** A policy refused or withheld the call: shown as blocked by the app. */
    val refused: Boolean = false,
) : Block {
    /** What the model reads: [lead], [page] inside the boundary, [note]. */
    val modelText: String get() = com.ouor.localchat.core.Untrusted.result(lead, page, note)

    val key: String get() = toolKey(step, callId)
}
