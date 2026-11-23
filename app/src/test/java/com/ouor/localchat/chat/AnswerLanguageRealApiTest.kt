package com.ouor.localchat.chat

import com.ouor.localchat.agent.FakeBrowserPort
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.storage.SettingsStore
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * FB4 E-3 measurement, not a pass/fail check: the v1.0.1 QA D1 run (English question, browser read of English
 * pages, tools switched off after two calls) against api.deepseek.com, [RUNS] times, printing the answer's script.
 * Skipped unless `DEEPSEEK_API_KEY` and `LOCALCHAT_REAL_API=1` are set. The key is never printed.
 */
class AnswerLanguageRealApiTest {

    private val key: String? = System.getenv("DEEPSEEK_API_KEY")
    private val runs = System.getenv("LOCALCHAT_LANG_RUNS")?.toIntOrNull() ?: 12

    private class Page(private val off: () -> Unit) : Tool {
        var calls = 0
        override val name = "read_page"
        override val groups = setOf(ToolGroup.BROWSER)
        override val description = "Open a URL in the browser and return the page text."
        override val parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("url") { put("type", "string") } }
            putJsonArray("required") { add(JsonPrimitive("url")) }
        }
        override val facts = com.ouor.localchat.tools.ToolFacts(com.ouor.localchat.tools.TargetRule.NONE, browser = false, changesPage = false)
        override fun argSummary(args: JsonObject) = ""
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
            // The user switches the browser off while the second page is read (QA: +4~5 s, after 2~3 steps).
            if (++calls == 2) off()
            val city = args["url"]?.jsonPrimitive?.content.orEmpty().substringAfterLast('/')
            return ToolOutcome(PAGES[city] ?: "$city - Wikipedia\nPage not found.")
        }
    }

    @Test
    fun `answer language after the tools are switched off mid-run`() = runBlocking {
        assumeTrue(!key.isNullOrBlank() && System.getenv("LOCALCHAT_REAL_API") == "1")
        val counts = linkedMapOf<String, Int>()
        repeat(runs) { i ->
            val store = FakeChatStore()
            lateinit var engine: ChatEngine
            lateinit var sid: String
            val page = Page { engine.narrowRun(sid, GenOptions()) }
            val models = object : ModelPort {
                override suspend fun resolve(ref: ModelRef) = ResolvedModel(ref, LlmTarget("https://api.deepseek.com", key, 2, false), caps(tools = true))
                override suspend fun markUsed(ref: ModelRef) = Unit
            }
            val wired = wireEngine(
                this, store, ClientLlmPort(ChatCompletionsClient(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build(), AppJson)),
                models, FakeToolPort(listOf(page)), FakeAttachments(), FakeBrowserPort(),
                SettingsStore(MemoryDataStore(AppSettings())), com.ouor.localchat.core.Clock.System,
            )
            engine = wired.engine
            wired.recovery.run()
            val reasoning = i % 2 == 1
            withTimeout(240_000) {
                sid = engine.send(null, Draft(Draft.NEW_CHAT, QUESTION, emptyList(), 0), GenOptions(reasoning = reasoning, browser = true), MODEL)
                engine.runs.first { sid !in it }
            }
            val reply = store.messages.values.last { it.body is MessageBody.Assistant }.body as MessageBody.Assistant
            val answer = reply.blocks.filterIsInstance<TextBlock>().lastOrNull()?.text.orEmpty()
            val lang = if (reply.error != null) "error:${reply.error!!.kind}" else script(answer)
            counts[lang] = (counts[lang] ?: 0) + 1
            println("E3 run ${i + 1} reasoning=$reasoning calls=${page.calls} status=${reply.status} lang=$lang :: ${answer.take(160).replace('\n', ' ')}")
        }
        println("E3 summary $counts")
    }

    @Test
    fun `wrapping up at the step limit ends with an answer`() = runBlocking {
        // FB4 step limit: maxSteps 1, so the dialog comes after the first page; "Wrap up" is chosen.
        assumeTrue(!key.isNullOrBlank() && System.getenv("LOCALCHAT_REAL_API") == "1")
        var answered = 0
        repeat(runs) { i ->
            val store = FakeChatStore()
            val page = Page {}
            val models = object : ModelPort {
                override suspend fun resolve(ref: ModelRef) = ResolvedModel(ref, LlmTarget("https://api.deepseek.com", key, 2, false), caps(tools = true))
                override suspend fun markUsed(ref: ModelRef) = Unit
            }
            val wired = wireEngine(
                this, store, ClientLlmPort(ChatCompletionsClient(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build(), AppJson)),
                models, FakeToolPort(listOf(page)), FakeAttachments(), FakeBrowserPort(),
                SettingsStore(MemoryDataStore(AppSettings(maxSteps = 1))), com.ouor.localchat.core.Clock.System,
            )
            wired.recovery.run()
            val engine = wired.engine
            val reasoning = i % 2 == 1
            withTimeout(240_000) {
                val sid = engine.send(null, Draft(Draft.NEW_CHAT, QUESTION, emptyList(), 0), GenOptions(reasoning = reasoning, browser = true), MODEL)
                val limit = engine.runs.first { runs -> runs[sid]?.phase.let { it == null || (it as? RunPhase.Paused)?.pause is Pause.StepLimit } }
                if (sid in limit) engine.resolve(sid, Resolution.StepLimitAnswer(continueRun = false))
                engine.runs.first { sid !in it }
            }
            val reply = store.messages.values.last { it.body is MessageBody.Assistant }.body as MessageBody.Assistant
            val answer = reply.blocks.filterIsInstance<TextBlock>().filter { it.step == reply.steps - 1 }.joinToString("") { it.text }
            if (reply.status == com.ouor.localchat.core.model.ReplyStatus.DONE && answer.isNotBlank()) answered++
            println("WRAP run ${i + 1} reasoning=$reasoning calls=${page.calls} steps=${reply.steps} status=${reply.status} error=${reply.error?.kind} :: ${answer.take(200).replace('\n', ' ')}")
        }
        println("WRAP summary answered=$answered/$runs")
    }

    private companion object {
        val MODEL = ModelRef("ep", "deepseek-flash", "DeepSeek")
        const val QUESTION = "Using the browser, visit https://en.wikipedia.org/wiki/Seoul then https://en.wikipedia.org/wiki/Busan " +
            "then https://en.wikipedia.org/wiki/Incheon one page at a time, and report the population of each city. " +
            "Do not run scripts, just read the page text."
        val PAGES = mapOf(
            "Seoul" to "Seoul - Wikipedia\nSeoul, officially Seoul Special City (Korean: 서울특별시), is the capital and largest city of " +
                "South Korea. The city's population is approximately 9.6 million (9,668,465 in 2023), and the Seoul Capital " +
                "Area has about 26 million people. Seoul is located on the Han River in the northwest of the country.",
            "Busan" to "Busan - Wikipedia\nBusan (Korean: 부산), officially Busan Metropolitan City, is South Korea's second most " +
                "populous city after Seoul, with a population of over 3.3 million (3,293,362 in 2023). It is the country's largest port.",
            "Incheon" to "Incheon - Wikipedia\nIncheon (Korean: 인천), officially Incheon Metropolitan City, is a city in northwestern " +
                "South Korea with a population of about 3 million (3,014,925 in 2023).",
        )

        /** "en" when the letters are Latin, else the dominant other script. */
        fun script(text: String): String {
            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty()) return "empty"
            val byScript = letters.groupingBy { Character.UnicodeScript.of(it.code) }.eachCount()
            val top = byScript.maxBy { it.value }.key
            return if (top == Character.UnicodeScript.LATIN) "en" else top.name.lowercase()
        }
    }
}
