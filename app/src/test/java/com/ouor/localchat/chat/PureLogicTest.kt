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

    private val zone = java.time.ZoneId.of("UTC")

    private fun prompt(tools: Set<String>, switchable: Boolean = false, lines: List<String> = emptyList()) =
        com.ouor.localchat.chat.context.SystemPrompt.build(0, zone, null, "", tools, switchable, lines)

    @Test
    fun `system prompt marks page text untrusted and guides only the exposed tools (browser-layers 5-2)`() {
        val plain = prompt(emptySet())
        assertTrue(plain.contains("untrusted_page_content"))
        assertFalse(plain.contains("Tool use"))
        // QA-M1 / QA minor: the date is the one for "latest"; with tools off but available, the user is told the switch.
        assertTrue(plain.contains("never add a guessed year to a search query"))
        assertFalse(plain.contains("switch under the message box"))
        assertTrue(prompt(emptySet(), switchable = true).contains("Browser switch"))
        assertFalse(prompt(setOf("read"), switchable = true).contains("Browser switch"))
        val search = prompt(setOf("web_search", "read"))
        assertTrue(search.contains("web_search, then read the 2-3 best results"))
        assertFalse(search.contains("ask_user"))
        val all = prompt(ToolCatalogNames)
        assertTrue(all.contains("To use a site: open a URL") && all.contains("call ask_user"))
        assertTrue(all.contains("tell the user what it asks before doing it"))
        assertFalse(all.contains("browse(") || all.contains("request_handoff") || all.contains("already open"))
    }

    @Test
    fun `policy lines end the tool guide, only with tools, and nothing else changes`() {
        val line = "- A policy's own line."
        assertTrue(prompt(setOf("read"), lines = listOf(line)).endsWith(line + "\n"))
        assertFalse(prompt(emptySet(), lines = listOf(line)).contains(line))
        assertEquals(prompt(setOf("read")), prompt(setOf("read"), lines = listOf(line)).replace(line + "\n", ""))
    }

    private val ToolCatalogNames = com.ouor.localchat.tools.ToolCatalog.ALL.map { it.name }.toSet()

    private val empty = MessageBody.Assistant(emptyList(), ReplyStatus.STREAMING, model = null, options = GenOptions())

    private fun result(lead: String = "", page: String = "", note: String = "", isError: Boolean = false) =
        ToolResultBlock("x", 0, page, isError, lead = lead, note = note)

    private fun listPage(tab: Int, title: String, list: String) = "Page: $title — https://$title.test/ (tab $tab)\n\n$list"

    private fun headOf(lead: String, tab: Int, title: String) =
        com.ouor.localchat.tools.Untrusted.result(lead, "Page: $title — https://$title.test/ (tab $tab)\n${ToolResultTrimmer.STALE}", "")

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
    fun `per tab only the newest element list is sent whole, older ones keep their head (browser-layers 5-3)`() {
        val older = result("Opened tab 1.", listPage(1, "A", "- link \"x\" [ref=1e1]"))
        val noted = result("Clicked 1e1 → new page.", listPage(1, "A", "- link \"x\" [ref=1e1]"),
            "(3 more)\n[App note: 2 step(s) left before the user is asked whether to continue. Answer soon if you can.]")
        val newest = result("Typed \"q\" into 1e2.", listPage(1, "B", "- button \"y\" [ref=1e9]"))
        val search = result(page = "[1] r — https://r.test/")
        val out = ToolResultTrimmer.trim(listOf(Triple("1", "open", older), Triple("2", "click", noted), Triple("3", "type", newest), Triple("4", "web_search", search)))
        assertEquals(headOf("Opened tab 1.", 1, "A"), out["1"])
        assertTrue(out.getValue("2").endsWith("Answer soon if you can.]")) // the app note stays, the old list's count goes
        assertFalse(out.getValue("2").contains("[ref=1e1]") || out.getValue("2").contains("(3 more)"))
        assertEquals(newest.modelText, out["3"])
        assertEquals(search.modelText, out["4"])
        // Names outside the rules are sent as they are; an aborted call has no block.
        val big = result(page = "z".repeat(5000))
        assertEquals(big.modelText, ToolResultTrimmer.trim(listOf(Triple("1", "other", big), Triple("2", "other", big)))["1"])
        assertEquals(com.ouor.localchat.chat.context.SystemPrompt.ABORTED, ToolResultTrimmer.trim(listOf(Triple("1", "click", null)))["1"])
    }

    @Test
    fun `each open tab keeps its newest list, a closed tab's list keeps only its head`() {
        val one = result("Opened tab 1.", listPage(1, "A", "- link \"a\" [ref=1e1]"))
        val two = result("Opened tab 2.", listPage(2, "B", "- link \"b\" [ref=2e1]"))
        val both = ToolResultTrimmer.trim(listOf(Triple("1", "open", one), Triple("2", "open", two)))
        assertEquals(one.modelText, both["1"])
        assertEquals(two.modelText, both["2"])
        val closed = ToolResultTrimmer.trim(listOf(Triple("1", "open", one), Triple("2", "open", two)), openTabs = setOf(2))
        assertEquals(headOf("Opened tab 1.", 1, "A"), closed["1"])
        assertEquals(two.modelText, closed["2"])
        assertEquals(2, ToolResultTrimmer.tabOf(two.page))
    }

    @Test
    fun `the newest list is the newest result that holds one, not a later error or refusal (G-M2)`() {
        val list = result("Clicked 1e1 → new page.", listPage(1, "A", "- button \"Archive\" [ref=1e7]"), "(12 more elements: 0 above and 12 below.)")
        val denied = result(ToolOutcome.DENIED_TEXT, isError = true)
        val disabled = result("1e9 is disabled.", isError = true)
        val out = ToolResultTrimmer.trim(listOf(Triple("1", "click", list), Triple("2", "click", denied), Triple("3", "click", disabled)))
        assertEquals(list.modelText, out["1"]) // still the page the model acts on
        assertEquals(denied.modelText, out["2"])
        assertEquals(disabled.modelText, out["3"])
        // A stale-ref error carries the current list: it is the newest then.
        val stale = result("Ref 1e7 is from an earlier page of tab 1. Use a ref from the element list below.", listPage(1, "B", "- link \"x\" [ref=1e20]"), isError = true)
        val again = ToolResultTrimmer.trim(listOf(Triple("1", "click", list), Triple("2", "click", stale)))
        assertEquals(headOf("Clicked 1e1 → new page.", 1, "A"), again["1"])
        assertEquals(stale.modelText, again["2"])
        // Code that opened a new page returns its list (G-m7): that list is the newest, the click's is old.
        val ran = result("Ran the code → new page.", "Result: 1\n" + listPage(1, "C", "- link \"y\" [ref=1e30]"))
        val afterExec = ToolResultTrimmer.trim(listOf(Triple("1", "click", list), Triple("2", "execute", ran)))
        assertTrue(afterExec["1"]!!, ToolResultTrimmer.STALE in afterExec["1"]!!)
        assertEquals(ran.modelText, afterExec["2"])
        assertTrue(ToolResultTrimmer.hasList(list.page))
        assertFalse(ToolResultTrimmer.hasList("A dialog is open."))
    }

    @Test
    fun `read results stay whole within the read budget of the window, the newest always`() {
        val part = result(page = "r".repeat(12_000))
        val reads = (1..5).map { Triple("$it", "read", part) }
        // Unknown window: 40,000 characters, so the newest 3 whole.
        val out = ToolResultTrimmer.trim(reads)
        assertEquals(listOf(false, false, true, true, true), reads.map { out[it.first] == part.modelText })
        // A small window: only the newest.
        val small = ToolResultTrimmer.trim(reads, contextWindow = 8_000)
        assertEquals(listOf(false, false, false, false, true), reads.map { small[it.first] == part.modelText })
        val huge = result(page = "h".repeat(60_000))
        assertEquals(huge.modelText, ToolResultTrimmer.trim(listOf(Triple("1", "read", huge)))["1"])
        // execute: the newest two.
        val ex = (1..3).map { Triple("$it", "execute", part) }
        val exOut = ToolResultTrimmer.trim(ex)
        assertEquals(listOf(false, true, true), ex.map { exOut[it.first] == part.modelText })
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
