package com.ouor.localchat.core.model

import kotlinx.serialization.Serializable

/** A capability value and its origin, shown as a badge in model details (P-04). */
@Serializable
data class Sourced<T>(val value: T, val source: CapSource)

@Serializable
enum class CapSource { USER, SERVER, DEFAULT, UNKNOWN }

@Serializable
data class ModelCapabilities(
    val toolCalling: Sourced<Boolean>,
    val imageInput: Sourced<Boolean>,
    /** null value = no reasoning. */
    val reasoning: Sourced<ReasoningSpec?>,
    /** null value = unknown. */
    val contextWindow: Sourced<Int?>,
    val maxOutputTokens: Sourced<Int?>,
)

@Serializable
data class ReasoningSpec(
    /** Effort levels the user can pick (M-08); empty = on/off only. */
    val efforts: List<String>,
    val defaultEffort: String?,
    val wire: ReasoningWire,
)

/** How reasoning is switched in a request (architecture 4.3). */
@Serializable
enum class ReasoningWire {
    /** `reasoning_effort: <level>`, omitted when off. */
    EFFORT,

    /** `thinking: {type: enabled|disabled}` + `reasoning_effort`. Omitting it means ON. */
    DEEPSEEK_THINKING,

    /** Cannot be switched off (e.g. `<think>` tag models). */
    ALWAYS_ON,
}

/**
 * User overrides for one model (P-04 step 1). A null field means "not overridden".
 * Stored as the model's capsUserJson and in backups.
 */
@Serializable
data class ModelOverrides(
    val displayName: String? = null,
    val toolCalling: Boolean? = null,
    val imageInput: Boolean? = null,
    val reasoning: Boolean? = null,
    val reasoningEfforts: List<String>? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
)
