package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.ModelRecord
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/** A new `models`/`endpoints` column that backup forgets would vanish on restore (AR F-05). */
class BackupMappingTest {

    private fun columns(c: Class<*>) = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()

    @Test
    fun `every models column survives a backup round trip`() {
        // Adding a column fails here: set it below and make toBackup/toRow carry it.
        assertEquals(
            setOf("endpointId", "modelId", "serverJson", "inServerList", "manual", "hidden", "capsUserJson", "lastUsedAt"),
            columns(ModelEntity::class.java),
        )
        val row = ModelEntity(
            endpointId = "ep1", modelId = "m1", serverJson = """{"id":"m1","context_length":8}""", inServerList = true, manual = true,
            hidden = true, capsUserJson = AppJson.encodeToString(ModelOverrides.serializer(), ModelOverrides("Name", true, false, true, listOf("high"), 1000, 100)),
            lastUsedAt = 42,
        )
        val viaFile = AppJson.decodeFromString(ModelRecord.serializer(), AppJson.encodeToString(ModelRecord.serializer(), row.toBackup()))
        assertEquals(row, viaFile.toRow())
    }

    @Test
    fun `endpoints columns are known`() {
        // sortOrder comes from the file order and createdAt is not in format v1 (track-d.md 4.3).
        assertEquals(
            setOf("id", "name", "baseUrl", "preset", "enabled", "maxRetries", "allowHttp", "sortOrder", "createdAt"),
            columns(EndpointEntity::class.java),
        )
    }
}
