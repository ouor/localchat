package com.ouor.localchat.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Fake OpenAI-compatible server on MockWebServer (architecture 11). `GET /models` lists [models];
 * each `POST /chat/completions` takes the next queued [Scenario]. Shared by JVM and device tests.
 *
 * ```
 * FakeOpenAiServer().use { server ->
 *     server.enqueue(Scenario.http(429, retryAfter = 1), Scenario.reasoning("hmm").text("Hi"))
 *     client.stream(LlmTarget(server.baseUrl, null, 5, allowHttp = true), request)
 * }
 * ```
 */
class FakeOpenAiServer : Closeable {

    private val server = MockWebServer()
    private val scenarios = LinkedBlockingQueue<Scenario>()

    /** Model ids returned by `/models`, DeepSeek-style extension fields omitted. */
    @Volatile
    var models: List<String> = listOf("fake-model")

    /**
     * FB4 E-1: a chat request with a `user` message right after a `tool` one gets Mistral's 400 (vLLM's
     * mistral_common check raises the same), without taking a scenario.
     */
    @Volatile
    var rejectUserAfterTool = false

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/models") -> json(200, modelsBody())
                    path.endsWith("/chat/completions") && rejectUserAfterTool && userAfterTool(request) ->
                        json(400, errorBody("Unexpected role 'user' after role 'tool'"))
                    path.endsWith("/chat/completions") ->
                        scenarios.poll()?.response(request) ?: json(500, errorBody("no scenario queued"))
                    else -> json(404, errorBody("unknown path $path"))
                }
            }
        }
        server.start()
    }

    /** Base URL like a user would register it, e.g. `http://127.0.0.1:1234/v1`. */
    val baseUrl: String get() = server.url("/v1").toString()

    fun enqueue(vararg scenario: Scenario) {
        scenarios.addAll(scenario)
    }

    /** Next recorded request (any path); fails the test after [timeoutSec]. */
    fun takeRequest(timeoutSec: Long = 5): RecordedRequest =
        requireNotNull(server.takeRequest(timeoutSec, TimeUnit.SECONDS)) { "no request within ${timeoutSec}s" }

    override fun close() = server.close()

    private fun userAfterTool(request: RecordedRequest): Boolean {
        val messages = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject["messages"]?.jsonArray.orEmpty()
        return messages.map { it.jsonObject["role"]?.jsonPrimitive?.content }.zipWithNext().any { (a, b) -> a == "tool" && b == "user" }
    }

    private fun modelsBody() = buildJsonObject {
        put("object", "list")
        putJsonArray("data") { models.forEach { addJsonObject { put("id", it); put("object", "model") } } }
    }
}

/**
 * One scripted reply. Builders append chunks in call order; the last chunk carries finish_reason
 * ("tool_calls" when a tool call was added, otherwise "stop") and usage, then `data: [DONE]`.
 */
