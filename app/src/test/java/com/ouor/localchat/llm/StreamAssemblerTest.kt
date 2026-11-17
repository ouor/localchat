package com.ouor.localchat.llm

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.Usage
import com.ouor.localchat.llm.wire.ChatChunk
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAssemblerTest {

    private fun chunk(json: String) = AppJson.decodeFromString(ChatChunk.serializer(), json)
    private fun content(text: String) = chunk("""{"choices":[{"delta":{"content":${JsonPrimitive(text)}}}]}""")

    private fun StreamAssembler.feed(vararg pieces: String): List<LlmEvent> =
        pieces.flatMap { accept(content(it)) } + flush()

    private fun List<LlmEvent>.reasoning(field: String) =
        filterIsInstance<LlmEvent.ReasoningDelta>().filter { it.field == field }.joinToString("") { it.text }

    private fun List<LlmEvent>.text() = filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }

    @Test
    fun `think tag split across chunks becomes reasoning`() {
        val events = StreamAssembler().feed("<thi", "nk>step one", " and two</th", "ink>\n\nAnswer", " here")
        assertEquals("step one and two", events.reasoning(StreamAssembler.THINK_TAG))
        assertEquals("Answer here", events.text())
    }

    @Test
    fun `plain content passes through without waiting`() {
        val asm = StreamAssembler()
        assertEquals(listOf(LlmEvent.TextDelta("Hello")), asm.accept(content("Hello")))
        assertTrue(asm.hasAnswer)
    }

    @Test
    fun `text that only looks like a tag start is released`() {
        assertEquals("<b>bold</b>", StreamAssembler().feed("<", "b>bold</b>").text())
        assertEquals("<th", StreamAssembler().feed("<th").text()) // stream ended undecided
    }

    @Test
    fun `DeepSeek tool-call markup written as text never reaches the answer, even split across chunks`() {
        // FB3 QA M1: a request without tools after tool-calling steps made DeepSeek write its calls as DSML text.
        val dsml = "<｜｜DSML｜｜calls>\n<｜｜DSML｜｜invoke name=\"page_text\">1</｜｜DSML｜｜invoke>\n</｜｜DSML｜｜calls>"
        val asm = StreamAssembler()
        val events = asm.feed("I'll visit each page.\n\n<", "｜｜DS", dsml.drop(5))
        assertEquals("I'll visit each page.", events.text().trimEnd()) // whitespace sent before the markup stays
        assertTrue(asm.hasAnswer)
        assertEquals("ok", StreamAssembler().feed("ok<｜DSML｜function_calls>x").text())
        assertEquals("a < b, <i>", StreamAssembler().feed("a <", " b, <", "i>").text())
    }

    @Test
    fun `the page-content boundary tags are removed from the answer`() {
        // FB3 QA m1.
        assertEquals(com.ouor.localchat.chat.context.Untrusted.TAG, AnswerFilter.BOUNDARY_TAG)
        val events = StreamAssembler().feed("<untrusted_pa", "ge_content>\nExample Domain\n</untrusted_page_content", ">\nThe title is X")
        assertEquals("\nExample Domain\n\nThe title is X", events.text())
    }

    @Test
    fun `unclosed think block is flushed as reasoning`() {
        val asm = StreamAssembler()
        val events = asm.feed("<think>still thinking</thi")
        assertEquals("still thinking</thi", events.reasoning(StreamAssembler.THINK_TAG))
        assertTrue(asm.hasContent)
        assertFalse(asm.hasAnswer)
    }

    @Test
    fun `think tag later in the body is left alone`() {
        assertEquals("a <think>b</think>", StreamAssembler().feed("a <think>", "b</think>").text())
    }

    @Test
    fun `reasoning fields keep their wire name and empty pieces are dropped`() {
        val asm = StreamAssembler()
        val events = asm.accept(chunk("""{"choices":[{"delta":{"reasoning_content":""}}]}""")) +
            asm.accept(chunk("""{"choices":[{"delta":{"reasoning":"r1"}}]}""")) +
            asm.accept(chunk("""{"choices":[{"delta":{"reasoning_content":"r2"}}]}"""))
        assertEquals(listOf(LlmEvent.ReasoningDelta("r1", "reasoning"), LlmEvent.ReasoningDelta("r2", "reasoning_content")), events)
    }

    @Test
    fun `tool calls by index, empty arguments become an empty object`() {
        val asm = StreamAssembler()
        asm.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"second","arguments":""}}]}}]}"""))
        asm.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"first","arguments":"{\"x\""}}]}}]}"""))
        asm.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":1}"}}]}}]}"""))
        assertEquals(
            listOf(ToolCallRequest("a", "first", """{"x":1}"""), ToolCallRequest("b", "second", "{}")),
            asm.toolCalls(),
        )
    }

    @Test
    fun `tool calls without index are grouped by id, missing id is generated`() {
        val asm = StreamAssembler()
        asm.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"id":"a","function":{"name":"f","arguments":"{"}}]}}]}"""))
        asm.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"}"}}]}}]}"""))
        asm.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"id":"b","function":{"name":"g","arguments":"{}"}}]}}]}"""))
        assertEquals(listOf("a" to "{}", "b" to "{}"), asm.toolCalls().map { it.id to it.argumentsJson })

        val noId = StreamAssembler()
        noId.accept(chunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"f"}}]}}]}"""))
        assertTrue(Regex("call_[0-9a-f]{8}").matches(noId.toolCalls().single().id))
    }

    @Test
    fun `usage from any chunk, cache hits from either field`() {
        val asm = StreamAssembler()
        asm.accept(chunk("""{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":2}}}"""))
        assertEquals(Usage(3, 4, 2), asm.usage)
        asm.accept(chunk("""{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":6,"prompt_cache_hit_tokens":1}}"""))
        assertEquals(Usage(5, 6, 1), asm.usage)
        assertEquals("stop", asm.finishReason)
    }

    @Test
    fun `refusal is an answer`() {
        val asm = StreamAssembler()
        assertEquals(listOf(LlmEvent.RefusalDelta("no")), asm.accept(chunk("""{"choices":[{"delta":{"refusal":"no"}}]}""")))
        assertTrue(asm.hasAnswer)
    }
}
