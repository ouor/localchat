package com.ouor.localchat.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.ouor.localchat.storage.db.entities.ModelEntity
import kotlinx.coroutines.flow.Flow

/** Models for provider (track A, track-a.md 5). */
@Dao
interface ModelDao {

    @Query("SELECT * FROM models WHERE endpointId = :endpointId ORDER BY rowid")
    fun observe(endpointId: String): Flow<List<ModelEntity>>

    /** P-06 picker: non-hidden models of enabled endpoints. */
    @Query(
        "SELECT m.* FROM models m JOIN endpoints e ON e.id = m.endpointId " +
            "WHERE e.enabled = 1 AND m.hidden = 0 ORDER BY e.sortOrder, e.createdAt, m.rowid",
    )
    fun observeVisible(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE endpointId = :endpointId ORDER BY rowid")
    suspend fun list(endpointId: String): List<ModelEntity>

    /** P-03 refresh merge result, in one transaction. */
    @Upsert
    suspend fun upsertAll(rows: List<ModelEntity>)

    /** Manual add; an existing row wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ModelEntity)

    @Query("UPDATE models SET capsUserJson = :capsUserJson WHERE endpointId = :endpointId AND modelId = :modelId")
    suspend fun updateUser(endpointId: String, modelId: String, capsUserJson: String?)

    @Query("UPDATE models SET hidden = :hidden WHERE endpointId = :endpointId AND modelId = :modelId")
    suspend fun setHidden(endpointId: String, modelId: String, hidden: Boolean)

    @Query("UPDATE models SET lastUsedAt = :lastUsedAt WHERE endpointId = :endpointId AND modelId = :modelId")
    suspend fun touch(endpointId: String, modelId: String, lastUsedAt: Long)
}
