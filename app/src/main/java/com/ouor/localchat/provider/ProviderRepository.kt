package com.ouor.localchat.provider

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.Endpoint
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.PresetId
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.storage.db.dao.ProviderDao
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Endpoints, their models and keys (public surface, architecture 10.2). */
@Singleton
class ProviderRepository @Inject constructor(
    private val client: ChatCompletionsClient,
    private val dao: ProviderDao,
    private val secrets: SecretStore,
    private val clock: Clock,
) {
    /** Read-modify-write of one row (overrides, hidden, refresh merge) must not interleave. */
    private val writes = Mutex()

    val endpoints: Flow<List<Endpoint>> =
        combine(dao.observeEndpoints(), secrets.endpointIds) { rows, withKey -> rows.map { it.toModel(it.id in withKey) } }

    fun models(endpointId: String): Flow<List<ModelInfo>> =
        dao.observeModels(endpointId).map { rows -> rows.map { it.toRecord().toModelInfo() } }

    /** Non-hidden models of enabled endpoints, for the model picker (P-06). */
    val allVisibleModels: Flow<List<ModelInfo>> =
        combine(dao.observeEndpoints(), dao.observeAllModels()) { eps, rows ->
            val enabled = eps.filter { it.enabled }.mapTo(HashSet()) { it.id }
            rows.filter { it.endpointId in enabled && !it.hidden }.map { it.toRecord().toModelInfo() }
        }

    /** Returns the new endpoint id. */
    suspend fun add(draft: EndpointDraft): String = writes.withLock {
        val id = Ids.new()
        val order = (dao.observeEndpoints().first().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsertEndpoint(draft.toEntity(id, enabled = true, sortOrder = order, createdAt = clock.now()))
        draft.apiKey?.let { secrets.set(id, it) }
        id
    }

    /** [EndpointDraft.apiKey] null keeps the stored key; use [setKey] to remove it. */
    suspend fun update(endpointId: String, draft: EndpointDraft): Unit = writes.withLock {
        val old = requireNotNull(dao.endpoint(endpointId)) { "no endpoint $endpointId" }
        dao.upsertEndpoint(draft.toEntity(endpointId, old.enabled, old.sortOrder, old.createdAt))
        draft.apiKey?.let { secrets.set(endpointId, it) }
    }

    /** Models go with it (CASCADE); history keeps its ModelRef snapshots (P-08). */
    suspend fun delete(endpointId: String): Unit = writes.withLock {
        dao.deleteEndpoint(endpointId)
        secrets.set(endpointId, null)
    }

    suspend fun setEnabled(endpointId: String, enabled: Boolean): Unit = writes.withLock {
        dao.endpoint(endpointId)?.let { dao.upsertEndpoint(it.copy(enabled = enabled)) }
    }

    /** null removes the key. */
    suspend fun setKey(endpointId: String, key: ApiKey?) = secrets.set(endpointId, key)

    /** Last 4 characters of the stored key, or null (SEC-2). */
    suspend fun keyHint(endpointId: String): String? = secrets.get(endpointId)?.value?.takeLast(4)

    /**
     * P-02: `GET /models` with unsaved values. With [endpointId] and no key in the draft, the stored
     * key of that endpoint is used (editing with "keep key").
     */
    suspend fun checkConnection(draft: EndpointDraft, endpointId: String? = null): ConnectionResult {
        val key = draft.apiKey ?: endpointId?.let { secrets.get(it) }
        return client.checkModels(draft.copy(apiKey = key).toTarget()).first
    }

    /** P-03: refresh the cached list, keeping user values; vanished models are marked, not deleted. */
    suspend fun refreshModels(endpointId: String): ConnectionResult {
        val ep = requireNotNull(dao.endpoint(endpointId)) { "no endpoint $endpointId" }
        val target = LlmTarget(ep.baseUrl, secrets.get(endpointId)?.value, maxRetries = 0, allowHttp = ep.allowHttp)
        val (result, list) = client.checkModels(target)
        if (result is ConnectionResult.Ok) writes.withLock {
            val existing = dao.observeModels(endpointId).first().map { it.toRecord() }
            dao.upsertModels(mergeServerList(endpointId, existing, list).map { it.toEntity() })
        }
        return result
    }

    /** P-03 fallback when the list lacks a model. An existing model is left as it is. */
    suspend fun addManualModel(endpointId: String, modelId: String): Unit = writes.withLock {
        val id = modelId.trim()
        require(id.isNotEmpty()) { "blank model id" }
        if (dao.model(endpointId, id) == null) dao.upsertModels(listOf(ModelRecord(endpointId, id, manual = true).toEntity()))
    }

    suspend fun updateModel(endpointId: String, modelId: String, overrides: ModelOverrides) =
        edit(endpointId, modelId) { it.copy(overrides = overrides) }

    /** P-03 hide/show in the model picker (not part of [ModelOverrides]; kept by refresh and reset). */
    suspend fun setHidden(endpointId: String, modelId: String, hidden: Boolean) =
        edit(endpointId, modelId) { it.copy(hidden = hidden) }

    /** P-06 "recent": chat calls this when a request is sent with the model. */
    suspend fun markUsed(endpointId: String, modelId: String) =
        edit(endpointId, modelId) { it.copy(lastUsedAt = clock.now()) }

    /** "Reset to defaults": drops all user overrides of the model (hidden stays). */
    suspend fun resetModel(endpointId: String, modelId: String) =
        edit(endpointId, modelId) { it.copy(overrides = ModelOverrides()) }

    /** Unknown models are ignored (deleted meanwhile). */
    private suspend fun edit(endpointId: String, modelId: String, change: (ModelRecord) -> ModelRecord): Unit = writes.withLock {
        dao.model(endpointId, modelId)?.let { dao.upsertModels(listOf(change(it.toRecord()).toEntity())) }
    }
}

/** Endpoint form values (SC-02). [apiKey] null = keep / no key. */
data class EndpointDraft(
    val name: String,
    val baseUrl: String,
    val apiKey: ApiKey?,
    val preset: PresetId?,
    val allowHttp: Boolean,
    val maxRetries: Int = 5,
)

sealed interface ConnectionResult {
    data class Ok(val modelCount: Int) : ConnectionResult

    /** AUTH (401), NETWORK, PROTOCOL (unparseable list) ... */
    data class Failed(val error: ChatError) : ConnectionResult
}

// Entities stay inside this file: everything above returns core models / ModelRecord.

private fun EndpointEntity.toModel(hasKey: Boolean) = Endpoint(
    id = id, name = name, baseUrl = baseUrl,
    preset = preset?.let { p -> PresetId.entries.firstOrNull { it.name == p } },
    enabled = enabled, maxRetries = maxRetries, allowHttp = allowHttp, hasKey = hasKey,
)

private fun EndpointDraft.toEntity(id: String, enabled: Boolean, sortOrder: Int, createdAt: Long) = EndpointEntity(
    id = id, name = name.trim(), baseUrl = baseUrl.trim(), preset = preset?.name, enabled = enabled,
    maxRetries = maxRetries.coerceIn(0, 10), allowHttp = allowHttp, sortOrder = sortOrder, createdAt = createdAt,
)

internal fun ModelEntity.toRecord(): ModelRecord {
    val overrides = capsUserJson?.let { runCatching { AppJson.decodeFromString(ModelOverrides.serializer(), it) }.getOrNull() }
        ?: ModelOverrides()
    return ModelRecord(
        endpointId = endpointId,
        modelId = modelId,
        serverJson = serverJson?.let { runCatching { AppJson.parseToJsonElement(it) as? JsonObject }.getOrNull() },
        inServerList = inServerList,
        manual = manual,
        hidden = hidden,
        // shortcut: displayNameUser duplicates overrides.displayName until track B drops the column.
        overrides = if (overrides.displayName == null && displayNameUser != null) overrides.copy(displayName = displayNameUser) else overrides,
        lastUsedAt = lastUsedAt,
    )
}

internal fun ModelRecord.toEntity() = ModelEntity(
    endpointId = endpointId,
    modelId = modelId,
    serverJson = serverJson?.toString(),
    inServerList = inServerList,
    manual = manual,
    displayNameUser = overrides.displayName,
    hidden = hidden,
    capsUserJson = overrides.takeIf { it != ModelOverrides() }?.let { AppJson.encodeToString(ModelOverrides.serializer(), it) },
    lastUsedAt = lastUsedAt,
)