class Scenario private constructor(
    private val httpCode: Int = 200,
    private val retryAfterSec: Int? = null,
    private val rawSse: String? = null,
    private val errorMessage: String? = null,
) {
    /** Built when answered: a tool call's arguments may pick refs from the request (see [ref]). */
    private val deltas = mutableListOf<(RecordedRequest) -> List<JsonObject>>()
    private var toolCalls = 0
    private var finishReason: String? = null
    private var dropAfter: Int? = null

    fun reasoning(text: String, field: String = "reasoning_content") = apply {
        deltas += { listOf(buildJsonObject { put(field, text) }) }
    }

    fun text(text: String) = apply { deltas += { listOf(buildJsonObject { put("content", text) }) } }

    /**
     * First delta carries id and name; arguments arrive in two pieces to exercise assembly. A [ref] placeholder in
     * [argumentsJson] becomes the ref the newest tool result lists for that line, as a model reading the list would.
     */
    fun toolCall(name: String, argumentsJson: String, id: String = "call_$toolCalls") = apply {
        val index = toolCalls++
        deltas += { request ->
            val args = REF.replace(argumentsJson) { m -> pickRef(request, String(Base64.getDecoder().decode(m.groupValues[1]))) }
            val half = args.length / 2
            listOf(toolDelta(index, id, name, "")) + listOf(args.take(half), args.drop(half)).map { toolDelta(index, null, null, it) }
        }
    }

    fun finish(reason: String) = apply { finishReason = reason }

    /** Sends only the first [chunks] chunks, then closes the connection without finish or [DONE]. */
    fun dropAfter(chunks: Int) = apply { dropAfter = chunks }

    internal fun response(request: RecordedRequest): MockResponse {
        if (httpCode != 200) {
            return MockResponse.Builder().code(httpCode)
                .setHeader("Content-Type", "application/json")
                .apply { retryAfterSec?.let { setHeader("Retry-After", it) } }
                .body(errorBody(errorMessage ?: "fake error $httpCode").toString())
                .build()
        }
        val events = rawSse?.let { listOf(it) } ?: chunks(request).map { "data: $it\n\n" }.let { all ->
            dropAfter?.let { all.take(it) } ?: (all + "data: [DONE]\n\n")
        }
        return MockResponse.Builder()
            .setHeader("Content-Type", "text/event-stream")
            .body(events.joinToString(""))
            .apply { if (dropAfter != null) onResponseEnd(SocketEffect.ShutdownConnection) }
            .build()
    }

    private fun chunks(request: RecordedRequest): List<JsonObject> {
        val finish = finishReason ?: if (toolCalls > 0) "tool_calls" else "stop"
        val body = deltas.flatMap { it(request) }.map { chunk(it, finishReason = null, usage = false) }
        return body + chunk(buildJsonObject { put("content", "") }, finish, usage = true)
    }

    private fun chunk(delta: JsonObject, finishReason: String?, usage: Boolean) = buildJsonObject {
        put("id", "fake")
        put("object", "chat.completion.chunk")
        put("model", "fake-model")
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                put("delta", delta)
                put("finish_reason", finishReason)
            }
        }
        // DeepSeek sends usage on the finishing choice chunk (architecture 0).
        if (usage) {
            putJsonObject("usage") { put("prompt_tokens", 10); put("completion_tokens", 5); put("total_tokens", 15) }
        }
    }

    private fun toolDelta(index: Int, id: String?, name: String?, arguments: String) = buildJsonObject {
        putJsonArray("tool_calls") {
            addJsonObject {
                put("index", index)
                if (id != null) {
                    put("id", id)
                    put("type", "function")
                }
                putJsonObject("function") {
                    if (name != null) put("name", name)
                    put("arguments", arguments)
                }
            }
        }
    }

    companion object {
        private val REF = Regex("@ref:([A-Za-z0-9+/=]+)@")

        /**
         * A ref placeholder for tool arguments: the ref of the first element line containing [line] (e.g.
         * `button "Go"`) in the newest tool result of the request that this scenario answers.
         */
        fun ref(line: String): String = "@ref:" + Base64.getEncoder().encodeToString(line.toByteArray()) + "@"

        private fun pickRef(request: RecordedRequest, line: String): String {
            val messages = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject["messages"]?.jsonArray.orEmpty()
            val results = messages.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "tool" }
                .mapNotNull { it["content"]?.jsonPrimitive?.content }
            val ref = Regex("\\[ref=(e\\d+)]")
            return results.asReversed().firstNotNullOfOrNull { r ->
                r.lineSequence().filter { line in it }.firstNotNullOfOrNull { ref.find(it)?.groupValues?.get(1) }
            }
                ?: error("no ref for '$line' in the newest tool results")
        }

        fun text(text: String) = Scenario().text(text)
        fun reasoning(text: String) = Scenario().reasoning(text)
        fun toolCall(name: String, argumentsJson: String) = Scenario().toolCall(name, argumentsJson)

        /** Non-200 response with an OpenAI error body; [retryAfter] in seconds. */
        fun http(code: Int, retryAfter: Int? = null, message: String? = null) =
            Scenario(httpCode = code, retryAfterSec = retryAfter, errorMessage = message)

        /** Replays a recorded stream verbatim (e.g. fixtures/deepseek-flash-reasoning-toolcall.sse). */
        fun raw(sse: String) = Scenario(rawSse = sse)
    }
}

private fun json(code: Int, body: JsonObject) = MockResponse.Builder()
    .code(code)
    .setHeader("Content-Type", "application/json")
    .body(body.toString())
    .build()

private fun errorBody(message: String) = buildJsonObject {
    putJsonObject("error") { put("message", message); put("type", "fake_error"); put("code", null as String?) }
}
