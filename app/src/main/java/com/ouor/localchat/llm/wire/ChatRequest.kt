package com.ouor.localchat.llm.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `POST /chat/completions` body. Built by chat.context.ContextAssembler, sent by
 * ChatCompletionsClient. Encode with core.AppJson (nulls omitted, defaults kept).
 * `max_tokens` is deliberately absent (architecture 4.1).
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val tools: List<WireTool>? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** DEEPSEEK_THINKING only; must be sent as "disabled" to turn reasoning off. */
    val thinking: Thinking? = null,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: StreamOptions? = StreamOptions(includeUsage = true),
)

/**
 * One chat message. [content] is a JSON string, or an array of parts
 * (`{"type":"text","text":…}`, `{"type":"image_url","image_url":{"url":"data:…"}}`) for user images.
 * Reasoning is sent back in the field it arrived in (current turn only, architecture 5.4).
 */
@Serializable
data class WireMessage(
    /** "system" | "user" | "assistant" | "tool". */
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
)

@Serializable
data class WireToolCall(val id: String, val function: WireFunctionCall, val type: String = "function")

@Serializable
data class WireFunctionCall(val name: String, val arguments: String)

@Serializable
data class WireTool(val function: WireFunction, val type: String = "function")

@Serializable
data class WireFunction(val name: String, val description: String, val parameters: JsonObject)

@Serializable
data class Thinking(val type: String) {
    companion object {
        val ENABLED = Thinking("enabled")
        val DISABLED = Thinking("disabled")
    }
}

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean)
