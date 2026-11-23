package com.ouor.localchat.tools.browser

import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.UserStep
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

/**
 * browser-layers 3-5: the user does a step in a tab and taps Done; the loop waits on [UserStep] and the result is then
 * the page as they left it ([done]). No tab changes hands: the tab is the user's as much as before.
 */
object AskUser : Tool {
    override val name = "ask_user"
    override val groups = setOf(ToolGroup.BROWSER)
    override val facts = ToolFacts(TargetRule.PAGE, browser = true, changesPage = false)
    override val description =
        "Ask the user to do a step themselves in a tab and wait until they tap Done: signing in, a CAPTCHA, entering card " +
            "details, or a choice only they can make. Returns the page as they left it. For questions, ask in your reply instead."
    override val parameters = params {
        string("task", "Short instruction shown to the user, in the user's language (e.g. \"Please sign in\").", required = true)
        tabParam()
    }

    override fun argSummary(args: JsonObject) = args.optString("task").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val task = args.string("task")
        val tab = ctx.tab()
        return ToolOutcome(lead = "Asked the user: $task", userStep = UserStep(tab.id, task))
    }

    const val DONE = "The user tapped Done; check the page below."

    /** The result once the user tapped Done: the tab's page, as look lists it. */
    suspend fun done(ctx: ToolContext): ToolOutcome = onMain { Look.list(ctx) }.let { it.copy(lead = DONE, isError = false) }
}
