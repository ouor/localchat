package com.ouor.localchat.ui.chat

import com.ouor.localchat.chat.caps
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.ReasoningSpec
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.core.model.Sourced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the switch row shows is exactly what a send, regenerate or edit-resend sends. */
class ComposerSwitchesTest {

    private fun reasoning(wire: ReasoningWire, efforts: List<String> = emptyList(), default: String? = null) =
        caps().copy(reasoning = Sourced(ReasoningSpec(efforts, default, wire), CapSource.DEFAULT))

    private fun sent(caps: com.ouor.localchat.core.model.ModelCapabilities?, remembered: GenOptions) =
        ComposerUiState().withSwitches(caps, remembered).options

    @Test
    fun `switches off send no search, no browser, no reasoning`() {
        assertEquals(GenOptions(), sent(caps(), GenOptions()))
    }

    @Test
    fun `switches on are sent as shown`() {
        assertEquals(GenOptions(reasoning = true, search = true, browser = true), sent(caps(), GenOptions(reasoning = true, search = true, browser = true)))
    }

    @Test
    fun `a model without tool calling shows search and browser off and disabled, and sends them off`() {
        val ui = ComposerUiState().withSwitches(caps(tools = false), GenOptions(search = true, browser = true))
        assertFalse(ui.search.on || ui.search.enabled || ui.browser.on || ui.browser.enabled)
        assertEquals(GenOptions(), ui.options)
    }

    @Test
    fun `a model whose reasoning is off shows it off and sends it off`() {
        val noReasoning = caps().copy(reasoning = Sourced(null, CapSource.USER))
        val ui = ComposerUiState().withSwitches(noReasoning, GenOptions(reasoning = true, reasoningEffort = "high"))
        assertFalse(ui.reasoning.on || ui.reasoning.enabled)
        assertEquals(GenOptions(), ui.options)
    }

    @Test
    fun `models that cannot switch reasoning off show it locked on and send it on`() {
        for (wire in listOf(ReasoningWire.EFFORT, ReasoningWire.ALWAYS_ON)) {
            val ui = ComposerUiState().withSwitches(reasoning(wire, listOf("low", "medium", "high"), "medium"), GenOptions())
            assertTrue(wire.name, ui.reasoning.on && ui.reasoning.locked && !ui.reasoning.enabled)
            assertTrue(wire.name, ui.options.reasoning)
        }
        assertEquals("medium", sent(reasoning(ReasoningWire.EFFORT, listOf("low", "medium", "high"), "medium"), GenOptions()).reasoningEffort)
    }

    @Test
    fun `DeepSeek thinking can be switched off`() {
        val ui = ComposerUiState().withSwitches(reasoning(ReasoningWire.DEEPSEEK_THINKING), GenOptions())
        assertTrue(ui.reasoning.enabled && !ui.reasoning.locked && !ui.reasoning.on)
        assertFalse(ui.options.reasoning)
    }

    @Test
    fun `an effort the model does not offer is replaced by its default`() {
        val levels = reasoning(ReasoningWire.DEEPSEEK_THINKING, listOf("high", "max"), "high")
        assertEquals("high", sent(levels, GenOptions(reasoning = true, reasoningEffort = "minimal")).reasoningEffort)
        assertEquals("max", sent(levels, GenOptions(reasoning = true, reasoningEffort = "max")).reasoningEffort)
    }
}
