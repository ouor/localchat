package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.DialogKind
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.ApprovalRequest
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optBool
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

/** Answers the alert/confirm/prompt an AI tab is blocked on. Accepting confirm() needs approval (B-S2). */
object DialogHandle : Tool {
    override val name = "dialog_handle"
    override val groups = setOf(ToolGroup.BROWSER)
    override val approval = ApprovalMode.CONDITIONAL
    override val labelRes = R.string.tool_dialog_handle
    override val description =
        "Answer the JavaScript alert, confirm or prompt dialog open in your tab. Accepting a confirm asks the user first."
    override val parameters = params {
        bool("accept", "true = OK / accept, false = Cancel / dismiss.", required = true)
        string("text", "Answer for a prompt dialog.")
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = if (args.optBool("accept") == true) "accept" else "dismiss"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val accept = args.optBool("accept") ?: throw ToolError("Missing required argument 'accept'.")
        val text = args.optString("text")
        return onMain {
            val tab = ctx.tabFor(args)
            val d = tab.dialog.value ?: throw ToolError("No dialog is open.")
            if (accept && d.kind == DialogKind.CONFIRM) {
                val ok = ctx.requireApproval(ApprovalRequest(name, d.message, tab.url, reason = IrreversibleAction.Reason.CONFIRM_DIALOG))
                if (!ok) return@onMain ToolOutcome.DENIED
            }
            tab.answerDialog(accept, text)
            val kind = d.kind.name.lowercase()
            ToolOutcome("${if (accept) "Accepted" else "Dismissed"} $kind \"${d.message.take(200)}\". ${settle(tab)}", summary = kind)
        }
    }
}
