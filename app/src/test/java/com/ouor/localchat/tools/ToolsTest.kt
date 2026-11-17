package com.ouor.localchat.tools

import android.view.KeyEvent
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.tools.browser.Keyboard
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
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

    // --- exposure (architecture 6.3, plan 3.8.1)

    @Test
    fun `catalog has 21 uniquely named tools`() {
        assertEquals(21, ToolCatalog.ALL.size)
        assertEquals(21, ToolCatalog.ALL.map { it.name }.toSet().size)
        assertEquals(ApprovalMode.ALWAYS, ToolCatalog.byName("execute")!!.approval)
    }

    @Test
    fun `listed tools add the history's tools to the exposed ones, once each, known names only`() {
        // FB3 QA M1: browser used earlier, now only search is on.
        val exposed = ToolExposure.exposedTools(caps(), searchOn = true, browserOn = false)
        val listed = ToolExposure.listedTools(exposed, listOf("click", "web_search", "click", "gone_tool"), caps())
        assertEquals(exposed.map { it.name } + "click", listed.map { it.name })
        assertEquals(listOf("navigate"), ToolExposure.listedTools(emptyList(), listOf("navigate"), caps()).map { it.name })
        assertTrue(ToolExposure.listedTools(emptyList(), listOf("navigate"), caps(tools = false)).isEmpty())
    }

    @Test
    fun `no tool calling means no tools`() {
        assertTrue(names(caps(tools = false), search = true, browser = true).isEmpty())
    }

    @Test
    fun `search and browser off means no tools, not even link reading`() {
        assertTrue(names(caps(), search = false, browser = false).isEmpty())
    }

    @Test
    fun `search alone exposes the search bundle`() {
        // Audit #11: navigate is browser-only; request_handoff stays for search-time CAPTCHAs.
        assertEquals(
            setOf("web_search", "open_tab", "page_text", "close_tab", "request_handoff"),
            names(caps(), search = true, browser = false),
        )
    }

    @Test
    fun `browser exposes 20 tools, screenshot only with vision`() {
        val noVision = names(caps(), search = false, browser = true)
        assertEquals(19, noVision.size)
        assertTrue("web_search" !in noVision && "screenshot" !in noVision)
        val vision = names(caps(vision = true), search = false, browser = true)
        assertEquals(noVision + "screenshot", vision)
        assertEquals(21, names(caps(vision = true), search = true, browser = true).size)
    }

    // --- schema DSL and argument readers

    @Test
    fun `params builds an object schema with required list`() {
        val s = params {
            string("url", "u", required = true)
            int("tabId", "t")
            string("action", "a", enum = listOf("back"))
        }
        assertEquals("object", s["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("url"), s["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val props = s["properties"]!!.jsonObject
        assertEquals("integer", props["tabId"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("back", props["action"]!!.jsonObject["enum"]!!.jsonArray[0].jsonPrimitive.content)
        assertNull(params {}["required"])
    }

    @Test
    fun `readers accept lenient values and reject wrong types`() {
        val a = buildJsonObject {
            put("n", "3"); put("f", 2.0); put("b", "true"); put("s", 5)
            putJsonArray("l") { add(JsonPrimitive("x")) }
            put("o", buildJsonObject { put("k", 1) })
            put("bad", 2.5)
        }
        assertEquals(3, a.optInt("n"))
        assertEquals(2, a.optInt("f"))
        assertEquals(true, a.optBool("b"))
        assertEquals("5", a.optString("s"))
        assertEquals(listOf("x"), a.optStringList("l"))
        assertEquals(listOf("5"), a.optStringList("s"))
        assertEquals(30_000, buildJsonObject { put("t", 99_999) }.optIntIn("t", 100..30_000))
        assertNull(a.optInt("missing"))
        expectToolError("must be an integer") { a.optInt("bad") }
        expectToolError("single value") { a.optString("o") }
        expectToolError("Missing required argument 'x'") { a.string("x") }
        expectToolError("true or false") { buildJsonObject { put("b", "yes") }.optBool("b") }
    }

    // --- tool argument errors happen before any browser access

    private val noBrowser = object : ToolContext {
        override val sessionId = "s1"
        override val browser: BrowserEngine get() = throw AssertionError("browser used")
        override suspend fun aiTab(tabIdArg: Int?): BrowserTab = throw AssertionError("aiTab used")
        override suspend fun requireApproval(request: ApprovalRequest) = throw AssertionError("approval asked")
        override fun numberSource(url: String, title: String, snippet: String?) = 1
        override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = "id"
    }

    private suspend fun runTool(name: String, args: JsonObject) = ToolCatalog.byName(name)!!.run(args, noBrowser)

    @Test
    fun `missing or invalid arguments are tool errors`() = runTest {
        val empty = JsonObject(emptyMap())
        mapOf(
            "click" to "target", "type" to "target", "hover" to "target", "select_option" to "target",
            "wait_for_selector" to "target", "execute" to "code", "web_search" to "query", "open_tab" to "url",
            "keyboard" to "key", "request_handoff" to "reason", "dialog_handle" to "accept",
        ).forEach { (tool, arg) -> expectToolError("'$arg'", tool) { runTool(tool, empty) } }
        expectToolError("'text'") { runTool("type", buildJsonObject { put("target", "e1") }) }
        expectToolError("'values'") { runTool("select_option", buildJsonObject { put("target", "e1") }) }
        expectToolError("Pass a url") { runTool("navigate", empty) }
        expectToolError("Only http and https") { runTool("open_tab", buildJsonObject { put("url", "javascript:alert(1)") }) }
        expectToolError("Only http and https") { runTool("navigate", buildJsonObject { put("url", "file:///sdcard/x") }) }
        expectToolError("Unknown key") { runTool("keyboard", buildJsonObject { put("key", "Hyper+Q") }) }
        expectToolError("direction must be") { runTool("scroll", buildJsonObject { put("direction", "sideways") }) }
        expectToolError("state must be") { runTool("wait_for_selector", buildJsonObject { put("target", "a"); put("state", "gone") }) }
    }

    @Test
    fun `every tool has a description, schema and summary`() {
        ToolCatalog.ALL.forEach { t ->
            assertTrue(t.name, t.description.length > 20)
            assertEquals(t.name, "object", t.parameters["type"]!!.jsonPrimitive.content)
            t.argSummary(JsonObject(emptyMap())) // must not throw on empty args
        }
    }

    // --- keyboard spec

    @Test
    fun `key specs parse to codes and meta`() {
        assertEquals(KeyEvent.KEYCODE_ENTER to 0, Keyboard.parse("Enter"))
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN to 0, Keyboard.parse("ArrowDown"))
        assertEquals(KeyEvent.KEYCODE_A, Keyboard.parse("Control+a")!!.first)
        assertTrue(Keyboard.parse("Ctrl+Shift+A")!!.second and KeyEvent.META_SHIFT_ON != 0)
        assertEquals(KeyEvent.KEYCODE_F5 to 0, Keyboard.parse("F5"))
        assertEquals(KeyEvent.KEYCODE_7 to 0, Keyboard.parse("7"))
        assertNull(Keyboard.parse("Hyper+Q"))
        assertNull(Keyboard.parse("F13"))
        assertNull(Keyboard.parse(""))
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
