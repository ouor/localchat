package com.ouor.localchat.storage.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import kotlinx.coroutines.flow.Flow

/** Endpoints and models for provider (track A). Basic access only; track A may add queries. */
@Dao
interface ProviderDao {

    @Query("SELECT * FROM endpoints ORDER BY sortOrder, createdAt")
    fun observeEndpoints(): Flow<List<EndpointEntity>>

    @Query("SELECT * FROM endpoints WHERE id = :id")
    suspend fun endpoint(id: String): EndpointEntity?

    @Upsert
    suspend fun upsertEndpoint(endpoint: EndpointEntity)

    @Query("DELETE FROM endpoints WHERE id = :id")
    suspend fun deleteEndpoint(id: String)

    @Query("SELECT * FROM models WHERE endpointId = :endpointId ORDER BY modelId")
    fun observeModels(endpointId: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models ORDER BY endpointId, modelId")
    fun observeAllModels(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE endpointId = :endpointId AND modelId = :modelId")
    suspend fun model(endpointId: String, modelId: String): ModelEntity?

    @Upsert
    suspend fun upsertModels(models: List<ModelEntity>)

    @Query("DELETE FROM models WHERE endpointId = :endpointId AND modelId = :modelId")
    suspend fun deleteModel(endpointId: String, modelId: String)
}
