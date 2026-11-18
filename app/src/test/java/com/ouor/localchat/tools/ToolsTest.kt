package com.ouor.localchat.tools

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.script.ElementInfo
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.core.model.ToolStatus
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

    // --- the catalog (browser-layers 3-1, 3-7)

    @Test
    fun `catalog has the 12 tools with their facts`() {
        assertEquals(
            listOf("web_search", "open", "navigate", "read", "look", "click", "type", "scroll", "execute", "tabs", "close_tab", "ask_user"),
            ToolCatalog.ALL.map { it.name },
        )
        val facts = ToolCatalog.ALL.associate { it.name to it.facts }
        assertEquals(TargetRule.SEARCH, facts.getValue("web_search").target)
        assertEquals(TargetRule.OPEN, facts.getValue("open").target)
        assertEquals(TargetRule.READ, facts.getValue("read").target)
        assertEquals(setOf("navigate", "look", "execute", "ask_user", "scroll"), facts.filterValues { it.target == TargetRule.PAGE }.keys)
        assertEquals(setOf("click", "type"), facts.filterValues { it.target == TargetRule.REF }.keys)
        assertEquals(setOf("tabs", "close_tab"), facts.filterValues { it.target == TargetRule.NONE }.keys)
        assertEquals(setOf("open", "navigate", "click", "type", "scroll", "execute"), facts.filterValues { it.changesPage }.keys)
        assertTrue(facts.values.all { it.browser })
        // An old chat's call is a plain step, never a tool.
        listOf("browse", "request_handoff", "page_text").forEach { assertNull(it, ToolCatalog.byName(it)) }
    }

    @Test
    fun `listed tools add the history's tools to the exposed ones, once each, known names only`() {
        val exposed = ToolExposure.exposedTools(caps(), searchOn = true, browserOn = false)
        val listed = ToolExposure.listedTools(exposed, listOf("click", "web_search", "click", "browse"), caps())
        assertEquals(exposed.map { it.name } + "click", listed.map { it.name })
        assertTrue(ToolExposure.listedTools(emptyList(), listOf("look"), caps(tools = false)).isEmpty())
    }

    @Test
    fun `exposure per mode is 0, 2, 11 and 12, vision or not`() {
        assertTrue(names(caps(tools = false), search = true, browser = true).isEmpty())
        assertTrue(names(caps(), search = false, browser = false).isEmpty())
        assertEquals(setOf("web_search", "read"), names(caps(), search = true, browser = false))
        assertEquals(11, names(caps(), search = false, browser = true).size)
        assertFalse("web_search" in names(caps(), search = false, browser = true))
        assertEquals(12, names(caps(), search = true, browser = true).size)
        assertEquals(12, names(caps(vision = true), search = true, browser = true).size)
    }

    @Test
    fun `every tool has a description, an object schema, page tools a tab argument`() {
        ToolCatalog.ALL.forEach { t ->
            assertTrue(t.name, t.description.length in 40..600)
            assertEquals(t.name, "object", t.parameters["type"]!!.jsonPrimitive.content)
            t.argSummary(JsonObject(emptyMap())) // must not throw on empty args
            val props = t.parameters["properties"]!!.jsonObject
            if (t.facts.target == TargetRule.PAGE || t.facts.target == TargetRule.READ || t.facts.target == TargetRule.OPEN) assertTrue(t.name, "tab" in props)
            // A ref names its tab, so click and type take none (S4).
            if (t.facts.target == TargetRule.REF) assertFalse(t.name, "tab" in props)
        }
        val nav = ToolCatalog.byName("navigate")!!.parameters["properties"]!!.jsonObject["to"]!!.jsonObject
        assertEquals(listOf("back", "forward", "reload"), nav["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
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

    // --- argument errors happen before any browser access

    private val noBrowser = object : ToolContext {
        override val sessionId = "s1"
        override val browser: BrowserEngine get() = throw AssertionError("browser used")
        override val target: BrowserTab? = null
        override val chat = ChatTabs()
        override val contextWindow: Int? = null
        override val vision = false
        override val readCursors = HashMap<Int, ReadCursor>()
        override fun status(s: ToolStatus?, engine: String?) = Unit
        override fun numberSource(url: String, title: String, snippet: String?) = 1
        override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = "id"
    }

    private suspend fun runTool(name: String, args: JsonObject) = ToolCatalog.byName(name)!!.run(args, noBrowser)

    @Test
    fun `missing or invalid arguments are tool errors`() = runTest {
        val empty = JsonObject(emptyMap())
        mapOf("click" to "ref", "type" to "ref", "execute" to "code", "web_search" to "query", "open" to "url", "navigate" to "to",
            "close_tab" to "tab", "ask_user" to "task")
            .forEach { (tool, arg) -> expectToolError("'$arg'", tool) { runTool(tool, empty) } }
        expectToolError("'text'") { runTool("type", buildJsonObject { put("ref", "1e1") }) }
        expectToolError("back, forward or reload") { runTool("navigate", buildJsonObject { put("to", "home") }) }
        expectToolError("down or up") { runTool("scroll", buildJsonObject { put("direction", "left") }) }
        expectToolError("part must be") { runTool("read", buildJsonObject { put("part", "x") }) }
        expectToolError("tab number") { runTool("close_tab", buildJsonObject { put("tab", "last") }) }
        // Without a resolved tab a page tool says so (Targets gives one in the executor).
        expectToolError(Targets.NO_TAB) { runTool("ask_user", buildJsonObject { put("task", "Sign in") }) }
    }

    @Test
    fun `urls are told from search words like the address bar`() {
        assertTrue(Targets.isUrl("example.com"))
        assertTrue(Targets.isUrl("https://a.test/x?q=1"))
        assertFalse(Targets.isUrl("kotlin coroutines"))
        assertFalse(Targets.isUrl("what is 3.5 mm"))
        assertFalse(Targets.isUrl("weather"))
        assertTrue(Targets.isPath("/p/123"))
        assertTrue(Targets.isPath("../up"))
        assertFalse(Targets.isPath("p/123"))
        assertFalse(Targets.isPath("/ not a path"))
        assertEquals("https://example.com", Targets.webUrl("example.com", null))
        assertEquals("https://a.test/p/1", Targets.webUrl("/p/1", "https://a.test/x/y"))
        assertNull(Targets.webUrl("/p/1", null))
        assertNull(Targets.webUrl("javascript:alert(1)", null))
        assertNull(Targets.webUrl("file:///sdcard/x", null))
        assertNull(Targets.webUrl("kotlin coroutines", null))
    }

    // --- type's Enter

    private fun input(type: String? = "text", role: String? = "textbox", nameAttr: String? = null, name: String? = null, placeholder: String? = null, search: Boolean = false) =
        ElementInfo(tag = "input", role = role, type = type, nameAttr = nameAttr, name = name, placeholder = placeholder, search = search)

    @Test
    fun `search boxes get Enter by themselves, other fields only with a trailing newline`() {
        assertEquals(TypeRules.Plan("lamp", enter = true, auto = true), TypeRules.plan(input(role = "searchbox", search = true), "lamp"))
        assertEquals(TypeRules.Plan("lamp", enter = false), TypeRules.plan(input(role = "combobox", nameAttr = "q"), "lamp"))
        assertEquals(TypeRules.Plan("Kim", enter = false), TypeRules.plan(input(name = "Name"), "Kim"))
        assertEquals(TypeRules.Plan("hi", enter = true), TypeRules.plan(input(name = "Message"), "hi\n"))
        assertEquals(TypeRules.Plan("hi", enter = true), TypeRules.plan(input(search = true), "hi\n"))
        assertEquals(TypeRules.Plan("", enter = false), TypeRules.plan(input(search = true), ""))
        assertEquals(TypeRules.Plan("a\nb\n", enter = false, keptNewline = true), TypeRules.plan(ElementInfo(tag = "textarea", role = "textbox"), "a\nb\n"))
        assertEquals(TypeRules.Plan("x", enter = false), TypeRules.plan(ElementInfo(tag = "textarea", role = "textbox"), "x"))
        // A [secret] field takes text like any other.
        assertEquals(TypeRules.Plan("pw", enter = false), TypeRules.plan(input(type = "password"), "pw"))
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
