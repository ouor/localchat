package com.ouor.localchat.chat

import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.Sourced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tools the model sees (architecture 6.3). */
class ToolExposureTest {

    private fun caps(tools: Boolean = true, vision: Boolean = false) = ModelCapabilities(
        toolCalling = Sourced(tools, CapSource.DEFAULT),
        imageInput = Sourced(vision, CapSource.DEFAULT),
        reasoning = Sourced(null, CapSource.UNKNOWN),
        contextWindow = Sourced(null, CapSource.UNKNOWN),
        maxOutputTokens = Sourced(null, CapSource.UNKNOWN),
    )

    private fun names(caps: ModelCapabilities, search: Boolean, browser: Boolean) =
        ToolExposure.exposedTools(caps, search, browser).map { it.name }.toSet()

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
}
