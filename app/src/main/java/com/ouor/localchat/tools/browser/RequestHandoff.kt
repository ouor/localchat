package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.tools.HandoffRequest
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optInt
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

object RequestHandoff : Tool {
    override val name = "request_handoff"
    override val groups = setOf(ToolGroup.SEARCH, ToolGroup.BROWSER)
    override val labelRes = R.string.tool_request_handoff
    override val description =
        "Hand your tab to the user and pause until they give it back. Use it for sign-in, passwords, CAPTCHAs, " +
            "payments, sensitive sites or decisions the user must make. Take a new snapshot after they return."
    override val parameters = params {
        string("reason", "Short instruction shown to the user, in the user's language (e.g. \"Please sign in\").", required = true)
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = args.optString("reason").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val reason = args.string("reason")
        val tabId = args.optInt("tabId")
        return onMain {
            val tab = ctx.browser.ownedAiTab(ctx.sessionId, tabId)
            // The loop pauses on handoff; when the user gives the tab back the executor replaces this text (architecture 7.7).
            ToolOutcome("Handed tab ${tab.id} to the user: $reason", handoff = HandoffRequest(tab.id, reason))
        }
    }
}
