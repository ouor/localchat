package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.tools.HandoffRequest
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

/** B-05: the work tab goes to the user until they give it back; the result is then the page as they left it (ToolExecutor.afterHandoff). */
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
            // The loop pauses on handoff; when the user gives the tab back the executor replaces this text (architecture 7.7).
            ToolOutcome("Handed the tab to the user: $reason", handoff = HandoffRequest(tab.id, reason))
        }
    }
}
