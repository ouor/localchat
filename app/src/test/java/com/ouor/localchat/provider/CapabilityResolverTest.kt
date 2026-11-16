package com.ouor.localchat.provider

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.ReasoningSpec
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.core.model.Sourced
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityResolverTest {

    private fun json(s: String) = AppJson.parseToJsonElement(s).jsonObject

    /** `deepseek-v4-pro` as returned by the real `/models` on 2026-11-16. */
    private val deepseekPro = json(
        """{"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek","name":"DeepSeek-V4-Pro",
           "context_window":1048576,"max_output_tokens":393216,"input_modalities":["text"],"output_modalities":["text"],
           "effort":{"supported_levels":["low","high","max"],"default_level":"high"},
           "api_capabilities":{"anthropic_messages":{"system_prompt_update":"leading-only"}}}""",
    )

    @Test
    fun `real DeepSeek entry resolves from server fields and known wire`() {
        val caps = CapabilityResolver.resolve(ModelOverrides(), deepseekPro, KnownModels.find("deepseek-v4-pro", KnownModels.DEEPSEEK_HOST))
        assertEquals(Sourced(true, CapSource.DEFAULT), caps.toolCalling)
        assertEquals(Sourced(false, CapSource.SERVER), caps.imageInput)
        assertEquals(
            Sourced(ReasoningSpec(listOf("low", "high", "max"), "high", ReasoningWire.DEEPSEEK_THINKING), CapSource.SERVER),
            caps.reasoning,
        )
        assertEquals(Sourced<Int?>(1_048_576, CapSource.SERVER), caps.contextWindow)
        assertEquals(Sourced<Int?>(393_216, CapSource.SERVER), caps.maxOutputTokens)
        assertEquals("DeepSeek-V4-Pro", CapabilityResolver.displayName("deepseek-v4-pro", ModelOverrides(), deepseekPro))
    }

    /** Every presence combination of user / server / known for the numeric and boolean rows. */
    @Test
    fun `priority is user, server, known, none for every combination`() {
        for (mask in 0 until 8) {
            val hasUser = mask and 1 != 0
            val hasServer = mask and 2 != 0
            val hasKnown = mask and 4 != 0
            val user = ModelOverrides(imageInput = if (hasUser) false else null, contextWindow = if (hasUser) 1 else null)
            val server = if (hasServer) json("""{"id":"m","input_modalities":["text","image"],"context_window":2}""") else null
            val known = if (hasKnown) KnownModels.Known(toolCalling = true, imageInput = false, reasoning = null, contextWindow = 3) else null
            val caps = CapabilityResolver.resolve(user, server, known)

            val (window, image, source) = when {
                hasUser -> Triple(1, false, CapSource.USER)
                hasServer -> Triple(2, true, CapSource.SERVER)
                hasKnown -> Triple(3, false, CapSource.DEFAULT)
                else -> Triple(null, false, CapSource.UNKNOWN)
            }
            assertEquals("mask $mask", Sourced(window, source), caps.contextWindow)
            assertEquals("mask $mask", Sourced(image, source), caps.imageInput)
            // Tool calling has no server field: user > known > false.
            assertEquals(if (hasKnown) Sourced(true, CapSource.DEFAULT) else Sourced(false, CapSource.UNKNOWN), caps.toolCalling)
        }
    }

    @Test
    fun `user can switch reasoning off or on and narrow the efforts`() {
        val known = KnownModels.find("deepseek-flash", KnownModels.DEEPSEEK_HOST)
        val off = CapabilityResolver.resolve(ModelOverrides(reasoning = false), deepseekPro, known)
        assertEquals(Sourced<ReasoningSpec?>(null, CapSource.USER), off.reasoning)

        val narrowed = CapabilityResolver.resolve(ModelOverrides(reasoningEfforts = listOf("low", "max")), deepseekPro, known)
        assertEquals(
            Sourced(ReasoningSpec(listOf("low", "max"), null, ReasoningWire.DEEPSEEK_THINKING), CapSource.USER),
            narrowed.reasoning,
        )

        val forcedOn = CapabilityResolver.resolve(ModelOverrides(reasoning = true), json("""{"id":"x"}"""), null)
        assertEquals(Sourced(ReasoningSpec(emptyList(), null, ReasoningWire.EFFORT), CapSource.USER), forcedOn.reasoning)
    }

    @Test
    fun `server reasoning of an unknown model uses EFFORT, known-only reasoning is a default`() {
        val server = json("""{"id":"x","effort":{"supported_levels":["low","high"]}}""")
        assertEquals(
            Sourced(ReasoningSpec(listOf("low", "high"), null, ReasoningWire.EFFORT), CapSource.SERVER),
            CapabilityResolver.resolve(ModelOverrides(), server, null).reasoning,
        )
        val qwen = CapabilityResolver.resolve(ModelOverrides(), null, KnownModels.find("qwen3:8b", null))
        assertEquals(CapSource.DEFAULT, qwen.reasoning.source)
        assertEquals(ReasoningWire.ALWAYS_ON, qwen.reasoning.value?.wire)
        assertEquals(Sourced<ReasoningSpec?>(null, CapSource.UNKNOWN), CapabilityResolver.resolve(ModelOverrides(), null, null).reasoning)
    }

    @Test
    fun `display name is user, then server name, then id`() {
        val server = json("""{"id":"m","name":"Server Name"}""")
        assertEquals("Mine", CapabilityResolver.displayName("m", ModelOverrides(displayName = "Mine"), server))
        assertEquals("Server Name", CapabilityResolver.displayName("m", ModelOverrides(displayName = " "), server))
        assertEquals("m", CapabilityResolver.displayName("m", ModelOverrides(), null as JsonObject?))
    }

    @Test
    fun `reasoning wire survives the user turning reasoning off`() {
        val record = ModelRecord("e", "deepseek-v4-pro", deepseekPro, inServerList = true, overrides = ModelOverrides(reasoning = false))
        val info = record.toModelInfo(KnownModels.DEEPSEEK_HOST)
        assertNull(info.caps.reasoning.value)
        assertEquals(ReasoningWire.DEEPSEEK_THINKING, info.reasoningWire)
        assertNull(ModelRecord("e", "my-local-model").toModelInfo(null).reasoningWire)
    }

    @Test
    fun `U-1 deepseek defaults apply on the DeepSeek API only`() {
        assertEquals(true, KnownModels.find("deepseek-chat", KnownModels.hostOf("https://api.deepseek.com"))?.toolCalling)
        assertNull(KnownModels.find("deepseek-r1:8b", KnownModels.hostOf("http://localhost:11434/v1")))
        assertNull(KnownModels.find("deepseek/deepseek-chat", KnownModels.hostOf("https://openrouter.ai/api/v1")))
        val local = ModelRecord("e", "deepseek-r1:8b").toModelInfo("192.168.0.5")
        assertFalse(local.caps.toolCalling.value)
        assertNull(local.caps.reasoning.value)
        assertNull(local.reasoningWire)
    }

    @Test
    fun `known model patterns`() {
        assertEquals("qwen3-8b", KnownModels.normalize("Org/Qwen3-8B:Q4_K_M"))
        assertEquals(128_000, KnownModels.find("gpt-4o-mini", null)?.contextWindow)
        assertEquals(1_047_576, KnownModels.find("openai/gpt-4.1", null)?.contextWindow)
        assertEquals(ReasoningWire.EFFORT, KnownModels.find("o4-mini", null)?.reasoning?.wire)
        assertEquals(400_000, KnownModels.find("gpt-5", null)?.contextWindow)
        assertEquals(true, KnownModels.find("llama-3.2-11b-vision", null)?.imageInput)
        assertEquals(false, KnownModels.find("llama3.1:8b", null)?.imageInput)
        assertEquals(true, KnownModels.find("qwen2.5-vl-7b", null)?.imageInput)
        assertNull(KnownModels.find("qwen2.5-7b", null)?.reasoning)
        assertNull(KnownModels.find("gemma3:4b", null)?.toolCalling)
        assertEquals(true, KnownModels.find("gemma-3-27b", null)?.imageInput)
        assertNull(KnownModels.find("mistral-small", null))
    }
}
