package com.ouor.localchat.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.io.InterruptedIOException

/** HTTP status / exception / in-stream error → [LlmError] (architecture 4.5). Pure. */
internal object ErrorClassifier {

    private val contextOverflow = Regex(
        "context length|maximum context|too many tokens|context_length_exceeded",
        RegexOption.IGNORE_CASE,
    )

    fun classify(status: Int, body: String?): LlmError = byStatus(status, body?.let(::messageOf))

    fun classify(t: Throwable): LlmError = when (t) {
        is HttpStatusException -> classify(t.code, t.body)
        is InterruptedIOException -> LlmError.Timeout // SocketTimeoutException included
        is IOException -> LlmError.Network
        else -> LlmError.Protocol(t.message)
    }

    /** `{"error":{…}}` inside a stream; a numeric `code` (OpenRouter) is used as the status. */
    fun classifyStreamError(error: JsonElement): LlmError {
        val code = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
        val message = textOf(error)
        return when {
            code != null && code >= 400 -> byStatus(code, message)
            message != null && contextOverflow.containsMatchIn(message) -> LlmError.ContextOverflow(null, message)
            else -> LlmError.Protocol(message)
        }
    }

    private fun byStatus(status: Int, message: String?): LlmError = when {
        status == 401 || status == 403 -> LlmError.Auth(status, message)
        status == 402 -> LlmError.Balance(status, message)
        status == 429 -> LlmError.RateLimited(status, message)
        status >= 500 -> LlmError.ServerBusy(status, message)
        message != null && contextOverflow.containsMatchIn(message) -> LlmError.ContextOverflow(status, message)
        else -> LlmError.BadRequest(status, message)
    }

    /** OpenAI `{"error":{"message"}}`, `{"error":"…"}`, `{"message":"…"}`, else the raw body (cut to 500). */
    private fun messageOf(body: String): String? {
        val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val parsed = obj?.let { textOf(it["error"]) ?: (it["message"] as? JsonPrimitive)?.contentOrNull }
        return parsed ?: body.trim().take(500).ifEmpty { null }
    }

    private fun textOf(e: JsonElement?): String? = when (e) {
        is JsonObject -> (e["message"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> e.contentOrNull
        else -> null
    }
}
