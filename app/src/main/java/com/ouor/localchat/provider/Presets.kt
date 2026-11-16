package com.ouor.localchat.provider

import com.ouor.localchat.core.model.PresetId

/** P-01 presets fill name and URL only; afterwards they are ordinary endpoints. */
data class Preset(
    val id: PresetId,
    val name: String,
    val baseUrl: String,
    /** E-02 "top up" target; presets only. */
    val billingUrl: String?,
)

object Presets {
    val ALL: List<Preset> = listOf(
        Preset(PresetId.DEEPSEEK, "DeepSeek", "https://api.deepseek.com", "https://platform.deepseek.com/top_up"),
        Preset(PresetId.OPENAI, "OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/settings/organization/billing"),
        Preset(PresetId.OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/settings/credits"),
        Preset(PresetId.OLLAMA, "Ollama", "http://localhost:11434/v1", null),
        Preset(PresetId.LM_STUDIO, "LM Studio", "http://localhost:1234/v1", null),
    )

    fun of(id: PresetId): Preset = ALL.first { it.id == id }
}
