package com.ouor.localchat.core.model

import kotlinx.serialization.Serializable

/** Composer toggles captured per reply (D2). */
@Serializable
data class GenOptions(
    val reasoning: Boolean = false,
    /** null = model default effort. */
    val reasoningEffort: String? = null,
    val search: Boolean = false,
    val browser: Boolean = false,
)
