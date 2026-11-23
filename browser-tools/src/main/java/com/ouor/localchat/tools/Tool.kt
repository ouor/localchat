package com.ouor.localchat.tools

import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.browser.ToolStatus
import kotlinx.serialization.json.JsonObject

/**
 * A model-callable tool (browser-layers 3-7). Implementations are stateless `object`s; every dependency comes through
 * [ToolContext], so tests use a fake context and no Hilt. Layer 2 does the job only: no approvals, no site checks.
 *
 * [run] may be called from any dispatcher (tools switch to Main for WebView work) and may throw [ToolError]; the
 * executor turns every exception into a failed [ToolOutcome], which still goes through the policy chain.
 */
interface Tool {
    /** Name exposed to the model, e.g. "click". */
    val name: String
    val groups: Set<ToolGroup>

    /** Facts the runner and policies read instead of the name. */
    val facts: ToolFacts

    /** English description for the model. */
    val description: String

    /** JSON schema of the arguments. */
    val parameters: JsonObject

    /** Short argument summary for the step line (URL, ref, query). */
    fun argSummary(args: JsonObject): String

    suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome
}

enum class ToolGroup { SEARCH, BROWSER }

/**
 * Which tab a call acts on ([Targets.resolve]): NONE tabs, close_tab; PAGE navigate, look, execute, ask_user, scroll
 * without ref; REF click, type, scroll with ref; OPEN open; READ read; SEARCH web_search.
 */
enum class TargetRule { NONE, PAGE, REF, OPEN, READ, SEARCH }

/** [changesPage]: the call may change the page (click, type, execute, open, navigate, scroll). */
data class ToolFacts(val target: TargetRule, val browser: Boolean, val changesPage: Boolean)

/**
 * A model-facing failure (bad argument, closed tab). The message goes to the model as the app's sentence; [page] is a
 * failed result that still shows the page (a stale ref answered with the current element list).
 */
class ToolError(message: String, val page: ToolOutcome? = null) : Exception(message)

/** Implemented by agent.BrowserToolRunner; this is how tools reach the loop without knowing it. */
interface ToolContext {
    val sessionId: String

    /** The same API the host UI uses. */
    val browser: BrowserEngine

    /** The tab [Targets.resolve] chose and the runner locked and woke; null for NONE tools. */
    val target: BrowserTab?

    /** This chat's memory of tabs (current tab, tabs it opened, its search tab). */
    val chat: ChatTabs

    /** The model's context window in tokens, null when unknown (read's part size, the list budget). */
    val contextWindow: Int?

    /** The model takes images: results that list a page attach a picture of it. */
    val vision: Boolean

    /** read's position per tab, for this chat. */
    val readCursors: MutableMap<Int, ReadCursor>

    /** What the running call waits for, shown in its step line; null clears it. */
    fun status(s: ToolStatus?, engine: String? = null)

    /** Citation number for [url], stable within the current message. */
    fun numberSource(url: String, title: String, snippet: String?): Int

    /** Stores a tool artifact (a page picture) as an ARTIFACT attachment and returns its id. */
    suspend fun saveArtifact(name: String, mime: String, bytes: ByteArray): String
}

/**
 * Where read stopped on a tab: [doc] the document, [anchor] the last 200 characters it returned, [part] the part
 * number that ended there, [turn] the [ChatTabs.turn] it was read in.
 */
/** Where a read ended: the text before [end] (its offset then) is [anchor]. */
data class ReadCursor(val doc: String?, val anchor: String, val part: Int, val turn: Int, val end: Int = 0)

/** ask_user: the user does [task] in [tabId] and taps Done; the loop pauses on it. */
data class UserStep(val tabId: Int, val task: String)

data class ToolOutcome(
    /** App-written line before the boundary: what was done and what changed. */
    val lead: String = "",
    /** Page-derived text: dialogs, field values, script results, the Page line, the element list. Empty = none. */
    val page: String = "",
    /** App-written guidance after the boundary: what the list left out, the submit button, the next part. */
    val note: String = "",
    val isError: Boolean = false,
    val summary: String? = null,
    val sources: List<Source> = emptyList(),
    val artifactIds: List<String> = emptyList(),
    val imageForModelId: String? = null,
    val userStep: UserStep? = null,
    /** The user said no to a confirmation; the call is shown as DENIED. */
    val denied: Boolean = false,
    /** A policy refused the call before it ran (set by the runner); shown as blocked by the app. */
    val refused: Boolean = false,
) {
    companion object {
        const val DENIED_TEXT = "The user denied this action; do not retry it. Continue another way or ask the user."
        val DENIED = ToolOutcome(lead = DENIED_TEXT, isError = true, denied = true)
    }
}
