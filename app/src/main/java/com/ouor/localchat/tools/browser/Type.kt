package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.TypeResult
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optBool
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * Sets a field's value through the native setter + input/change events (React-compatible, architecture 7.4).
 * Never into password/OTP/card fields (B-S1); on a plain-http page every call needs approval (team decision 1).
 */
object Type : Tool {
    override val name = "type"
    override val groups = setOf(ToolGroup.BROWSER)
    override val approval = ApprovalMode.CONDITIONAL
    override val labelRes = R.string.tool_type
    override val description =
        "Type text into a text field in your tab (replaces its value unless clear=false). Password, one-time-code " +
            "and card fields are refused: ask the user with request_handoff. To submit, press Enter with keyboard " +
            "or click the button."
    override val parameters = params {
        targetParam()
        string("text", "Text to enter.", required = true)
        bool("clear", "Replace the current value (default true). false appends.")
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = args.optString("target").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val target = args.string("target")
        val text = args.optString("text") ?: throw ToolError("Missing required argument 'text'.")
        val clear = args.optBool("clear") ?: true
        return onMain {
            val tab = ctx.tabFor(args)
            val at = locate(tab, target)
            guardInput(at.info, at.sensitive)
            if (!at.editable) throw ToolError("${at.info.label()} is not a text field.")
            val insecure = if (tab.url.startsWith("http://", ignoreCase = true)) IrreversibleAction.Reason.INSECURE_INPUT else null
            if (!ctx.guardActivate(name, tab, insecure, at.info.label())) return@onMain ToolOutcome.DENIED
            ctx.highlight(tab, at, R.string.browser_highlight_type)
            val r = PageScript.call<TypeResult>(tab, "type", targetArgs(target) { put("text", text); put("clear", clear) })
            ToolOutcome("Typed into ${at.info.label()}. Value now: \"${r.value}\"${dialogSuffix(tab)}", summary = at.info.label())
        }
    }
}
