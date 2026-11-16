package com.ouor.localchat.provider

import com.ouor.localchat.chat.MemoryDataStore
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.PresetId
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.storage.SecretEntries
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.storage.db.dao.ProviderDao
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import com.ouor.localchat.testing.FakeOpenAiServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class ProviderRepositoryTest {

    private val server = FakeOpenAiServer()
    private val dao = FakeProviderDao()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val secrets = SecretStore(MemoryDataStore(SecretEntries())) { aes }
    private var now = 1_000L
    private val repo = ProviderRepository(ChatCompletionsClient(OkHttpClient(), AppJson), dao, secrets) { now }

    private fun draft(key: String? = "sk-abcd1234", url: String = server.baseUrl) =
        EndpointDraft("Local", " $url ", key?.let(::ApiKey), PresetId.OLLAMA, allowHttp = true, maxRetries = 20)

    @After
    fun close() = server.close()

    @Test
    fun `add stores the endpoint and its key`() = runTest {
        val a = repo.add(draft())
        now = 2_000
        val b = repo.add(draft(key = null))
        val eps = repo.endpoints.first()
        assertEquals(listOf(a, b), eps.map { it.id })
        assertEquals(listOf(true, false), eps.map { it.hasKey })
        assertEquals(server.baseUrl, eps[0].baseUrl)
        assertEquals(10, eps[0].maxRetries)
        assertEquals(PresetId.OLLAMA, eps[0].preset)
        assertEquals("1234", repo.keyHint(a))
        assertNull(repo.keyHint(b))
        assertEquals(listOf(0, 1), dao.eps.value.values.sortedBy { it.sortOrder }.map { it.sortOrder })
    }

    @Test
    fun `update keeps the key unless a new one is given, setKey null removes it`() = runTest {
        val id = repo.add(draft())
        repo.setEnabled(id, false)
        repo.update(id, draft(key = null).copy(name = "Renamed"))
        val ep = repo.endpoints.first().single()
        assertEquals("Renamed", ep.name)
        assertFalse(ep.enabled)
        assertEquals("1234", repo.keyHint(id))
        repo.update(id, draft(key = "sk-new-9999"))
        assertEquals("9999", repo.keyHint(id))
        repo.setKey(id, null)
        assertFalse(repo.endpoints.first().single().hasKey)
    }

    @Test
    fun `refresh merges the list and keeps user values`() = runTest {
        val id = repo.add(draft())
        server.models = listOf("a", "b")
        assertEquals(ConnectionResult.Ok(2), repo.refreshModels(id))
        assertEquals("Bearer sk-abcd1234", server.takeRequest().headers["Authorization"])

        repo.updateModel(id, "a", ModelOverrides(displayName = "Mine", toolCalling = true))
        repo.setHidden(id, "b", true)
        now = 5_000
        repo.markUsed(id, "a")

        server.models = listOf("a", "c")
        repo.refreshModels(id)
        val models = repo.models(id).first().associateBy { it.modelId }
        assertEquals(setOf("a", "b", "c"), models.keys)
        assertEquals("Mine", models.getValue("a").displayName)
        assertTrue(models.getValue("a").caps.toolCalling.value)
        assertEquals(5_000L, models.getValue("a").lastUsedAt)
        assertFalse(models.getValue("b").inServerList)
        assertTrue(models.getValue("b").hidden)
        assertEquals(listOf("a", "c"), repo.allVisibleModels.first().map { it.modelId })
    }

    @Test
    fun `failed refresh leaves the cached list alone`() = runTest {
        val id = repo.add(draft())
        server.models = listOf("a")
        repo.refreshModels(id)
        repo.update(id, draft(key = null, url = "http://127.0.0.1:1/v1"), keepKeyForNewHost = true)
        val r = repo.refreshModels(id) as ConnectionResult.Failed
        assertEquals(ChatErrorKind.NETWORK, r.error.kind)
        assertEquals(listOf("a"), repo.models(id).first().map { it.modelId })
    }

    @Test
    fun `manual model, reset and disabled endpoint`() = runTest {
        val id = repo.add(draft())
        repo.addManualModel(id, " my-model ")
        repo.updateModel(id, "my-model", ModelOverrides(contextWindow = 4096))
        assertEquals(ModelOverrides(contextWindow = 4096), repo.overrides(id, "my-model"))
        repo.addManualModel(id, "my-model") // already there: untouched
        assertEquals(4096, repo.models(id).first().single().caps.contextWindow.value)
        assertTrue(repo.models(id).first().single().manual)

        repo.resetModel(id, "my-model")
        assertNull(repo.models(id).first().single().caps.contextWindow.value)
        assertNull(dao.models.value.values.single().capsUserJson)

        repo.setEnabled(id, false)
        assertTrue(repo.allVisibleModels.first().isEmpty())
        repo.updateModel(id, "unknown", ModelOverrides(toolCalling = true)) // ignored
    }

    @Test
    fun `delete removes endpoint, models and key`() = runTest {
        val id = repo.add(draft())
        repo.addManualModel(id, "m")
        repo.delete(id)
        assertTrue(repo.endpoints.first().isEmpty())
        assertTrue(dao.models.value.isEmpty())
        assertNull(repo.keyHint(id))
    }

    @Test
    fun `connection check uses the stored key when the draft keeps it`() = runTest {
        val id = repo.add(draft())
        assertEquals(ConnectionResult.Ok(1), repo.checkConnection(draft(key = null), endpointId = id))
        assertEquals("Bearer sk-abcd1234", server.takeRequest().headers["Authorization"])
        repo.checkConnection(draft(key = null))
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `SE 25 a new host does not silently inherit the stored key`() = runTest {
        val id = repo.add(draft())
        val other = "http://127.0.0.1:1/v1"
        assertThrows(KeyHostChanged::class.java) { kotlinx.coroutines.runBlocking { repo.update(id, draft(key = null, url = other)) } }
        assertEquals(server.baseUrl, repo.endpoints.first().single().baseUrl)
        assertThrows(KeyHostChanged::class.java) { kotlinx.coroutines.runBlocking { repo.checkConnection(draft(key = null, url = other), id) } }

        repo.update(id, draft(key = null, url = server.baseUrl + "/x")) // same origin: key kept
        assertEquals("1234", repo.keyHint(id))
        repo.update(id, draft(key = "sk-other-5678", url = other)) // new key for the new host
        assertEquals("5678", repo.keyHint(id))
        repo.update(id, draft(key = null, url = server.baseUrl), keepKeyForNewHost = true) // user confirmed
        assertEquals(ConnectionResult.Ok(1), repo.checkConnection(draft(key = null), id))
        assertEquals("Bearer sk-other-5678", server.takeRequest().headers["Authorization"])

        repo.setKey(id, null) // nothing to leak: no guard
        repo.update(id, draft(key = null, url = other))
    }

    @Test
    fun `public entity mapping keeps every column`() {
        val ep = EndpointEntity("e", "N", "https://h/v1", "OLLAMA", enabled = false, maxRetries = 3, allowHttp = true, sortOrder = 7, createdAt = 9)
        val model = ep.toModel(hasKey = true)
        assertTrue(model.hasKey)
        assertEquals(PresetId.OLLAMA, model.preset)
        assertEquals(ep, model.toEntity(sortOrder = 7, createdAt = 9))
        val row = ModelEntity("e", "m", """{"id":"m"}""", inServerList = false, manual = true, hidden = true,
            capsUserJson = AppJson.encodeToString(ModelOverrides.serializer(), ModelOverrides(displayName = "N")), lastUsedAt = 4)
        assertEquals(row, row.toRecord().toEntity())
    }

    @Test
    fun `entity mapping round trip`() {
        val record = ModelRecord("e", "m", AppJson.parseToJsonElement("""{"id":"m"}""") as kotlinx.serialization.json.JsonObject,
            inServerList = true, overrides = ModelOverrides(displayName = "N", imageInput = true), hidden = true, lastUsedAt = 3)
        assertEquals(record, record.toEntity().toRecord())
    }
}
