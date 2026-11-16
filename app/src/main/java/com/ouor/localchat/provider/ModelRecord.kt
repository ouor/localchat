package com.ouor.localchat.provider

import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelOverrides
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One stored model, shaped like the `models` table row (architecture 8.1). [serverJson] is the
 * `/models` entry as-is; user values are [hidden], [overrides] and [lastUsedAt].
 */
@Serializable
data class ModelRecord(
    val endpointId: String,
    val modelId: String,
    val serverJson: JsonObject? = null,
    val inServerList: Boolean = false,
    val manual: Boolean = false,
    val hidden: Boolean = false,
    val overrides: ModelOverrides = ModelOverrides(),
    val lastUsedAt: Long? = null,
) {
    fun toModelInfo(): ModelInfo = ModelInfo(
        endpointId = endpointId,
        modelId = modelId,
        displayName = CapabilityResolver.displayName(modelId, overrides, serverJson),
        hidden = hidden,
        inServerList = inServerList,
        manual = manual,
        lastUsedAt = lastUsedAt,
        caps = CapabilityResolver.resolve(overrides, serverJson, KnownModels.find(modelId)),
    )
}

/**
 * P-03 refresh: server values are replaced, user values kept, models missing from the list are
 * marked `inServerList = false` (never deleted). Existing order first, then new ids in server order.
 */
fun mergeServerList(endpointId: String, existing: List<ModelRecord>, server: List<JsonObject>): List<ModelRecord> {
    val byId = LinkedHashMap<String, JsonObject>()
    server.forEach { entry -> (entry["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { byId.putIfAbsent(it, entry) } }
    val kept = existing.map { r ->
        val fresh = byId[r.modelId]
        if (fresh == null) r.copy(inServerList = false) else r.copy(serverJson = fresh, inServerList = true)
    }
    val known = existing.mapTo(HashSet()) { it.modelId }
    val added = byId.filterKeys { it !in known }.map { (id, json) -> ModelRecord(endpointId, id, json, inServerList = true) }
    return kept + added
}
