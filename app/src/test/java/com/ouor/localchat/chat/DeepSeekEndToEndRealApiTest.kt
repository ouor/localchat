package com.ouor.localchat.chat

import com.ouor.localchat.attach.Validation
import com.ouor.localchat.attach.ValidationIssue
import com.ouor.localchat.chat.context.SystemPrompt
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.chat.run.RunRegistry
import com.ouor.localchat.chat.run.StartupRecovery
import com.ouor.localchat.chat.run.TurnRunner
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.PresetId
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.llm.LlmEvent
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.llm.wire.Thinking
import com.ouor.localchat.provider.EndpointDraft
import com.ouor.localchat.provider.FakeProviderDao
import com.ouor.localchat.provider.ProviderRepository
import com.ouor.localchat.storage.SecretEntries
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.imageio.ImageIO

/**
 * RealApi (Phase 2.2): the whole ChatEngine → TurnRunner → ContextAssembler → ChatCompletionsClient
 * path against api.deepseek.com with both models. Production ports for the model side
 * (ProviderRepository + ProviderModelPort + SecretStore), in-memory store and a fake weather tool.
 * Skipped unless `DEEPSEEK_API_KEY` and `LOCALCHAT_REAL_API=1` are set. The key is never printed.
 */
class DeepSeekEndToEndRealApiTest {

    private val key: String? = System.getenv("DEEPSEEK_API_KEY")

    @Before
    fun gate() = assumeTrue(!key.isNullOrBlank() && System.getenv("LOCALCHAT_REAL_API") == "1")

