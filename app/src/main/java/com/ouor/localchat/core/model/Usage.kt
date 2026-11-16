package com.ouor.localchat.core.model

import kotlinx.serialization.Serializable

/** Token usage of one request (M-11, A-07). */
@Serializable
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    /** Prompt cache hits when the server reports them (e.g. DeepSeek prompt_cache_hit_tokens). */
    val cachedPromptTokens: Int? = null,
)
