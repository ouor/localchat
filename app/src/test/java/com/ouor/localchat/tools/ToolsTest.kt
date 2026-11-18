package com.ouor.localchat.tools

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.safety.ElementInfo
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.core.model.ToolStatus
import com.ouor.localchat.tools.browser.Browse
import com.ouor.localchat.tools.browser.TypeRules
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolsTest {

    private fun caps(tools: Boolean = true, vision: Boolean = false) = ModelCapabilities(
        toolCalling = Sourced(tools, CapSource.DEFAULT),
        imageInput = Sourced(vision, CapSource.DEFAULT),
        reasoning = Sourced(null, CapSource.UNKNOWN),
        contextWindow = Sourced(null, CapSource.UNKNOWN),
        maxOutputTokens = Sourced(null, CapSource.UNKNOWN),
    )

    private fun names(caps: ModelCapabilities, search: Boolean, browser: Boolean) =
        ToolExposure.exposedTools(caps, search, browser).map { it.name }.toSet()

    // --- exposure (architecture 6.3, tools-redesign 5-1)

    @Test
    fun `catalog has the 7 tools`() {
        assertEquals(listOf("web_search", "read", "browse", "click", "type", "execute", "request_handoff"), ToolCatalog.ALL.map { it.name })
        assertEquals(ApprovalMode.ALWAYS, ToolCatalog.byName("execute")!!.approval)
        assertNull(ToolCatalog.byName("page_text")) // an old chat's call is a plain step, never a tool
    }

    @Test
    fun `listed tools add the history's tools to the exposed ones, once each, known names only`() {
        // FB3 QA M1: browser used earlier, now only search is on.
        val exposed = ToolExposure.exposedTools(caps(), searchOn = true, browserOn = false)
        val listed = ToolExposure.listedTools(exposed, listOf("click", "web_search", "click", "snapshot"), caps())
        assertEquals(exposed.map { it.name } + "click", listed.map { it.name })
        assertEquals(listOf("browse"), ToolExposure.listedTools(emptyList(), listOf("browse", "navigate"), caps()).map { it.name })
        assertTrue(ToolExposure.listedTools(emptyList(), listOf("browse"), caps(tools = false)).isEmpty())
    }

    @Test
    fun `exposure per mode is 0, 2, 6 and 7, vision or not`() {
        assertTrue(names(caps(tools = false), search = true, browser = true).isEmpty())
        assertTrue(names(caps(), search = false, browser = false).isEmpty())
        assertEquals(setOf("web_search", "read"), names(caps(), search = true, browser = false))
        assertEquals(setOf("read", "browse", "click", "type", "execute", "request_handoff"), names(caps(), search = false, browser = true))
        assertEquals(7, names(caps(), search = true, browser = true).size)
        assertEquals(7, names(caps(vision = true), search = true, browser = true).size)
    }

    // --- schema DSL and argument readers

    @Test
    fun `params builds an object schema with required list`() {
        val s = params {
            string("url", "u", required = true)
            string("text", "t")
        }
        assertEquals("object", s["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("url"), s["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("string", s["properties"]!!.jsonObject["text"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(params {}["required"])
    }

    @Test
    fun `string readers take unquoted values as text and reject objects`() {
        val a = buildJsonObject { put("s", 5); put("o", buildJsonObject { put("k", 1) }); put("blank", " ") }
        assertEquals("5", a.optString("s"))
        assertNull(a.optString("missing"))
        expectToolError("single value") { a.optString("o") }
        expectToolError("Missing required argument 'x'") { a.string("x") }
        expectToolError("Missing required argument 'blank'") { a.string("blank") }
    }

    // --- tool argument errors happen before any browser access

    private fun context(exposed: Set<String>) = object : ToolContext {
        override val sessionId = "s1"
        override val browser: BrowserEngine get() = throw AssertionError("browser used")
        override val contextWindow: Int? = null
        override val vision = false
        override val exposed = exposed
        override suspend fun workTab(navigating: Boolean): BrowserTab = throw AssertionError("workTab used")
        override suspend fun <T> hiddenTab(url: String, block: suspend (BrowserTab) -> T): T = throw AssertionError("hiddenTab used")
        override fun status(s: ToolStatus?, engine: String?) = Unit
        override val readCursors = HashMap<String, ReadCursor>()
        override fun markVisited(urls: Collection<String>) = Unit
        override fun checkApprovedPage(tab: BrowserTab) = Unit
        override suspend fun requireApproval(request: ApprovalRequest) = throw AssertionError("approval asked")
        override fun numberSource(url: String, title: String, snippet: String?) = 1
        override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = "id"
    }

    private val noBrowser = context(ToolCatalog.ALL.map { it.name }.toSet())

    private suspend fun runTool(name: String, args: JsonObject, ctx: ToolContext = noBrowser) = ToolCatalog.byName(name)!!.run(args, ctx)

    @Test
    fun `missing or invalid arguments are tool errors`() = runTest {
        val empty = JsonObject(emptyMap())
        mapOf("click" to "ref", "type" to "ref", "execute" to "code", "web_search" to "query", "request_handoff" to "reason")
            .forEach { (tool, arg) -> expectToolError("'$arg'", tool) { runTool(tool, empty) } }
        expectToolError("'text'") { runTool("type", buildJsonObject { put("ref", "e1") }) }
        expectToolError("Only http and https") { runTool("read", buildJsonObject { put("url", "javascript:alert(1)") }) }
        expectToolError("Only http and https") { runTool("browse", buildJsonObject { put("url", "file:///sdcard/x") }) }
        // Search only: read needs a url (one definition for both modes, tools-redesign R4).
        expectToolError("Pass the url to read.") { runTool("read", empty, context(setOf("web_search", "read"))) }
    }

    @Test
    fun `every tool has a description, schema and summary`() {
        ToolCatalog.ALL.forEach { t ->
            assertTrue(t.name, t.description.length > 20)
            assertEquals(t.name, "object", t.parameters["type"]!!.jsonPrimitive.content)
            t.argSummary(JsonObject(emptyMap())) // must not throw on empty args
        }
    }

    @Test
    fun `browse tells URLs from search words like the address bar`() {
        assertTrue(Browse.isUrl("example.com"))
        assertTrue(Browse.isUrl("https://a.test/x?q=1"))
        assertFalse(Browse.isUrl("kotlin coroutines"))
        assertFalse(Browse.isUrl("what is 3.5 mm"))
        assertFalse(Browse.isUrl("weather"))
        // G-m5: a path from a list is opened on the current site, not searched for.
        assertTrue(Browse.isPath("/p/123"))
        assertTrue(Browse.isPath("../up"))
        assertFalse(Browse.isPath("p/123"))
        assertFalse(Browse.isPath("/ not a path"))
    }

    // --- type's Enter (tools-redesign 3-5)

    private fun input(type: String? = "text", role: String? = "textbox", nameAttr: String? = null, name: String? = null, placeholder: String? = null, search: Boolean = false) =
        ElementInfo(tag = "input", role = role, type = type, nameAttr = nameAttr, name = name, placeholder = placeholder, search = search)

    @Test
    fun `search boxes get Enter by themselves, other fields only with a trailing newline`() {
        // agent.js decides what a search box is (info.search, shown as searchbox in the list).
        assertEquals(TypeRules.Plan("lamp", enter = true, auto = true), TypeRules.plan(input(role = "searchbox", search = true), "lamp"))
        assertEquals(TypeRules.Plan("lamp", enter = false), TypeRules.plan(input(role = "combobox", nameAttr = "q"), "lamp"))
        assertEquals(TypeRules.Plan("Kim", enter = false), TypeRules.plan(input(name = "Name"), "Kim"))
        assertEquals(TypeRules.Plan("hi", enter = true), TypeRules.plan(input(name = "Message"), "hi\n"))
        assertEquals(TypeRules.Plan("hi", enter = true), TypeRules.plan(input(search = true), "hi\n")) // asked for: no suggestion check
        // G-m2: clearing a search box submits nothing.
        assertEquals(TypeRules.Plan("", enter = false), TypeRules.plan(input(search = true), ""))
        // G-m3: multi-line fields keep the newline as text, and the result says so.
        assertEquals(TypeRules.Plan("a\nb\n", enter = false, keptNewline = true), TypeRules.plan(ElementInfo(tag = "textarea", role = "textbox"), "a\nb\n"))
        assertEquals(TypeRules.Plan("x\n", enter = false, keptNewline = true), TypeRules.plan(ElementInfo(tag = "div", role = "textbox", search = true), "x\n"))
        assertEquals(TypeRules.Plan("x", enter = false), TypeRules.plan(ElementInfo(tag = "textarea", role = "textbox"), "x"))
    }

    private inline fun expectToolError(fragment: String, context: String = "", block: () -> Unit) {
        try {
            block()
            fail("expected ToolError containing '$fragment' $context")
        } catch (e: ToolError) {
            assertTrue("'${e.message}' should contain '$fragment' $context", e.message!!.contains(fragment))
        }
    }
}
