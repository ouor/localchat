package com.ouor.localchat.ui.providers

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ouor.localchat.chat.MemoryDataStore
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.provider.FakeProviderDao
import com.ouor.localchat.provider.ProviderRepository
import com.ouor.localchat.storage.SecretEntries
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.testing.FakeOpenAiServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.KeyGenerator

/** FB2 QA Major-1: the model list fetched after saving survives the screen closing right away. */
class EndpointSaveTest {

    private val server = FakeOpenAiServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val repo = ProviderRepository(
        ChatCompletionsClient(OkHttpClient(), AppJson), FakeProviderDao(), SecretStore(MemoryDataStore(SecretEntries())) { aes }, { 0 }, appScope,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.close()
    }

    @Test
    fun `onboarding save then entry removed still fills the model list`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        server.models = listOf("deepseek-chat", "deepseek-reasoner")
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { EndpointEditViewModel(null, null, true, repo) } })[EndpointEditViewModel::class]
        vm.onName("DeepSeek")
        vm.onUrl(server.baseUrl)
        vm.onAllowHttp(true)
        vm.onKey("sk-test")
        vm.save()
        val saved = vm.effects.first() as EndpointEffect.Saved
        store.clear() // onboarding: Saved removes the entry, its ViewModel is cleared at once
        val models = withContext(Dispatchers.Default) { withTimeout(5_000) { repo.models(saved.endpointId).first { it.isNotEmpty() } } }
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models.map { it.modelId })
    }
}
