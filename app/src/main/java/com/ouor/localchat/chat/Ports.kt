package com.ouor.localchat.chat

import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Control
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.ReasoningWire
import com.ouor.localchat.llm.LlmEvent
import com.ouor.localchat.llm.LlmResult
import com.ouor.localchat.llm.LlmTarget
import com.ouor.localchat.llm.wire.ChatRequest
import com.ouor.localchat.tools.Tool
import kotlinx.coroutines.flow.Flow

// Seams to other tracks, so the loop runs in JVM tests with fakes (architecture 11, 12).
// Production bindings: chat/ProductionPorts.kt, wired in di/ChatModule.kt.

/** ChatCompletionsClient (track A). */
interface LlmPort {
    fun stream(target: LlmTarget, request: ChatRequest): Flow<LlmEvent>
    suspend fun complete(target: LlmTarget, request: ChatRequest): LlmResult
}

/**
 * Everything a request to [ref] needs. [reasoningWire] is provider's ModelInfo.reasoningWire: it
 * survives the user turning reasoning off in caps, so DeepSeek still gets `thinking: disabled`.
 */
data class ResolvedModel(
    val ref: ModelRef,
    val target: LlmTarget,
    val caps: ModelCapabilities,
    val reasoningWire: ReasoningWire? = caps.reasoning.value?.wire,
)

/** ProviderRepository + SecretStore. null = endpoint or model no longer exists (or disabled). */
interface ModelPort {
    suspend fun resolve(ref: ModelRef): ResolvedModel?

    /** P-06 "recent". */
    suspend fun markUsed(ref: ModelRef)
}

/** tools.ToolExposure over ToolCatalog (track C, architecture 6.3). */
interface ToolPort {
    fun exposed(caps: ModelCapabilities, search: Boolean, browser: Boolean): List<Tool>

    /** Any catalog tool, exposed or not (LinkPrefetcher runs page_text itself). */
    fun byName(name: String): Tool?
}

/** Owned by attach (AR F-04). */
typealias AttachmentPayload = com.ouor.localchat.attach.AttachmentPayload

/** Implemented by attach.StoredAttachments (AR F-04). */
interface AttachmentPort {
    suspend fun payload(id: String): AttachmentPayload?

    /** B-08: stores a tool artifact as an ARTIFACT attachment; returns its id. */
    suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String

    /** S-03: stores prefetched page text as a ready DOCUMENT attachment; returns its id. */
    suspend fun saveText(name: String, text: String): String
}

/** BrowserEngine (track C) checks behind ToolContext.aiTab (architecture 6.4). */
interface BrowserPort {
    /** Default or given AI tab id of [sessionId] after ownership and sensitive-URL checks; throws when refused. */
    suspend fun aiTabId(sessionId: String, tabIdArg: Int?): Int
    fun tab(tabId: Int): BrowserTab
    fun control(tabId: Int): Flow<Control>

    /** Current URL of the tab, null once closed. */
    fun tabUrl(tabId: Int): String?
    fun setControl(tabId: Int, control: Control)

    /** B-S4: masks the tab's cookie values in [text]. */
    fun maskCookies(text: String, url: String): String

    /** End of a tool call: clears the B-09 ② "AI is operating" state. */
    fun clearAiActivity(sessionId: String)

    /** S-03 hidden tab for LinkPrefetcher. */
    suspend fun openAiTab(sessionId: String, url: String): Int
    suspend fun close(tabId: Int)
    fun isSensitive(url: String): Boolean
}
