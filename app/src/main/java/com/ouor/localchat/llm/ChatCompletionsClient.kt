package com.ouor.localchat.llm

import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.llm.wire.ChatRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** OpenAI Chat Completions compatible client (architecture 4). */
@Singleton
class ChatCompletionsClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    /**
     * Streams one completion, retrying per [LlmTarget.maxRetries] until the first content delta.
     * Emits exactly one terminal event ([LlmEvent.Finished] or [LlmEvent.Failed]). Cancellation is
     * coroutine cancellation and closes the socket immediately.
     */
    fun stream(target: LlmTarget, request: ChatRequest): Flow<LlmEvent> = TODO("track A")

    /** Non-streaming use (titles, summaries); collects [stream] internally. */
    suspend fun complete(target: LlmTarget, request: ChatRequest): LlmResult = TODO("track A")

    /** `GET {baseUrl}/models`, entries returned as-is so extension fields survive. */
    suspend fun listModels(target: LlmTarget): List<JsonObject> = TODO("track A")
}

/** Where to send a request. http base URLs are refused unless [allowHttp] (SEC-5). */
data class LlmTarget(
    val baseUrl: String,
    val apiKey: String?,
    val maxRetries: Int,
    val allowHttp: Boolean,
) {
    override fun toString() = "LlmTarget(baseUrl=$baseUrl, apiKey=${if (apiKey == null) "none" else "****"})"
}

sealed interface LlmEvent {
    /** [field] is the wire field name ("reasoning_content", "reasoning", or "think_tag"). */
    data class ReasoningDelta(val text: String, val field: String) : LlmEvent
    data class TextDelta(val text: String) : LlmEvent
    data class RefusalDelta(val text: String) : LlmEvent

    /** Emitted once, after assembly, in index order. */
    data class ToolCallsReady(val calls: List<ToolCallRequest>) : LlmEvent
    data class Retrying(val attempt: Int, val max: Int, val delayMs: Long) : LlmEvent

    /** Terminal. */
    data class Finished(val finishReason: String?, val usage: Usage?) : LlmEvent

    /** Terminal. [partial] = content had already arrived (never retried). */
    data class Failed(val error: LlmError, val partial: Boolean) : LlmEvent
}

/** [argumentsJson] is raw text from the model; it may be invalid JSON (the tool reports that back). */
data class ToolCallRequest(val id: String, val name: String, val argumentsJson: String)

sealed interface LlmResult {
    data class Ok(val text: String, val finishReason: String?, val usage: Usage?) : LlmResult
    data class Failed(val error: LlmError) : LlmResult
}
