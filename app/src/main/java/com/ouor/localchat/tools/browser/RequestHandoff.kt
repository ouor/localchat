package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserTab
import com.ouor.localchat.tools.HandoffRequest
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

/** B-05: the work tab goes to the user until they give it back; the result is then the page as they left it ([returned]). */
object RequestHandoff : Tool {
    override val name = "request_handoff"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_request_handoff
    override val description =
        "Give your browser tab to the user and wait until they give it back. Use it for sign-in, passwords, CAPTCHAs, " +
            "payments, sensitive sites, or choices only the user can make. Returns the page as the user left it."
    override val parameters = params {
        string("reason", "Short instruction shown to the user, in the user's language (e.g. \"Please sign in\").", required = true)
    }

    override fun argSummary(args: JsonObject) = args.optString("reason").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val reason = args.string("reason")
        return onMain {
            val tab = ctx.browser.ownedAiTab(ctx.sessionId)
            // The loop pauses on handoff; when the user gives the tab back the executor replaces this result (architecture 7.7).
            ToolOutcome("", lead = "Handed the tab to the user: $reason", handoff = HandoffRequest(tab.id, reason))
        }
    }

    const val GIVEN_BACK = "The user gave the tab back."
    private const val RETURNED = "$GIVEN_BACK They may or may not have finished; check the page below."

    /**
     * The result once the user gave [tab] back (tools-redesign 3-7): the page as they left it. ToolExecutor checks a
     * closed tab and a sensitive site first. Main thread.
     */
    internal suspend fun returned(ctx: ToolContext, tab: BrowserTab): ToolOutcome {
        val since = now()
        val page = ctx.pageResult(tab, ctx.ready(tab, since), since)
        return ctx.withPicture(tab, page.copy(isError = false, lead = RETURNED), keep = false)
    }
}
