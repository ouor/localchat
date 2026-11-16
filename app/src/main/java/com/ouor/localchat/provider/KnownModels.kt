package com.ouor.localchat.provider

import com.ouor.localchat.core.model.ReasoningSpec
import com.ouor.localchat.core.model.ReasoningWire
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * P-04 step 3: built-in defaults by model id pattern (architecture 3.4). A null field = no default.
 * Only DeepSeek was checked against the real server; the rest are documented estimates (R10).
 * Adding a model family = one line in [rules]. A rule with a host applies only on that API (U-1:
 * `deepseek-r1` on Ollama is a local distill without DeepSeek's tool calling or `thinking` switch).
 */
object KnownModels {

    data class Known(
        val toolCalling: Boolean?,
        val imageInput: Boolean?,
        val reasoning: ReasoningSpec?,
        val contextWindow: Int?,
        val maxOutputTokens: Int? = null,
    )

    /** `org/Qwen3-8B:q4` → `qwen3-8b`. */
    fun normalize(modelId: String): String = modelId.substringAfterLast('/').substringBefore(':').lowercase()

    /** [host] of the endpoint's base URL; null matches only rules without a host. */
    fun find(modelId: String, host: String?): Known? {
        val id = normalize(modelId)
        return rules.firstOrNull { (re, only, _) -> (only == null || only == host) && re.containsMatchIn(id) }?.third?.invoke(id)
    }

    fun hostOf(baseUrl: String?): String? = baseUrl?.trim()?.toHttpUrlOrNull()?.host

    const val DEEPSEEK_HOST = "api.deepseek.com"

    private val effort = ReasoningSpec(listOf("low", "medium", "high"), "medium", ReasoningWire.EFFORT)

    private val rules: List<Triple<Regex, String?, (String) -> Known>> = listOf(
        // Image input, efforts and window come from /models (checked 2026-11-16).
        Triple(Regex("^deepseek-"), DEEPSEEK_HOST) { _ -> Known(true, null, ReasoningSpec(emptyList(), null, ReasoningWire.DEEPSEEK_THINKING), null) },
        Triple(Regex("""^gpt-4\.1"""), null) { _ -> Known(true, true, null, 1_047_576) },
        Triple(Regex("^gpt-4o"), null) { _ -> Known(true, true, null, 128_000) },
        Triple(Regex("^gpt-5"), null) { _ -> Known(true, true, effort, 400_000) },
        Triple(Regex("^o[34]"), null) { _ -> Known(true, true, effort, 200_000) },
        Triple(Regex("""^llama-?3\.[1-3]"""), null) { id -> Known(true, "vision" in id, null, 128_000) },
        Triple(Regex("""^qwen(2\.5|3)"""), null) { id ->
            val think = if (id.startsWith("qwen3")) ReasoningSpec(emptyList(), null, ReasoningWire.ALWAYS_ON) else null
            Known(true, "-vl" in id, think, 32_768)
        },
        Triple(Regex("^gemma-?3"), null) { _ -> Known(null, true, null, 128_000) },
    )
}
