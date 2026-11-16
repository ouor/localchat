package com.ouor.localchat.core.model

import kotlinx.serialization.Serializable

/** Model identity plus a display name snapshot, so history still reads after the endpoint is gone (P-08). */
@Serializable
data class ModelRef(
    val endpointId: String,
    val modelId: String,
    val displayName: String,
)

@Serializable
data class ModelInfo(
    val endpointId: String,
    val modelId: String,
    /** user > server `name` > modelId. */
    val displayName: String,
    val hidden: Boolean,
    /** false = no longer returned by /models ("not in list", P-03). */
    val inServerList: Boolean,
    val manual: Boolean,
    val lastUsedAt: Long?,
    /** Resolved capabilities with where each value came from (P-04). */
    val caps: ModelCapabilities,
) {
    val ref: ModelRef get() = ModelRef(endpointId, modelId, displayName)
}
