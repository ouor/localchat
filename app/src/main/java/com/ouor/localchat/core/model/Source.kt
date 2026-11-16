package com.ouor.localchat.core.model

import kotlinx.serialization.Serializable

/** S-04 citation. [n] is the number the model writes as `[n]`, unique within a message. */
@Serializable
data class Source(
    val n: Int,
    val url: String,
    val title: String,
    val snippet: String? = null,
    val siteName: String? = null,
)
