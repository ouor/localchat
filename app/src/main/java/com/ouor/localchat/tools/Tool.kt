package com.ouor.localchat.tools

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.storage.AttachmentFiles
import kotlinx.serialization.json.JsonObject

/**
 * A model-callable tool (architecture 6.1). Implementations are stateless `object`s; every
 * dependency comes through [ToolContext], so tests use a fake context and no Hilt.
 */
interface Tool {
    /** Name exposed to the model, e.g. "click". */
    val name: String
    val groups: Set<ToolGroup>
    val requiresVision: Boolean get() = false
    val approval: ApprovalMode get() = ApprovalMode.NONE

    /** English description for the model. */
    val description: String

    /** JSON schema of the arguments. */
    val parameters: JsonObject

    /** A-04 step label string resource. */
    val labelRes: Int

    /** A-04 short argument summary (URL, selector). */
    fun argSummary(args: JsonObject): String

    suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome
}

enum class ToolGroup { LINK, SEARCH, BROWSER }

enum class ApprovalMode {
    NONE,

    /** The tool decides (browser.safety.IrreversibleAction) and calls [ToolContext.requireApproval]. */
    CONDITIONAL,

    /** Every call is approved first by the executor (execute). */
    ALWAYS,
}

/** Implemented by chat.run.ToolExecutor; this is how tools reach the loop without importing chat. */
interface ToolContext {
    val sessionId: String
    val browser: BrowserEngine
    val files: AttachmentFiles

    /** The AI tab to act on, after ownership, sensitive-URL and control checks (architecture 6.4). */
    suspend fun aiTab(tabIdArg: Int?): BrowserTab

    /** B-S2: suspends until the user answers SC-13. */
    suspend fun requireApproval(request: ApprovalRequest): Boolean

    /** S-04: citation number for [url], stable within the current message. */
    fun numberSource(url: String, title: String, snippet: String?): Int
}

/** Contents of the SC-13 dialog. */
data class ApprovalRequest(
    val toolName: String,
    val targetText: String?,
    val url: String?,
    val screenshotId: String? = null,
    /** execute: full code shown before running. */
    val code: String? = null,
)

/** B-05 request to hand the tab to the user; the loop pauses on it. */
data class HandoffRequest(val tabId: Int, val reason: String)

data class ToolOutcome(
    val text: String,
    val isError: Boolean = false,
    val summary: String? = null,
    val sources: List<Source> = emptyList(),
    val artifactIds: List<String> = emptyList(),
    val imageForModelId: String? = null,
    val handoff: HandoffRequest? = null,
)
