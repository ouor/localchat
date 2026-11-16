package com.ouor.localchat.core.model

import kotlinx.serialization.Serializable

/** An OpenAI-compatible endpoint ("provider" in the UI). The API key is never stored here. */
@Serializable
data class Endpoint(
    val id: String,
    val name: String,
    val baseUrl: String,
    val preset: PresetId?,
    val enabled: Boolean,
    /** P-09: 0..10. */
    val maxRetries: Int = 5,
    /** SEC-5: http base URLs are refused unless the user accepted the warning. */
    val allowHttp: Boolean,
    val hasKey: Boolean,
)

@Serializable
enum class PresetId { DEEPSEEK, OPENAI, OPENROUTER, OLLAMA, LM_STUDIO }

/**
 * A plaintext API key. toString() is masked so a key never lands in logs by accident (SEC-1).
 * Read [value] only where the key is actually sent or encrypted.
 */
@JvmInline
value class ApiKey(val value: String) {
    override fun toString(): String = "ApiKey(****)"
}
