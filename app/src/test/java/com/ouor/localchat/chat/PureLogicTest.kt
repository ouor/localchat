package com.ouor.localchat.chat

import com.ouor.localchat.chat.context.TokenEstimate
import com.ouor.localchat.chat.context.ToolResultTrimmer
import com.ouor.localchat.chat.run.StreamCollector
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.llm.LlmEvent
import com.ouor.localchat.llm.ToolCallRequest
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.llm.wire.WireMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.ouor.localchat.tools.ToolOutcome
import org.junit.Test

class PureLogicTest {

    @Test
    fun `system prompt always marks page text untrusted and, with tools, defers sensitive sites to the engine (SE 10, SE 28, FB2 arch M1)`() {
        val zone = java.time.ZoneId.of("UTC")
        val plain = com.ouor.localchat.chat.context.SystemPrompt.build(0, zone, null, "", tools = emptySet())
        val tools = com.ouor.localchat.chat.context.SystemPrompt.build(0, zone, null, "", tools = setOf("web_search", "read"))
        assertTrue(plain.contains("untrusted_page_content"))
        assertFalse(plain.contains("Tool use"))
        // The built-in list can be off, so no fixed "closed" claim; only how to react to the engine's refusal.
        assertFalse(tools.contains("closed to you"))
        assertTrue(tools.contains("a site ${com.ouor.localchat.browser.safety.SensitiveUrls.REFUSAL}, do not try that site again"))
        assertTrue(tools.contains("tell the user what it asks before doing it"))
        // QA-M1 / QA minor: the date is the one for "latest"; with tools off but available, the user is told the switch.
        assertTrue(plain.contains("never add a guessed year to a search query"))
        assertFalse(plain.contains("switch under the message box"))
        assertTrue(com.ouor.localchat.chat.context.SystemPrompt.build(0, zone, null, "", emptySet(), switchable = true).contains("Browser switch"))
        assertFalse(com.ouor.localchat.chat.context.SystemPrompt.build(0, zone, null, "", setOf("read"), switchable = true).contains("Browser switch"))
    }

    @Test
    fun `system prompt guides only the exposed tools, and says when the chat already has a tab (tools-redesign 5-2)`() {
        val zone = java.time.ZoneId.of("UTC")
        fun build(tools: Set<String>, tab: Boolean = false) = com.ouor.localchat.chat.context.SystemPrompt.build(0, zone, null, "", tools, tab)
        val search = build(setOf("web_search", "read"))
        assertTrue(search.contains("web_search, then read the 2-3 best results"))
        assertFalse(search.contains("browse") || search.contains("request_handoff"))
        val browser = build(setOf("read", "browse", "click", "type", "execute", "request_handoff"))
        assertFalse(browser.contains("web_search"))
        assertTrue(browser.contains("To use a site: browse"))
        assertTrue(browser.contains("call request_handoff"))
        assertFalse(browser.contains("already open"))
        val both = build(ToolCatalogNames, tab = true)
        assertTrue(both.contains("web_search, then read") && both.contains("Your browser tab is already open; call browse() without url"))
        assertFalse(build(setOf("web_search", "read"), tab = true).contains("already open")) // no browse, no tab line
        assertFalse(both.contains("execute")) // moved into the tool's description
    }

    private val ToolCatalogNames = com.ouor.localchat.tools.ToolCatalog.ALL.map { it.name }.toSet()

    private val empty = MessageBody.Assistant(emptyList(), ReplyStatus.STREAMING, model = null, options = GenOptions())

    @Test
    fun `stream reducer groups deltas into blocks`() {
        val events = listOf(
            LlmEvent.ReasoningDelta("a", "reasoning_content"), LlmEvent.ReasoningDelta("b", "reasoning_content"),
            LlmEvent.TextDelta("x"), LlmEvent.TextDelta("y"),
            LlmEvent.ToolCallsReady(listOf(ToolCallRequest("c", "n", "{}"))),
            LlmEvent.Finished("content_filter", Usage(1, 2)),
        )
        var t = 0L
        val body = events.fold(empty) { b, e -> StreamCollector.reduce(b, e, step = 0, now = t++) }
        val thinking = body.blocks[0] as ThinkingBlock
        assertEquals("ab", thinking.text)
        assertEquals(2L, thinking.endedAt)
        assertEquals("xy", (body.blocks[1] as TextBlock).text)
        assertEquals("c", (body.blocks[2] as ToolCallBlock).callId)
        assertTrue(body.refusal)
        assertEquals(Usage(1, 2), body.usage)
    }

