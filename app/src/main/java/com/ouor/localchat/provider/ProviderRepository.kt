package com.ouor.localchat.provider

import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.Endpoint
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.PresetId
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Endpoints, their models and keys (public surface, architecture 10.2). */
@Singleton
class ProviderRepository @Inject constructor() {

    val endpoints: Flow<List<Endpoint>> get() = TODO("track A")

    fun models(endpointId: String): Flow<List<ModelInfo>> = TODO("track A")

    /** Non-hidden models of enabled endpoints, for the model picker (P-06). */
    val allVisibleModels: Flow<List<ModelInfo>> get() = TODO("track A")

    /** Returns the new endpoint id. */
    suspend fun add(draft: EndpointDraft): String = TODO("track A")

    suspend fun update(endpointId: String, draft: EndpointDraft): Unit = TODO("track A")

    suspend fun delete(endpointId: String): Unit = TODO("track A")

    suspend fun setEnabled(endpointId: String, enabled: Boolean): Unit = TODO("track A")

    /** null removes the key. */
    suspend fun setKey(endpointId: String, key: ApiKey?): Unit = TODO("track A")

    /** Last 4 characters of the stored key, or null (SEC-2). */
    suspend fun keyHint(endpointId: String): String? = TODO("track A")

    /** P-02: `GET /models` with unsaved values. */
    suspend fun checkConnection(draft: EndpointDraft): ConnectionResult = TODO("track A")

    /** P-03: refresh the cached list, keeping user values; vanished models are marked, not deleted. */
    suspend fun refreshModels(endpointId: String): ConnectionResult = TODO("track A")

    suspend fun addManualModel(endpointId: String, modelId: String): Unit = TODO("track A")

    suspend fun updateModel(endpointId: String, modelId: String, overrides: ModelOverrides): Unit = TODO("track A")

    /** "Reset to defaults": drops all user overrides of the model. */
    suspend fun resetModel(endpointId: String, modelId: String): Unit = TODO("track A")
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
