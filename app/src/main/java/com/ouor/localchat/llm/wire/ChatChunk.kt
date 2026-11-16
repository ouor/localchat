package com.ouor.localchat.llm.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One `data:` line of a streamed completion. Only the fields we read; everything else
 * (`reasoning_details`, `logprobs`, ids) is dropped by `ignoreUnknownKeys`.
 */
@Serializable
data class ChatChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: WireUsage? = null,
    /** Some servers report failures inside the stream (`{"error":{…}}`). Kept raw: shapes differ. */
    val error: JsonElement? = null,
)

@Serializable
data class ChunkChoice(
    val delta: ChunkDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    val refusal: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
data class ToolCallDelta(
    val index: Int? = null,
    val id: String? = null,
    val function: FunctionDelta? = null,
)

@Serializable
data class FunctionDelta(val name: String? = null, val arguments: String? = null)

@Serializable
data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    /** DeepSeek. */
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    /** OpenAI. */
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
)

@Serializable
data class PromptTokensDetails(@SerialName("cached_tokens") val cachedTokens: Int? = null)
