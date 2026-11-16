package com.ouor.localchat.provider

import com.ouor.localchat.core.model.ReasoningSpec
import com.ouor.localchat.core.model.ReasoningWire

/**
 * P-04 step 3: built-in defaults by model id pattern (architecture 3.4). A null field = no default.
 * Only DeepSeek was checked against the real server; the rest are documented estimates (R10).
 * Adding a model family = one line in [rules].
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

    fun find(modelId: String): Known? {
        val id = normalize(modelId)
        return rules.firstOrNull { it.first.containsMatchIn(id) }?.second?.invoke(id)
    }

    private val effort = ReasoningSpec(listOf("low", "medium", "high"), "medium", ReasoningWire.EFFORT)

    private val rules: List<Pair<Regex, (String) -> Known>> = listOf(
        // Image input, efforts and window come from /models (checked 2026-11-16).
        Regex("^deepseek-") to { _ -> Known(true, null, ReasoningSpec(emptyList(), null, ReasoningWire.DEEPSEEK_THINKING), null) },
        Regex("""^gpt-4\.1""") to { _ -> Known(true, true, null, 1_047_576) },
        Regex("^gpt-4o") to { _ -> Known(true, true, null, 128_000) },
        Regex("^gpt-5") to { _ -> Known(true, true, effort, 400_000) },
        Regex("^o[34]") to { _ -> Known(true, true, effort, 200_000) },
        Regex("""^llama-?3\.[1-3]""") to { id -> Known(true, "vision" in id, null, 128_000) },
        Regex("""^qwen(2\.5|3)""") to { id ->
            val think = if (id.startsWith("qwen3")) ReasoningSpec(emptyList(), null, ReasoningWire.ALWAYS_ON) else null
            Known(true, "-vl" in id, think, 32_768)
        },
        Regex("^gemma-?3") to { _ -> Known(null, true, null, 128_000) },
    )
}
