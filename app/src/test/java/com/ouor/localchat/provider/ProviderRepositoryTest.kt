package com.ouor.localchat.provider

import androidx.datastore.core.DataStore
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
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class ProviderRepositoryTest {

    /** In-memory ProviderDao with the same semantics (upsert by key, CASCADE on endpoint delete). */
    private class FakeDao : ProviderDao {
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

    private class MemoryStore<T>(initial: T) : DataStore<T> {
        val state = MutableStateFlow(initial)
        override val data = state
        override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(state.value).also { state.value = it }
    }

    private val server = FakeOpenAiServer()
    private val dao = FakeDao()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val secrets = SecretStore(MemoryStore(SecretEntries())) { aes }
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
        repo.update(id, draft(key = null, url = "http://127.0.0.1:1/v1"))
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
    fun `entity mapping round trip`() {
        val record = ModelRecord("e", "m", AppJson.parseToJsonElement("""{"id":"m"}""") as kotlinx.serialization.json.JsonObject,
            inServerList = true, overrides = ModelOverrides(displayName = "N", imageInput = true), hidden = true, lastUsedAt = 3)
        assertEquals(record, record.toEntity().toRecord())
    }
}
