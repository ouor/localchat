package com.ouor.localchat.provider

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.testing.FakeOpenAiServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsAndConnectionTest {

    private fun entry(id: String, name: String? = null) =
        AppJson.parseToJsonElement("""{"id":"$id"${name?.let { ""","name":"$it"""" } ?: ""}}""").jsonObject

    @Test
    fun `refresh keeps user values, marks vanished models and adds new ones`() {
        val existing = listOf(
            ModelRecord("e", "a", entry("a", "Old"), inServerList = true, hidden = true,
                overrides = ModelOverrides(displayName = "Mine", toolCalling = false), lastUsedAt = 7),
            ModelRecord("e", "gone", entry("gone"), inServerList = true),
            ModelRecord("e", "manual", manual = true),
        )
        val merged = mergeServerList("e", existing, listOf(entry("a", "New"), entry("manual"), entry("b"), entry("b"), entry("")))

        val a = merged[0]
        assertEquals(existing[0].copy(serverJson = entry("a", "New")), a)
        assertEquals("Mine", a.toModelInfo(null).displayName)
        assertEquals(existing[1].copy(inServerList = false), merged[1])
        assertEquals(existing[2].copy(serverJson = entry("manual"), inServerList = true), merged[2])
        assertEquals(listOf(ModelRecord("e", "b", entry("b"), inServerList = true)), merged.drop(3))
    }

    private val client = ChatCompletionsClient(OkHttpClient(), AppJson)
    private fun draft(url: String, allowHttp: Boolean = true) = EndpointDraft("x", url, ApiKey("k"), null, allowHttp)

    @Test
    fun `connection check reports the model count`() = runTest {
        FakeOpenAiServer().use { server ->
            server.models = listOf("a", "b", "c")
            val (result, list) = client.checkModels(draft(server.baseUrl).toTarget())
            assertEquals(ConnectionResult.Ok(3), result)
            assertEquals(3, list.size)
            assertEquals("Bearer k", server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `connection check failure kinds`() = runTest {
        MockWebServer().apply { start() }.use { mock ->
            mock.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"bad key"}}""").build())
            mock.enqueue(MockResponse.Builder().body("<html>not an api</html>").build())
            val url = mock.url("/v1").toString()
            assertEquals(ChatErrorKind.AUTH, kind(client.checkModels(draft(url).toTarget()).first))
            assertEquals(ChatErrorKind.PROTOCOL, kind(client.checkModels(draft(url).toTarget()).first))
            assertEquals(ChatErrorKind.CONFIG, kind(client.checkModels(draft(url, allowHttp = false).toTarget()).first))
        }
        val closed = MockWebServer().apply { start() }.let { val u = it.url("/v1").toString(); it.close(); u }
        assertEquals(ChatErrorKind.NETWORK, kind(client.checkModels(draft(closed).toTarget()).first))
    }

    private fun kind(r: ConnectionResult): ChatErrorKind {
        assertTrue(r is ConnectionResult.Failed)
        return (r as ConnectionResult.Failed).error.kind
    }
}
