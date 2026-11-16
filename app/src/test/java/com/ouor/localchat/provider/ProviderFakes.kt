package com.ouor.localchat.provider

import com.ouor.localchat.storage.db.dao.ProviderDao
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory ProviderDao with the same semantics (upsert by key, CASCADE on endpoint delete). */
class FakeProviderDao : ProviderDao {
    val eps = MutableStateFlow<Map<String, EndpointEntity>>(emptyMap())
    val models = MutableStateFlow<Map<Pair<String, String>, ModelEntity>>(emptyMap())
    override fun observeEndpoints() = eps.map { m -> m.values.sortedWith(compareBy({ it.sortOrder }, { it.createdAt })) }
    override suspend fun endpoint(id: String) = eps.value[id]
    override suspend fun upsertEndpoint(endpoint: EndpointEntity) { eps.value += endpoint.id to endpoint }
    override suspend fun deleteEndpoint(id: String) {
        eps.value -= id
        models.value = models.value.filterKeys { it.first != id }
    }
    override fun observeModels(endpointId: String) =
        models.map { m -> m.values.filter { it.endpointId == endpointId }.sortedBy { it.modelId } }
    override fun observeAllModels() = models.map { m -> m.values.sortedWith(compareBy({ it.endpointId }, { it.modelId })) }
    override suspend fun model(endpointId: String, modelId: String) = models.value[endpointId to modelId]
    override suspend fun upsertModels(models: List<ModelEntity>) {
        this.models.value += models.associateBy { it.endpointId to it.modelId }
    }
    override suspend fun deleteModel(endpointId: String, modelId: String) { models.value -= endpointId to modelId }
}