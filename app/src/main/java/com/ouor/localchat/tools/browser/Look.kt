package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.tools.TargetRule
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolFacts
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

/** browser-layers 3-5: a tab's elements with refs once it is ready, the part on screen first. */
object Look : Tool {
    override val name = "look"
    override val groups = setOf(ToolGroup.BROWSER)
    override val facts = ToolFacts(TargetRule.PAGE, browser = true, changesPage = false)
    override val labelRes = R.string.tool_look
    override val description =
        "List the elements of a tab's page with refs, the part on screen first. Use it for a page you have not acted on yet, " +
            "such as a tab the user opened, or after the user did something."
    override val parameters = params { tabParam() }

    override fun argSummary(args: JsonObject) = args.optString("tab").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome = onMain { list(ctx) }

    /** The call's tab listed; also ask_user's result once the user tapped Done. */
    internal suspend fun list(ctx: ToolContext): ToolOutcome {
        val tab = ctx.tab()
        val since = now()
        return ctx.withPicture(tab, ctx.pageResult(tab, ctx.ready(tab, since), since), keep = true)
    }
}