    @Test
    fun `reasoning sent in both fields by a router stays one growing thinking block`() {
        val asm = com.ouor.localchat.llm.StreamAssembler()
        var body = empty
        for (w in listOf("Let ", "me ", "think.")) {
            val chunk = com.ouor.localchat.core.AppJson.decodeFromString(
                com.ouor.localchat.llm.wire.ChatChunk.serializer(),
                """{"choices":[{"delta":{"reasoning_content":"$w","reasoning":"$w"}}]}""",
            )
            asm.accept(chunk).forEach { body = StreamCollector.reduce(body, it, step = 0, now = 0L) }
        }
        assertEquals(listOf("Let me think."), body.blocks.filterIsInstance<ThinkingBlock>().map { it.text })
    }

    @Test
    fun `trimmer cuts long results to head and tail`() {
        assertEquals("short", ToolResultTrimmer.cut("short"))
        val cut = ToolResultTrimmer.cut("a".repeat(1500) + "b".repeat(1000) + "c".repeat(500))
        assertEquals("a".repeat(1500) + "…[1000 characters omitted]…" + "c".repeat(500), cut)
    }

    @Test
    fun `only the newest element list is sent whole, older ones keep their head inside the boundary (tools-redesign 5-3)`() {
        fun wrapped(head: String, list: String) = com.ouor.localchat.chat.context.Untrusted.wrap("$head\n\n$list")
        val older = wrapped("Clicked link \"A\" → new page.\nPage: A — https://a.test/", "- link \"x\" [ref=e1]")
        val newest = wrapped("Page: B — https://b.test/", "- button \"y\" [ref=e9]")
        val withNote = older + "\n\n[App note: 2 step(s) left before the user is asked whether to continue. Answer soon if you can.]"
        val out = ToolResultTrimmer.trim(listOf(Triple("1", "browse", older), Triple("2", "click", withNote), Triple("3", "type", newest), Triple("4", "web_search", "r")))
        assertEquals(
            "<untrusted_page_content>\nClicked link \"A\" → new page.\nPage: A — https://a.test/\n${ToolResultTrimmer.STALE}\n</untrusted_page_content>",
            out["1"],
        )
        assertTrue(out.getValue("2").endsWith("Answer soon if you can.]")) // the app note stays
        assertFalse(out.getValue("2").contains("[ref=e1]"))
        assertEquals(newest, out["3"])
        assertEquals("r", out["4"])
        // Names outside the rules are sent as they are.
        val big = "z".repeat(5000)
        assertEquals(big, ToolResultTrimmer.trim(listOf(Triple("1", "other", big), Triple("2", "other", big)))["1"])
    }

    @Test
    fun `the newest list is the newest result that holds one, not a later error or refusal (G-M2)`() {
        val list = "Clicked e1 → new page.\n" + com.ouor.localchat.chat.context.Untrusted.wrap("Page: A — https://a.test/\n\n- button \"Archive\" [ref=e7]") +
            "\n(12 more elements: 0 above and 12 below the visible part. browse() with no url lists the remaining elements.)"
        val denied = ToolOutcome.DENIED_TEXT
        val disabled = "e9 is disabled."
        val pageError = com.ouor.localchat.chat.context.Untrusted.wrap("A JavaScript alert dialog is open: \"hi\".")
        val out = ToolResultTrimmer.trim(listOf(Triple("1", "click", list), Triple("2", "click", denied), Triple("3", "click", disabled), Triple("4", "type", pageError)))
        assertEquals(list, out["1"]) // still the page the model acts on
        assertEquals(denied, out["2"])
        assertEquals(disabled, out["3"])
        assertEquals(pageError, out["4"])
        // A stale-ref error carries the current list: it is the newest then.
        val stale = "Ref e7 is from an earlier page of this tab, not this one. Use a ref from the element list below.\n" +
            com.ouor.localchat.chat.context.Untrusted.wrap("Page: B — https://b.test/\n\n- link \"x\" [ref=e20]")
        val again = ToolResultTrimmer.trim(listOf(Triple("1", "click", list), Triple("2", "click", stale)))
        assertEquals(
            "Clicked e1 → new page.\n" + com.ouor.localchat.chat.context.Untrusted.wrap("Page: A — https://a.test/\n" + ToolResultTrimmer.STALE),
            again["1"], // the old list's "more elements" note goes with its list
        )
        assertEquals(stale, again["2"])
        // Code that opened a new page returns its list (G-m7): that list is the newest, the click's is old.
        val ran = "Ran the code → new page.\n" + com.ouor.localchat.chat.context.Untrusted.wrap("Result: 1\nPage: C — https://c.test/\n\n- link \"y\" [ref=e30]")
        val afterExec = ToolResultTrimmer.trim(listOf(Triple("1", "click", list), Triple("2", "execute", ran)))
        assertTrue(afterExec["1"]!!, ToolResultTrimmer.STALE in afterExec["1"]!!)
        assertEquals(ran, afterExec["2"])
        // F9: a list continued by browse() goes on from the one before it; both stay whole (same page, refs valid).
        val rest = com.ouor.localchat.chat.context.Untrusted.wrap("Page: A — https://a.test/" + com.ouor.localchat.browser.TabRules.CONTINUED + "\n\n- link \"z\" [ref=e40]")
        val continued = ToolResultTrimmer.trim(listOf(Triple("0", "browse", list), Triple("1", "browse", list), Triple("2", "browse", rest)))
        assertTrue(continued["0"]!!, ToolResultTrimmer.STALE in continued["0"]!!)
        assertEquals(list, continued["1"])
        assertEquals(rest, continued["2"])
        assertTrue(ToolResultTrimmer.hasList(list))
        assertFalse(ToolResultTrimmer.hasList(pageError))
    }

