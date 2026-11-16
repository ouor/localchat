package com.ouor.localchat.chat

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.llm.ChatCompletionsClient
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.provider.ProviderRepository
import com.ouor.localchat.provider.reasoningWire
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.storage.db.dao.AttachmentDao
import com.ouor.localchat.storage.db.toModel
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
    // TODO(track C): tools.ToolExposure.exposedTools(caps, search, browser) once ToolCatalog exists.
    override fun exposed(caps: ModelCapabilities, search: Boolean, browser: Boolean): List<Tool> = emptyList()
}

class StoredAttachmentPort @Inject constructor(private val dao: AttachmentDao) : AttachmentPort {
    // TODO(track D): extracted text and the downscaled request image from AttachmentFiles.
    override suspend fun payload(id: String): AttachmentPayload? = dao.get(id)?.toModel()?.let { AttachmentPayload(it) }
}

class EngineBrowserPort @Inject constructor(private val engine: BrowserEngine) : BrowserPort {
    override suspend fun aiTab(sessionId: String, tabIdArg: Int?): BrowserTab {
        val owner = Owner.Ai(sessionId)
        val id = if (tabIdArg == null) {
            engine.tabs.value.firstOrNull { it.owner == owner }?.id ?: engine.openAiTab(sessionId, null)
        } else {
            val info = engine.tabs.value.firstOrNull { it.id == tabIdArg } ?: throw ToolRejected("No tab $tabIdArg")
            if (info.owner != owner) throw ToolRejected("Tab $tabIdArg is not an AI tab of this conversation")
            tabIdArg
        }
        // TODO(track C): refuse sensitive URLs with browser.safety.SensitiveUrls (B-S3).
        return engine.tab(id)
    }

    override fun control(tabId: Int): Flow<Control> =
        engine.tabs.map { tabs -> tabs.firstOrNull { it.id == tabId }?.control ?: Control.AI }.distinctUntilChanged()

    override fun setControl(tabId: Int, control: Control) = engine.setControl(tabId, control)
}
