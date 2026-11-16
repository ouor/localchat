package com.ouor.localchat.chat

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.safety.CookieMasker
import com.ouor.localchat.tools.ToolCatalog
import com.ouor.localchat.tools.ToolExposure
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.provider.ProviderRepository
import com.ouor.localchat.provider.reasoningWire
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.attach.StoredAttachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ouor.localchat.tools.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// Production adapters for Ports.kt. Pieces another track has not delivered yet are marked TODO.

class ClientLlmPort @Inject constructor(private val client: ChatCompletionsClient) : LlmPort {
    override fun stream(target: LlmTarget, request: ChatRequest) = client.stream(target, request)
    override suspend fun complete(target: LlmTarget, request: ChatRequest) = client.complete(target, request)
}

class ProviderModelPort @Inject constructor(
    private val providers: ProviderRepository,
    private val secrets: SecretStore,
) : ModelPort {
    override suspend fun resolve(ref: ModelRef): ResolvedModel? {
        val endpoint = providers.endpoints.first().firstOrNull { it.id == ref.endpointId && it.enabled } ?: return null
        val model = providers.models(ref.endpointId).first().firstOrNull { it.modelId == ref.modelId } ?: return null
        val target = LlmTarget(endpoint.baseUrl, secrets.get(endpoint.id)?.value, endpoint.maxRetries, endpoint.allowHttp)
        return ResolvedModel(model.ref, target, model.caps, model.reasoningWire)
    }

    override suspend fun markUsed(ref: ModelRef) = providers.markUsed(ref.endpointId, ref.modelId)
}

class CatalogToolPort @Inject constructor() : ToolPort {
    override fun exposed(caps: ModelCapabilities, search: Boolean, browser: Boolean): List<Tool> =
        ToolExposure.exposedTools(caps, search, browser)

    override fun byName(name: String): Tool? = ToolCatalog.byName(name)
}

class StoredAttachmentPort @Inject constructor(private val stored: StoredAttachments) : AttachmentPort {
    override suspend fun payload(id: String) = stored.payload(id)
    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray) = stored.saveArtifact(name, mime, bytes)
    override suspend fun saveText(name: String, text: String) = stored.saveText(name, text)
}

class EngineBrowserPort @Inject constructor(private val engine: BrowserEngine) : BrowserPort {
    // Default tab creation, ownership and B-S3 live in one place in the engine (main thread only).
    override suspend fun aiTabId(sessionId: String, tabIdArg: Int?): Int =
        withContext(Dispatchers.Main.immediate) { engine.aiTabFor(sessionId, tabIdArg).id }

    override fun tab(tabId: Int): BrowserTab = engine.tab(tabId)

    override suspend fun acquireAiTab(sessionId: String, tabIdArg: Int?, toolName: String, onUserControl: suspend (Int) -> Unit, onResumed: suspend () -> Unit): Int =
        engine.acquireAiTab(sessionId, tabIdArg, toolName, onUserControl, onResumed).id

    override fun setControl(tabId: Int, control: Control) = engine.setControl(tabId, control)

    override fun tabUrl(tabId: Int): String? = engine.tabs.value.firstOrNull { it.id == tabId }?.url

    override fun maskCookies(text: String, url: String): String = CookieMasker.mask(text, engine.cookieHeader(url))

    override fun clearAiActivity(sessionId: String) = engine.clearAiActivity(sessionId)

    override fun closeAiTabs(sessionId: String?) {
        // close() posts to the main thread itself.
        engine.tabs.value.filter { (it.owner as? Owner.Ai)?.sessionId?.let { s -> sessionId == null || s == sessionId } == true }
            .forEach { engine.close(it.id) }
    }

    override suspend fun openAiTab(sessionId: String, url: String): Int =
        withContext(Dispatchers.Main.immediate) { engine.openAiTab(sessionId, url) }

    override suspend fun close(tabId: Int) = withContext(Dispatchers.Main.immediate) { engine.close(tabId) }

    override fun isSensitive(url: String): Boolean = engine.sensitiveUrls.isSensitive(url)
}
