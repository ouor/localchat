package com.ouor.localchat.tools

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.core.model.ToolStatus
import kotlinx.serialization.json.JsonObject

/**
 * A model-callable tool (architecture 6.1). Implementations are stateless `object`s; every
 * dependency comes through [ToolContext], so tests use a fake context and no Hilt.
 *
 * [run] may be called from any dispatcher (tools switch to Main for WebView work) and may throw
 * [ToolError]; the executor turns it into `ToolOutcome(message, isError = true)`.
 */
interface Tool {
    /** Name exposed to the model, e.g. "click". */
    val name: String
    val groups: Set<ToolGroup>
    val approval: ApprovalMode get() = ApprovalMode.NONE

    /** English description for the model. */
    val description: String

    /** JSON schema of the arguments. */
    val parameters: JsonObject

    /** A-04 step label string resource. */
    val labelRes: Int

    /** A-04 short argument summary (URL, ref, query). */
    fun argSummary(args: JsonObject): String

    suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome
}

enum class ToolGroup { SEARCH, BROWSER }

enum class ApprovalMode {
    NONE,

    /** The tool decides (browser.safety.IrreversibleAction) and calls [ToolContext.requireApproval]. */
    CONDITIONAL,

    /** Every call is approved first by the executor (execute). */
    ALWAYS,
}

/**
 * A model-facing failure (bad argument, refused action). The message goes to the model as is; [page] is a failed
 * result that still shows the page (a stale ref answered with the current element list).
 */
class ToolError(message: String, val page: ToolOutcome? = null) : Exception(message)

/** Implemented by chat.run.ToolExecutor; this is how tools reach the loop without importing chat. */
interface ToolContext {
    val sessionId: String
    val browser: BrowserEngine

    /** The model's context window in tokens, null when unknown (read's part size). */
    val contextWindow: Int?

    /** The model takes images: browse attaches a picture of the page. */
    val vision: Boolean

    /** Names of the tools the model can call now; guidance in results names only these. */
    val exposed: Set<String>

    /**
     * The chat's work tab after the sensitive-URL and control checks (architecture 6.4, tools-redesign 4). Only a
     * [navigating] call (browse) creates it when missing and may leave a sensitive site from it; otherwise a missing
     * tab is an error. Implement with [BrowserEngine.acquireAiTab]; waits while the user controls the tab.
     */
    suspend fun workTab(navigating: Boolean = false): BrowserTab

    /**
     * Opens a hidden tab at [url], runs [block] in it on the main thread and always closes it. The landing URL is
     * checked against sensitive sites (an error then) and every URL is kept for cookie masking.
     */
    suspend fun <T> hiddenTab(url: String, block: suspend (BrowserTab) -> T): T

    /** What the running call waits for, shown in its step line; null clears it. */
    fun status(s: ToolStatus?, engine: String? = null)

    /** read's continuation per URL (and "(tab)"), for this chat. */
    val readCursors: MutableMap<String, ReadCursor>

    /** B-S4: [urls] were visited for this call's result (a cached read part): their cookie values are masked too. */
    fun markVisited(urls: Collection<String>)

    /**
     * SE 9 / B-M2: throws when an ALWAYS-approved call is no longer on the page the user approved. Called right before
     * the approved action runs, after any wait.
     */
    fun checkApprovedPage(tab: BrowserTab)

    /** B-S2: suspends until the user answers SC-13. */
    suspend fun requireApproval(request: ApprovalRequest): Boolean

    /** S-04: citation number for [url], stable within the current message. */
    fun numberSource(url: String, title: String, snippet: String?): Int

    /** B-08: stores a tool artifact (browse picture, approval preview) as an ARTIFACT attachment and returns its id. */
    suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String
}

/** Contents of the SC-13 dialog. */
data class ApprovalRequest(
    val toolName: String,
    val targetText: String?,
    val url: String?,
    /** SC-13 preview: artifact attachment id of a JPEG crop of the target element (never of a sensitive field). */
    val screenshotId: String? = null,
    /** execute: full code shown before running. */
    val code: String? = null,
    /** Why approval is needed; null for execute (always approved). */
    val reason: IrreversibleAction.Reason? = null,
)

/** A long page read in parts: [parts] of the text, [next] = index of the part the next call returns. */
data class ReadCursor(
    val url: String,
    val title: String,
    val parts: List<String>,
    var next: Int,
    /** Flags line shown with the first part. */
    val flags: String = "",
    /** "(tab)" cursor: the document and text start it was made from. */
    val doc: String? = null,
    val head: String? = null,
    /** Every URL the page was loaded through (redirects): masked again whenever a part is sent (B-S4). */
    val urls: List<String> = emptyList(),
)

/** B-05 request to hand the tab to the user; the loop pauses on it. */
data class HandoffRequest(val tabId: Int, val reason: String)

data class ToolOutcome(
    /** Page-derived text: the executor masks it and puts it inside the B-S5 boundary. Empty = none. */
    val text: String,
    val isError: Boolean = false,
    val summary: String? = null,
    val sources: List<Source> = emptyList(),
    val artifactIds: List<String> = emptyList(),
    val imageForModelId: String? = null,
    val handoff: HandoffRequest? = null,
    /** The user refused a CONDITIONAL approval inside the tool; the call is shown as DENIED. */
    val denied: Boolean = false,
    /** App-written line before the boundary: what was done ("Clicked e5 → new page."). */
    val lead: String = "",
    /** App-written guidance after the boundary ("[more: …]", the submit button, more elements). */
    val note: String = "",
) {
    companion object {
        const val DENIED_TEXT = "The user denied this action; do not retry it. Continue another way or ask the user."
        val DENIED = ToolOutcome("", isError = true, denied = true, lead = DENIED_TEXT)
    }
}
