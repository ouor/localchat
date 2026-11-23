package com.ouor.localchat.policy

import android.graphics.Bitmap
import com.ouor.localchat.browser.Handled
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.script.Located
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolOutcome
import kotlinx.serialization.json.JsonObject

/** A link of the tool call chain (browser-layers 4-1): BrowserToolRunner asks every one in [Policies] order. */
interface ToolPolicy : Policy {
    fun onTurnStart(sessionId: String) {}
    fun onTurnEnd(sessionId: String) {}

    /** Before the tool runs. The first Refuse or Confirm stops the chain. */
    suspend fun before(call: PolicyCall): Verdict = Verdict.Allow

    /** After it ran: success, failure and exceptions alike, folded in order. */
    fun after(call: PolicyCall, facts: CallFacts, result: ToolOutcome): ToolOutcome = result
}

data class PolicyCall(
    val sessionId: String,
    val tool: String,
    val toolFacts: ToolFacts,
    val args: JsonObject,
    /** Uptime ms the call started. */
    val startedAt: Long,
    /** The target tab; null for a new tab (its destination is in [args]) or a call without a tab. */
    val tab: TabInfo?,
    /** Label, role, frame and form submit label of a ref on the target tab, without scrolling; null when gone. */
    val describe: suspend (ref: String) -> Located?,
    /** A picture of a ref's element for a confirmation preview. */
    val captureElement: suspend (ref: String) -> Bitmap?,
)

data class CallFacts(
    /** The target tab when the call ended (a new tab included). */
    val tabAfter: TabInfo?,
    /** Tabs this call opened (a new tab, popups). */
    val opened: List<Int>,
    /** Main-frame URLs the target tab requested during the call, redirects included. */
    val mainRequests: List<String>,
    /** Layer 1's public cookie read. */
    val cookieHeader: (url: String) -> String?,
    /** The target tab's events during the call and how they were answered. */
    val events: List<Handled>,
)

sealed interface Verdict {
    data object Allow : Verdict

    /** [message] goes to the model as the app's sentence. */
    data class Refuse(val message: String) : Verdict

    /** The user confirms (SC-13) before the call goes on; a no is DENIED. */
    data class Confirm(val request: ApprovalRequest) : Verdict
}

/** What the confirmation sheet shows: generic fields only, no reason codes. */
data class ApprovalRequest(val title: String, val target: String, val preview: Bitmap?, val pageUrl: String?)
