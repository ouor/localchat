package com.ouor.localchat.chat

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.safety.CookieMasker
import com.ouor.localchat.tools.ToolExposure
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
import com.ouor.localchat.storage.db.toEntity
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.attach.AttachmentIngest
import java.util.Base64
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
}

class StoredAttachmentPort @Inject constructor(
    private val dao: AttachmentDao,
    private val files: AttachmentFiles,
    private val ingest: AttachmentIngest,
    private val clock: Clock,
) : AttachmentPort {
    /** Derived files are made on demand (e.g. after a restore) with prepareStored (track-d.md 6). */
    override suspend fun payload(id: String): AttachmentPayload? {
        val a = dao.get(id)?.toModel() ?: return null
        if (!a.hasOriginal || a.state != PrepState.Ready) return AttachmentPayload(a)
        return withContext(Dispatchers.IO) {
            val derived = if (a.kind == AttachmentKind.IMAGE) files.requestImage(id) else files.text(id)
            if (!derived.isFile) ingest.prepareStored(a)
            when {
                !derived.isFile -> AttachmentPayload(a)
                a.kind == AttachmentKind.IMAGE ->
                    AttachmentPayload(a, imageDataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(derived.readBytes()))
                else -> AttachmentPayload(a, text = derived.readText())
            }
        }
    }

    override suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val id = Ids.new()
        files.original(id).writeBytes(bytes)
        dao.upsert(
            Attachment(id, name, mime, bytes.size.toLong(), AttachmentKind.ARTIFACT, PrepState.Ready, hasOriginal = true, createdAt = clock.now())
                .toEntity(),
        )
        id
    }
}

class EngineBrowserPort @Inject constructor(private val engine: BrowserEngine) : BrowserPort {
    // Default tab creation, ownership and B-S3 live in one place in the engine (main thread only).
    override suspend fun aiTabId(sessionId: String, tabIdArg: Int?): Int =
        withContext(Dispatchers.Main.immediate) { engine.aiTabFor(sessionId, tabIdArg).id }

    override fun tab(tabId: Int): BrowserTab = engine.tab(tabId)

    override fun control(tabId: Int): Flow<Control> =
        engine.tabs.map { tabs -> tabs.firstOrNull { it.id == tabId }?.control ?: Control.AI }.distinctUntilChanged()

    override fun setControl(tabId: Int, control: Control) = engine.setControl(tabId, control)

    override fun maskCookies(text: String, url: String): String = CookieMasker.mask(text, engine.cookieHeader(url))

    override fun clearAiActivity(sessionId: String) = engine.clearAiActivity(sessionId)
}
