package com.ouor.localchat.llm

import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.llm.wire.ChatChunk
import com.ouor.localchat.llm.wire.ChatRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    fun stream(target: LlmTarget, request: ChatRequest): Flow<LlmEvent> = flow {
        val url = target.url("chat/completions")
            ?: return@flow emit(LlmEvent.Failed(LlmError.Config(target.urlProblem()), partial = false))
        val maxRetries = target.maxRetries.coerceIn(0, 10)
        var body = request.copy(stream = true)
        var strippedField = false
        var attempt = 0
        while (true) {
            val failure = attemptOnce(url, target.apiKey, body) ?: return@flow
            if (!failure.partial && !strippedField) {
                // R7: retry once without an optional field the server named in its 400.
                val stripped = withoutRejectedField(body, failure.error)
                if (stripped != null) {
                    body = stripped
                    strippedField = true
                    continue
                }
            }
            if (failure.partial || !RetryPolicy.isRetryable(failure.error) || attempt >= maxRetries) {
                return@flow emit(LlmEvent.Failed(failure.error, failure.partial))
            }
            attempt++
            val wait = RetryPolicy.delayMs(attempt, failure.retryAfterMs)
            emit(LlmEvent.Retrying(attempt, maxRetries, wait))
            delay(wait)
        }
    }

    /** Non-streaming use (titles, summaries); collects [stream] internally. Refusal text is not included. */
    suspend fun complete(target: LlmTarget, request: ChatRequest): LlmResult {
        val text = StringBuilder()
        var result: LlmResult = LlmResult.Failed(LlmError.Protocol("no terminal event"))
        stream(target, request).collect { e ->
            when (e) {
                is LlmEvent.TextDelta -> text.append(e.text)
                is LlmEvent.Finished -> result = LlmResult.Ok(text.toString(), e.finishReason, e.usage)
                is LlmEvent.Failed -> result = LlmResult.Failed(e.error)
                else -> Unit
            }
        }
        return result
    }

    /** `GET {baseUrl}/models`, entries returned as-is so extension fields survive. Throws [LlmException]. */
    suspend fun listModels(target: LlmTarget): List<JsonObject> {
        val url = target.url("models") ?: throw LlmException(LlmError.Config(target.urlProblem()))
        val (code, body) = try {
            http.newCall(Request.Builder().url(url).auth(target.apiKey).get().build()).await()
        } catch (e: IOException) {
            throw LlmException(ErrorClassifier.classify(e))
        }
        if (code !in 200..299) throw LlmException(ErrorClassifier.classify(code, body))
        return runCatching { json.parseToJsonElement(body).jsonObject.getValue("data").jsonArray.map { it.jsonObject } }
            .getOrElse { throw LlmException(LlmError.Protocol("unexpected /models body")) }
    }

    /** One HTTP attempt. Emits deltas and, on success, the terminal events; returns null then. */
    private suspend fun FlowCollector<LlmEvent>.attemptOnce(url: HttpUrl, apiKey: String?, body: ChatRequest): Failure? {
        val asm = StreamAssembler()
        var failure: Failure? = null
        var done = false
        val request = Request.Builder().url(url).auth(apiKey)
            .post(json.encodeToString(ChatRequest.serializer(), body).toRequestBody(JSON))
            .build()
        http.sseData(request)
            .transformWhile { data ->
                if (data.trim() == "[DONE]") {
                    done = true
                    return@transformWhile false
                }
                val chunk = runCatching { json.decodeFromString(ChatChunk.serializer(), data) }.getOrNull()
                when {
                    chunk == null -> failure = Failure(LlmError.Protocol("unreadable chunk"), asm.hasContent)
                    chunk.error != null -> failure = Failure(ErrorClassifier.classifyStreamError(chunk.error), asm.hasContent)
                    else -> asm.accept(chunk).forEach { emit(it) }
                }
                failure == null
            }
            .catch { e ->
                val retryAfter = (e as? HttpStatusException)?.retryAfter
                failure = Failure(
                    ErrorClassifier.classify(e),
                    partial = e !is HttpStatusException && asm.hasContent,
                    retryAfterMs = RetryPolicy.parseRetryAfter(retryAfter, System.currentTimeMillis()),
                )
            }
            .collect { emit(it) }
        failure?.let { return it }

        asm.flush().forEach { emit(it) }
        val finish = asm.finishReason
        return when {
            // EOF with neither finish_reason nor [DONE]: the connection broke.
            finish == null && !done -> Failure(LlmError.Network, asm.hasContent)
            !asm.hasAnswer && finish != "length" && finish != "content_filter" ->
                Failure(LlmError.EmptyResponse, asm.hasContent)
            else -> {
                asm.toolCalls().takeIf { it.isNotEmpty() }?.let { emit(LlmEvent.ToolCallsReady(it)) }
                emit(LlmEvent.Finished(finish, asm.usage))
                null
            }
        }
    }

    /** R7: `stream_options` / `thinking` named (quoted) in a 400 and present in the request. */
    private fun withoutRejectedField(body: ChatRequest, error: LlmError): ChatRequest? {
        if (error !is LlmError.BadRequest) return null
        val message = error.serverMessage ?: return null
        fun named(field: String) = Regex("""["'`]$field\b""").containsMatchIn(message)
        return when {
            body.streamOptions != null && named("stream_options") -> body.copy(streamOptions = null)
            body.thinking != null && named("thinking") -> body.copy(thinking = null)
            else -> null
        }
    }

    private class Failure(val error: LlmError, val partial: Boolean, val retryAfterMs: Long? = null)

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

private fun Request.Builder.auth(apiKey: String?) =
    apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }

/** `{baseUrl}/{path}`, or null when the URL is unusable or http is not allowed (SEC-5). */
internal fun LlmTarget.url(path: String): HttpUrl? {
    val base = baseUrl.trim().toHttpUrlOrNull() ?: return null
    if (!base.isHttps && !allowHttp) return null
    return base.newBuilder().addPathSegments(path).build()
}

internal fun LlmTarget.urlProblem(): String =
    if (baseUrl.trim().toHttpUrlOrNull()?.isHttps == false && !allowHttp) "http not allowed for this endpoint" else "invalid base URL"

/** Status and body text of a plain call, cancelling the call when the coroutine is cancelled. */
private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use { it.code to it.body.string() } }
            result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
    })
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
