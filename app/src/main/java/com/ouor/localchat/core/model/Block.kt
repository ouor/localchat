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
    /** Original text sent to the model. A-05 trimming happens only when building a request. */
    val content: String,
    val isError: Boolean,
    /** A-04 short summary shown when expanded. */
    val summary: String? = null,
    /** S-04 citation sources, numbered per message. */
    val sources: List<Source> = emptyList(),
    /** Screenshot / PDF attachment ids. */
    val artifactIds: List<String> = emptyList(),
    /** screenshot: attached as an image to the next request. */
    val imageForModelId: String? = null,
) : Block {
    val key: String get() = toolKey(step, callId)
}
