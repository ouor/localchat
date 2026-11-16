package com.ouor.localchat.chat

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.ModelOverrides
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.provider.EndpointDraft
import com.ouor.localchat.provider.FakeProviderDao
import com.ouor.localchat.provider.ProviderRepository
import com.ouor.localchat.storage.SecretEntries
import com.ouor.localchat.storage.SecretStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.KeyGenerator

class ProviderModelPortTest {
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val secrets = SecretStore(MemoryDataStore(SecretEntries())) { aes }
    private val repo = ProviderRepository(ChatCompletionsClient(OkHttpClient(), AppJson), FakeProviderDao(), secrets, { 0 }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
    private val port = ProviderModelPort(repo, secrets)

    private suspend fun reasoningOff(baseUrl: String, modelId: String): ModelRef {
        val id = repo.add(EndpointDraft("e", baseUrl, null, null, allowHttp = true))
        repo.addManualModel(id, modelId)
        repo.updateModel(id, modelId, ModelOverrides(reasoning = false))
        return ModelRef(id, modelId, modelId)
    }

    @Test
    fun `U-1 a user off sends thinking disabled only on the DeepSeek host`() = runTest {
        assertNull(port.resolve(reasoningOff("http://localhost:11434/v1", "deepseek-r1:8b"))!!.reasoningWire)
        assertEquals(ReasoningWire.DEEPSEEK_THINKING, port.resolve(reasoningOff("https://api.deepseek.com", "deepseek-chat"))!!.reasoningWire)
    }
}
