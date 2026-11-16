package com.ouor.localchat.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.ouor.localchat.storage.db.entities.EndpointEntity
import kotlinx.coroutines.flow.Flow

/** Endpoints for provider (track A, track-a.md 5). Keys are in SecretStore, never here. */
@Dao
interface EndpointDao {

    @Query("SELECT * FROM endpoints ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<EndpointEntity>>

    @Query("SELECT * FROM endpoints WHERE id = :id")
    suspend fun get(id: String): EndpointEntity?

    @Insert
    suspend fun insert(endpoint: EndpointEntity)

    @Update
    suspend fun update(endpoint: EndpointEntity)

    /** Its models go too (CASCADE). */
    @Query("DELETE FROM endpoints WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE endpoints SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT MAX(sortOrder) FROM endpoints")
    suspend fun maxSortOrder(): Int?
}
