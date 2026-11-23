package com.ouor.localchat.chat

import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.Front
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
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

/** ToolExposure over ToolCatalog (track C, architecture 6.3). */
interface ToolPort {
    fun exposed(caps: ModelCapabilities, search: Boolean, browser: Boolean): List<Tool>

    /** Any catalog tool, exposed or not (LinkPrefetcher runs read itself). */
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

/**
 * The engine as ToolExecutor uses it (browser-layers 8): the same API the drawer uses, narrowed so the loop runs in
 * JVM tests with a fake. [tab] is only reached by browser tools.
 */
interface BrowserPort {
    fun tabs(): List<TabInfo>
    fun front(): Front?

    /** The tab the drawer shows now, null when none. */
    fun shown(): Int?

    /** A new tab with nothing loaded yet; the front stays. Returns its id. */
    suspend fun open(): Int
    suspend fun close(tabId: Int)
    fun setActivity(tabId: Int, activity: ToolActivity?)

    /** Main thread. Throws TabClosed for a closed tab. */
    fun tab(tabId: Int): BrowserTab
    fun cookieHeader(url: String): String?

    /** The clock of the tabs' event, request and input logs (uptime ms). */
    fun now(): Long
}