    @Test
    fun `read results stay whole within the read budget of the window, the newest always`() {
        val part = "r".repeat(12_000)
        val reads = (1..5).map { Triple("$it", "read", part) }
        // Unknown window: 40,000 characters, so the newest 3 whole.
        val out = ToolResultTrimmer.trim(reads)
        assertEquals(listOf(false, false, true, true, true), reads.map { out[it.first] == part })
        // A small window: only the newest.
        val small = ToolResultTrimmer.trim(reads, contextWindow = 8_000)
        assertEquals(listOf(false, false, false, false, true), reads.map { small[it.first] == part })
        val huge = "h".repeat(60_000)
        assertEquals(huge, ToolResultTrimmer.trim(listOf(Triple("1", "read", huge)))["1"])
        // execute: the newest two.
        val ex = (1..3).map { Triple("$it", "execute", part) }
        val exOut = ToolResultTrimmer.trim(ex)
        assertEquals(listOf(false, true, true), ex.map { exOut[it.first] == part })
    }

    @Test
    fun `title cleanup`() {
        assertEquals("Paris trip plan", TitleGenerator.clean("Title: \"Paris trip plan.\"\nmore"))
        assertEquals("파리 여행", TitleGenerator.clean("# 제목: 파리 여행"))
        assertNull(TitleGenerator.clean("  \n \"\" "))
        assertEquals(30, TitleGenerator.firstPrompt("x".repeat(50)).length)
        assertEquals("a b", TitleGenerator.firstPrompt(" a\n\n b "))
    }

    @Test
    fun `token estimate uses usage then chars`() {
        val req = ChatRequest("m", listOf(WireMessage("user", JsonPrimitive("x".repeat(350)))))
        assertEquals(100, TokenEstimate.estimate(req, emptyList(), null))
        val used = MessageNode("a", "s", null, null, Role.ASSISTANT, 10, empty.copy(usage = Usage(1000, 0), status = ReplyStatus.DONE))
        val next = MessageNode("u", "s", "a", null, Role.USER, 11, MessageBody.User("y".repeat(35)))
        assertEquals(1010, TokenEstimate.estimate(req, listOf(used, next), null))
        // Usage reported before a summary is stale.
        assertEquals(100, TokenEstimate.estimate(req, listOf(used, next), summaryAt = 10))
    }

    @Test
    fun `close marks unanswered calls`() {
        val body = empty.copy(blocks = listOf(
            ToolCallBlock("a", "n", "{}", 0, com.ouor.localchat.core.model.ToolCallState.DONE, 0),
            ToolResultBlock("a", 0, "r", false),
            ToolCallBlock("b", "n", "{}", 0, com.ouor.localchat.core.model.ToolCallState.RUNNING, 0),
        ))
        val closed = StreamCollector.close(body, inFlightCallId = "b", now = 5)
        assertEquals(ReplyStatus.STOPPED, closed.status)
        assertEquals(2, closed.blocks.filterIsInstance<ToolResultBlock>().size)
    }

    @Test
    fun `write queue keeps the newest failed write and retries`() = runTest {
        val q = WriteQueue()
        var fail = true
        val written = mutableListOf<String>()
        q.write("n") { if (fail) error("disk") else written += "old" }
        assertEquals(SaveHealth.FAILING, q.health.value)
        q.write("n") { if (fail) error("disk") else written += "new" }
        fail = false
        q.retry()
        assertEquals(listOf("new"), written)
        assertEquals(SaveHealth.OK, q.health.value)
    }
}
