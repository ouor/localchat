package com.ouor.localchat.storage.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Used by provider (track A). API keys are never here (SecretStore). */
@Entity(tableName = "endpoints")
data class EndpointEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val preset: String?,
    val enabled: Boolean,
    val maxRetries: Int,
    val allowHttp: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
)

/** One model of an endpoint: the raw `/models` entry plus user overrides (P-03, P-04). */
@Entity(
    tableName = "models",
    primaryKeys = ["endpointId", "modelId"],
    foreignKeys = [
        ForeignKey(EndpointEntity::class, ["id"], ["endpointId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("lastUsedAt")],
)
data class ModelEntity(
    val endpointId: String,
    val modelId: String,
    /** Raw `/models` entry, null for manually added models. */
    val serverJson: String?,
    val inServerList: Boolean,
    val manual: Boolean,
    val displayNameUser: String?,
    val hidden: Boolean,
    /** core.model.ModelOverrides in AppJson, null = none. */
    val capsUserJson: String?,
    val lastUsedAt: Long?,
)
