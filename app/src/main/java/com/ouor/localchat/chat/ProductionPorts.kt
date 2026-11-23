package com.ouor.localchat.chat

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.provider.KnownModels
import com.ouor.localchat.tools.ToolCatalog

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
        // U-1: the DeepSeek fallback applies on api.deepseek.com only, so a user "off" elsewhere sends no `thinking`.
        val wire = model.caps.reasoning.value?.wire ?: model.reasoningWire?.takeIf {
            KnownModels.find(model.modelId, KnownModels.hostOf(endpoint.baseUrl)) != null
        }
        return ResolvedModel(model.ref, target, model.caps, wire)
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
    override fun tabs(): List<TabInfo> = engine.tabs.value
    override fun front(): Front? = engine.front.value
    override fun shown(): Int? = engine.shown.value
    override suspend fun open(): Int = withContext(Dispatchers.Main.immediate) { engine.open() }
    override suspend fun close(tabId: Int) = withContext(Dispatchers.Main.immediate) { engine.close(tabId) }
    override fun setActivity(tabId: Int, activity: ToolActivity?) = engine.setActivity(tabId, activity)
    override fun tab(tabId: Int): BrowserTab = engine.tab(tabId)
    override fun cookieHeader(url: String): String? = engine.cookieHeader(url)
    override fun now(): Long = android.os.SystemClock.uptimeMillis()
}