    /** No browser: answers from a fixed table. */
    private object Weather : Tool {
        var calls = 0
        override val name = "get_weather"
        override val groups = setOf(ToolGroup.LINK)
        override val description = "Current weather for a city."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
            putJsonArray("required") { add(JsonPrimitive("city")) }
        }
        override val labelRes = 0
        override fun argSummary(args: JsonObject) = ""
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
            calls++
            val city = args["city"]?.jsonPrimitive?.content.orEmpty()
            return ToolOutcome(if (city.contains("Busan", true)) "Busan: rain, 17°C" else "$city: sunny, 21°C")
        }
    }

    /** Records every streamed request as sent (same ChatRequest the client encodes). */
    private class RecordingLlm(private val inner: LlmPort) : LlmPort by inner {
        val requests = mutableListOf<ChatRequest>()
        val events = mutableListOf<LlmEvent>()
        override fun stream(target: LlmTarget, request: ChatRequest): Flow<LlmEvent> {
            requests += request
            return inner.stream(target, request).onEach { events += it }
        }
    }

    /** Breaks the response body of the next armed chat request after [limit] bytes (network drop mid-stream). */
    private class DropBody(private val limit: Long) : Interceptor {
        @Volatile var armed = false
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val response = chain.proceed(chain.request())
            if (!armed || !chain.request().url.encodedPath.endsWith("chat/completions")) return response
            armed = false
            val source = object : ForwardingSource(response.body.source()) {
                var read = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (read >= limit) throw IOException("test: connection dropped")
                    val n = super.read(sink, minOf(byteCount, limit - read))
                    if (n > 0) read += n
                    return n
                }
            }
            return response.newBuilder().body(source.buffer().asResponseBody(response.body.contentType(), -1)).build()
        }
    }

    /** Everything wired like di/, on the runBlocking thread (FakeChatStore is not thread-safe). */
    private inner class Rig(scope: CoroutineScope, http: OkHttpClient = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()) {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val client = ChatCompletionsClient(http, AppJson)
        val secrets = SecretStore(MemoryDataStore(SecretEntries())) { aes }
        val providers = ProviderRepository(client, FakeProviderDao(), secrets, Clock.System)
        val llm = RecordingLlm(ClientLlmPort(client))
        val models = ProviderModelPort(providers, secrets)
        val store = FakeChatStore()
        val settings = SettingsStore(MemoryDataStore(AppSettings()))
        val tools = object : ToolPort {
            override fun exposed(caps: ModelCapabilities, search: Boolean, browser: Boolean): List<Tool> =
                if (caps.toolCalling.value) listOf(Weather) else emptyList()
            override fun byName(name: String): Tool? = Weather.takeIf { it.name == name }
        }
        val images = HashMap<String, AttachmentPayload>()
        val attachments = object : AttachmentPort {
            override suspend fun payload(id: String) = images[id]
            override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = error("unused")
            override suspend fun saveText(name: String, text: String) = error("unused")
        }
        val compactor = Compactor(store, llm, Clock.System)
        val browserPort = FakeBrowserPort()
        val registry = RunRegistry(scope)
        val runner = TurnRunner(
            store, llm, models, tools, attachments, browserPort, { error("browser used") },
            settings, compactor, TitleGenerator(store, llm), LinkPrefetcher(tools, browserPort, attachments), Clock.System, scope,
        )
        val recovery = StartupRecovery(store, Clock.System)
        val engine = ChatEngine(store, registry, runner, recovery, models, tools, compactor, settings, Clock.System)

        suspend fun endpoint(name: String, apiKey: String? = key, baseUrl: String = "https://api.deepseek.com", maxRetries: Int = 2): String {
            val id = providers.add(EndpointDraft(name, baseUrl, apiKey?.let(::ApiKey), PresetId.DEEPSEEK, allowHttp = false, maxRetries = maxRetries))
            providers.refreshModels(id)
            return id
        }

        suspend fun ref(endpointId: String, modelId: String): ModelRef {
            if (providers.models(endpointId).first().none { it.modelId == modelId }) providers.addManualModel(endpointId, modelId)
            return providers.models(endpointId).first().first { it.modelId == modelId }.ref
        }

        suspend fun send(sessionId: String?, text: String, model: ModelRef, options: GenOptions = GenOptions(), attachmentIds: List<String> = emptyList()): String {
            val sid = engine.send(sessionId, Draft(Draft.NEW_CHAT, text, attachmentIds, 0), options, model)
            awaitIdle(sid)
            return sid
        }

        suspend fun awaitIdle(sid: String) = withTimeout(180_000) { engine.runs.first { sid !in it } }

        fun reply(sid: String) = store.messages.values.last { it.sessionId == sid && it.body is MessageBody.Assistant }.body as MessageBody.Assistant
        fun MessageBody.Assistant.text() = blocks.filterIsInstance<TextBlock>().joinToString("") { it.text }
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking {
        val rig = Rig(this)
        rig.recovery.run()
        rig.block()
    }

    @Test
    fun `P-04 server values first, text-only model never gets the image`() = rig {
        val ep = endpoint("DeepSeek")
        val byId = providers.models(ep).first().associateBy { it.modelId }
        val flash = byId.getValue("deepseek-flash")
        val pro = byId.getValue("deepseek-v4-pro")
        assertEquals(CapSource.SERVER, flash.caps.imageInput.source)
        assertTrue(flash.caps.imageInput.value)
        assertEquals(CapSource.SERVER, pro.caps.imageInput.source)
        assertFalse(pro.caps.imageInput.value)
        assertEquals(CapSource.SERVER, pro.caps.contextWindow.source)
        assertEquals(CapSource.SERVER, pro.caps.reasoning.source)
        assertEquals(CapSource.DEFAULT, pro.caps.toolCalling.source) // no server field: known models

        val image = Attachment("img1", "red.png", "image/png", 100, AttachmentKind.IMAGE, PrepState.Ready, hasOriginal = true, createdAt = 0)
        images["img1"] = AttachmentPayload(image, imageDataUrl = redPng())
        // I-03 is the only guard (architecture 0: the server silently ignores images).
        assertEquals(listOf(ValidationIssue.Kind.IMAGES_UNSUPPORTED), Validation.check(listOf(image), pro.caps).issues.map { it.kind })
        assertTrue(Validation.check(listOf(image), flash.caps).ok)

        // If it is sent anyway, the request carries the text placeholder, not the image.
        val sid = send(null, "Is there an attachment? Answer in one short sentence.", pro.ref, attachmentIds = listOf("img1"))
        assertEquals(ReplyStatus.DONE, reply(sid).status)
        val proUser = llm.requests.last().messages.last { it.role == "user" }.content.toString()
        assertFalse(proUser.contains("image_url"))
        assertTrue(proUser.contains("red.png"))

        val sid2 = send(null, "What single color fills this image? One word.", flash.ref, attachmentIds = listOf("img1"))
        assertEquals(ReplyStatus.DONE, reply(sid2).status)
        assertTrue((llm.requests.last().messages.last { it.role == "user" }.content as JsonArray).toString().contains("image_url"))
        assertTrue(reply(sid2).text(), reply(sid2).text().contains("red", ignoreCase = true))
    }

    @Test
    fun `P-05 P-06 Q5 two endpoints, model switch mid-chat, recent use recorded`() = rig {
        val a = endpoint("DeepSeek A")
        val b = endpoint("DeepSeek B")
        val visible = providers.allVisibleModels.first()
        assertEquals(4, visible.size)
        assertEquals(setOf(a, b), visible.map { it.endpointId }.toSet())

        val sid = send(null, "Remember the number 7341. Reply only OK.", ref(a, "deepseek-flash"))
        assertEquals(ReplyStatus.DONE, reply(sid).status)
        send(sid, "Which number did I ask you to remember? Digits only.", ref(b, "deepseek-v4-pro"))
        val second = reply(sid)
        assertEquals(ReplyStatus.DONE, second.status)
        assertTrue(second.text(), second.text().contains("7341"))
        assertEquals("deepseek-v4-pro", second.model?.modelId)
        assertEquals(b, store.sessions.getValue(sid).lastModel?.endpointId)

        val used = (providers.models(a).first() + providers.models(b).first()).associate { (it.endpointId to it.modelId) to it.lastUsedAt }
        assertNotNull(used[a to "deepseek-flash"])
        assertNotNull(used[b to "deepseek-v4-pro"])
        assertNull(used[a to "deepseek-v4-pro"])
        assertNull(used[b to "deepseek-flash"])
    }

    @Test
    fun `M-08 reasoning off sends thinking disabled, on streams reasoning`() = rig {
        val ep = endpoint("DeepSeek")
        for (model in listOf("deepseek-flash", "deepseek-v4-pro")) {
            val off = send(null, "Say hi.", ref(ep, model), GenOptions(reasoning = false))
            assertEquals(Thinking.DISABLED, llm.requests.last().thinking)
            assertTrue(AppJson.encodeToString(ChatRequest.serializer(), llm.requests.last()).contains(""""thinking":{"type":"disabled"}"""))
            assertTrue(reply(off).blocks.none { it is ThinkingBlock })

            // No effort chosen: the server default from /models ("high") is sent. With tools in the request the server
            // sometimes skips thinking on trivial questions, so ask something that needs a few steps.
            val on = send(null, "Compute 37*43 - 19*28. Answer with the number only.", ref(ep, model), GenOptions(reasoning = true))
            assertEquals(Thinking.ENABLED, llm.requests.last().thinking)
            assertEquals("high", llm.requests.last().reasoningEffort)
            assertTrue(
                "$model blocks=" + reply(on).blocks.map { it::class.simpleName } + " events=" + llm.events.takeLast(5),
                reply(on).blocks.any { it is ThinkingBlock && it.wireField == "reasoning_content" },
            )
            assertTrue(reply(on).text(), reply(on).text().contains("1059"))
        }
    }

    @Test
    fun `A-01 reasoning plus tool calls over several steps and turns without 400`() = rig {
        val ep = endpoint("DeepSeek")
        for (model in listOf("deepseek-flash", "deepseek-v4-pro")) {
            val before = Weather.calls
            val options = GenOptions(reasoning = true, reasoningEffort = "low")
            val sid = send(null, "Use get_weather for Seoul, then tell me the temperature.", ref(ep, model), options)
            val first = reply(sid)
            assertNull(first.error)
            assertEquals(ReplyStatus.DONE, first.status)
            assertTrue(Weather.calls > before)
            assertTrue(first.steps >= 2)
            assertTrue(first.blocks.any { it is ToolResultBlock })
            assertTrue(first.text(), first.text().contains("21"))
            // The tool-calling step's reasoning went back in the same turn (architecture 0 rule).
            val stepTwo = llm.requests.last { r -> r.messages.any { it.role == "tool" } }
            // With effort "low" the model sometimes calls the tool without thinking first; then nothing is due.
            val thoughtBeforeCall = first.blocks.any { it is ThinkingBlock && it.step == 0 && it.text.isNotEmpty() }
            assertEquals(
                "$model: " + stepTwo.messages.map { "${it.role}/${it.toolCalls?.size}/${it.reasoningContent?.length}" },
                thoughtBeforeCall,
                stepTwo.messages.any { it.role == "assistant" && it.toolCalls != null && it.reasoningContent != null },
            )

            // Next turn: previous turn's reasoning is dropped and that is accepted.
            send(sid, "And Busan? Use the tool again.", ref(ep, model), options)
            val second = reply(sid)
            assertNull(second.error)
            assertEquals(ReplyStatus.DONE, second.status)
            assertTrue(second.text(), second.text().contains("17"))
        }
    }

    @Test
    fun `A-06 small context window forces compaction`() = rig {
        val ep = endpoint("DeepSeek")
        val flash = ref(ep, "deepseek-flash")
        var sid: String? = null
        for (q in listOf("My cat is called Miso. Reply OK.", "My dog is called Bori. Reply OK.", "I live in Daegu. Reply OK.")) {
            sid = send(sid, q, flash)
        }
        providers.updateModel(ep, "deepseek-flash", ModelOverrides(contextWindow = 200))
        send(sid, "What is my cat's name? One word.", flash)
        assertEquals(1, store.summaryList.size)
        val last = reply(sid!!)
        assertEquals(ReplyStatus.DONE, last.status)
        val sent = llm.requests.last().messages
        assertTrue(sent.any { it.role == "user" && it.content.toString().contains(SystemPrompt.SUMMARY_PREFIX.trim()) })
        assertTrue(last.text(), last.text().contains("Miso", ignoreCase = true))
    }

    @Test
    fun `A-07 usage from the finish chunk is stored on the reply for the gauge`() = rig {
        val ep = endpoint("DeepSeek")
        val sid = send(null, "Say hi in one word.", ref(ep, "deepseek-flash"))
        val usage = reply(sid).usage
        assertNotNull(usage)
        assertTrue("$usage", usage!!.promptTokens > 0 && usage.completionTokens > 0)
        assertEquals(true, llm.requests.last().streamOptions?.includeUsage)
    }

    @Test
    fun `E-01 wrong key is AUTH without retry`() = rig {
        val ep = endpoint("Bad key", apiKey = "sk-invalid-for-test")
        val sid = send(null, "hi", ref(ep, "deepseek-flash"))
        val r = reply(sid)
        assertEquals(ReplyStatus.ERROR, r.status)
        assertEquals(ChatErrorKind.AUTH, r.error?.kind)
        assertEquals(1, llm.requests.size)
        assertTrue(llm.events.none { it is LlmEvent.Retrying })
    }

    @Test
    fun `unknown model is BAD_REQUEST`() = rig {
        val ep = endpoint("DeepSeek")
        val sid = send(null, "hi", ref(ep, "deepseek-no-such-model"))
        val r = reply(sid)
        assertEquals(ReplyStatus.ERROR, r.status)
        assertEquals(ChatErrorKind.BAD_REQUEST, r.error?.kind)
    }

    @Test
    fun `M-02 stop keeps the partial reply within a second`() = rig {
        val ep = endpoint("DeepSeek")
        val sid = engine.send(null, Draft(Draft.NEW_CHAT, "Write 60 numbered lines about the sea.", emptyList(), 0), GenOptions(), ref(ep, "deepseek-flash"))
        withTimeout(60_000) {
            engine.runs.first { runs -> (runs[sid]?.live?.body as? MessageBody.Assistant)?.blocks?.any { it is TextBlock } == true }
        }
        val t0 = System.nanoTime()
        engine.stop(sid)
        awaitIdle(sid)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
        val r = reply(sid)
        assertEquals(ReplyStatus.STOPPED, r.status)
        assertTrue(r.text().isNotEmpty())
    }

    @Test
    fun `E-03 network drop mid-stream keeps content, no retry`() = runBlocking {
        val drop = DropBody(limit = 3_000)
        val http = OkHttpClient.Builder().addInterceptor(drop).build()
        with(Rig(this, http)) {
            recovery.run()
            val ep = endpoint("DeepSeek")
            drop.armed = true
            val sid = send(null, "Write 40 numbered lines about mountains.", ref(ep, "deepseek-flash"))
            val r = reply(sid)
            assertEquals(ReplyStatus.ERROR, r.status)
            assertEquals(ChatErrorKind.NETWORK, r.error?.kind)
            assertTrue(r.text().isNotEmpty())
            assertTrue(llm.events.none { it is LlmEvent.Retrying })
        }
    }

    @Test
    fun `E-03 unreachable server is retried then NETWORK`() = rig {
        val ep = endpoint("Down", baseUrl = "https://127.0.0.1:1", maxRetries = 1)
        val sid = send(null, "hi", ref(ep, "deepseek-flash"))
        val r = reply(sid)
        assertEquals(ReplyStatus.ERROR, r.status)
        assertEquals(ChatErrorKind.NETWORK, r.error?.kind)
        assertEquals(1, llm.events.count { it is LlmEvent.Retrying })
    }

    private fun redPng(): String {
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 32) for (y in 0 until 32) img.setRGB(x, y, 0xFF0000)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
