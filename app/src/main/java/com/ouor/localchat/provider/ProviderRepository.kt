package com.ouor.localchat.provider

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.ApplicationScope
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.Endpoint
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.PresetId
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.storage.db.dao.ProviderDao
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Endpoints, their models and keys (public surface, architecture 10.2). */
@Singleton
class ProviderRepository @Inject constructor(
    private val client: ChatCompletionsClient,
    private val dao: ProviderDao,
    private val secrets: SecretStore,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Read-modify-write of one row (overrides, hidden, refresh merge) must not interleave. */
    private val writes = Mutex()

    val endpoints: Flow<List<Endpoint>> =
        combine(dao.observeEndpoints(), secrets.endpointIds) { rows, withKey -> rows.map { it.toModel(it.id in withKey) } }

    fun models(endpointId: String): Flow<List<ModelInfo>> =
        combine(dao.observeEndpoints(), dao.observeModels(endpointId)) { eps, rows ->
            val host = KnownModels.hostOf(eps.firstOrNull { it.id == endpointId }?.baseUrl)
            rows.map { it.toRecord().toModelInfo(host) }
        }

    /** Non-hidden models of enabled endpoints, for the model picker (P-06). */
    val allVisibleModels: Flow<List<ModelInfo>> =
        combine(dao.observeEndpoints(), dao.observeAllModels()) { eps, rows ->
            val hosts = eps.filter { it.enabled }.associate { it.id to KnownModels.hostOf(it.baseUrl) }
            rows.filter { it.endpointId in hosts && !it.hidden }.map { it.toRecord().toModelInfo(hosts[it.endpointId]) }
        }

    /** Returns the new endpoint id. */
    suspend fun add(draft: EndpointDraft): String = writes.withLock {
        val id = Ids.new()
        val order = (dao.observeEndpoints().first().maxOfOrNull { it.sortOrder } ?: -1) + 1
        dao.upsertEndpoint(draft.toEntity(id, enabled = true, sortOrder = order, createdAt = clock.now()))
        draft.apiKey?.let { secrets.set(id, it) }
        id
    }

    /**
     * [EndpointDraft.apiKey] null keeps the stored key; use [setKey] to remove it. SE 25: a stored key
     * is not kept for a new origin (scheme, host, port) unless [keepKeyForNewHost]; else [KeyHostChanged].
     */
    suspend fun update(endpointId: String, draft: EndpointDraft, keepKeyForNewHost: Boolean = false): Unit = writes.withLock {
        val old = requireNotNull(dao.endpoint(endpointId)) { "no endpoint $endpointId" }
        guardKey(endpointId, old.baseUrl, draft, keepKeyForNewHost)
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
     * key of that endpoint is used (editing with "keep key"), with the same SE 25 rule as [update].
     */
    suspend fun checkConnection(draft: EndpointDraft, endpointId: String? = null, keepKeyForNewHost: Boolean = false): ConnectionResult {
        endpointId?.let { id -> dao.endpoint(id)?.let { guardKey(id, it.baseUrl, draft, keepKeyForNewHost) } }
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

    /**
     * [refreshModels] after a save, owned by the app scope: the screen that saved may close at once (onboarding
     * removes its entry on Saved), and cancelling with it left a new endpoint with no models (FB2 QA Major-1).
     */
    fun refreshModelsAfterSave(endpointId: String) {
        scope.launch { refreshModels(endpointId) }
    }

    /** P-03 fallback when the list lacks a model. An existing model is left as it is. */
    suspend fun addManualModel(endpointId: String, modelId: String): Unit = writes.withLock {
        val id = modelId.trim()
        require(id.isNotEmpty()) { "blank model id" }
        if (dao.model(endpointId, id) == null) dao.upsertModels(listOf(ModelRecord(endpointId, id, manual = true).toEntity()))
    }

    /** The stored user values (SC-02a form start); [ModelInfo] only has the resolved result. */
    suspend fun overrides(endpointId: String, modelId: String): ModelOverrides =
        dao.model(endpointId, modelId)?.toRecord()?.overrides ?: ModelOverrides()

    /**
     * FB-6 "자동": the model's caps with every user override ignored, so each value's source is
     * SERVER, DEFAULT (built-in) or UNKNOWN. null when the model is not stored.
     */
    suspend fun autoCaps(endpointId: String, modelId: String): ModelCapabilities? {
        val row = dao.model(endpointId, modelId) ?: return null
        val host = KnownModels.hostOf(dao.endpoint(endpointId)?.baseUrl)
        return row.toRecord().copy(overrides = ModelOverrides()).toModelInfo(host).caps
    }

    /** Replaces all user values of the model. */
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

    private suspend fun guardKey(endpointId: String, oldUrl: String, draft: EndpointDraft, confirmed: Boolean) {
        if (draft.apiKey == null && !confirmed && origin(oldUrl) != origin(draft.baseUrl) && secrets.get(endpointId) != null) {
            throw KeyHostChanged(endpointId)
        }
    }

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

/**
 * SE 25: the base URL moved to another origin while the draft keeps the stored key. The UI asks for
 * the key again, or repeats the call with `keepKeyForNewHost = true` after the user confirms.
 */
class KeyHostChanged(val endpointId: String) : IllegalStateException("base URL origin changed; re-enter or confirm the key")

/** Unparseable URLs compare as themselves, so any edit of one counts as a change. */
private fun origin(url: String) = url.trim().toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" } ?: url.trim()

sealed interface ConnectionResult {
    data class Ok(val modelCount: Int) : ConnectionResult

    /** AUTH (401), NETWORK, PROTOCOL (unparseable list) ... */
    data class Failed(val error: ChatError) : ConnectionResult
}

// Entity mapping (AR F-05): the only mapping, also used by backup. Everything above returns core models / ModelRecord.

fun EndpointEntity.toModel(hasKey: Boolean) = Endpoint(
    id = id, name = name, baseUrl = baseUrl,
    preset = preset?.let { p -> PresetId.entries.firstOrNull { it.name == p } },
    enabled = enabled, maxRetries = maxRetries, allowHttp = allowHttp, hasKey = hasKey,
)

/** The key is not part of the row ([Endpoint.hasKey] comes from SecretStore). */
fun Endpoint.toEntity(sortOrder: Int, createdAt: Long) = EndpointEntity(
    id = id, name = name, baseUrl = baseUrl, preset = preset?.name, enabled = enabled,
    maxRetries = maxRetries, allowHttp = allowHttp, sortOrder = sortOrder, createdAt = createdAt,
)

private fun EndpointDraft.toEntity(id: String, enabled: Boolean, sortOrder: Int, createdAt: Long) =
    Endpoint(id, name.trim(), baseUrl.trim(), preset, enabled, maxRetries.coerceIn(0, 10), allowHttp, hasKey = false)
        .toEntity(sortOrder, createdAt)

fun ModelEntity.toRecord(): ModelRecord {
    val overrides = capsUserJson?.let { runCatching { AppJson.decodeFromString(ModelOverrides.serializer(), it) }.getOrNull() }
        ?: ModelOverrides()
    return ModelRecord(
        endpointId = endpointId,
        modelId = modelId,
        serverJson = serverJson?.let { runCatching { AppJson.parseToJsonElement(it) as? JsonObject }.getOrNull() },
        inServerList = inServerList,
        manual = manual,
        hidden = hidden,
        overrides = overrides,
        lastUsedAt = lastUsedAt,
    )
}

fun ModelRecord.toEntity() = ModelEntity(
    endpointId = endpointId,
    modelId = modelId,
    serverJson = serverJson?.toString(),
    inServerList = inServerList,
    manual = manual,
    hidden = hidden,
    capsUserJson = overrides.takeIf { it != ModelOverrides() }?.let { AppJson.encodeToString(ModelOverrides.serializer(), it) },
    lastUsedAt = lastUsedAt,
)
