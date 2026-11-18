package com.ouor.localchat.tools.browser

import android.view.KeyEvent
import com.ouor.localchat.R
import com.ouor.localchat.browser.safety.ElementInfo
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.Suggestions
import com.ouor.localchat.browser.script.TypeResult
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * Sets a field's value through the native setter + input/change events (React-compatible, architecture 7.4), or
 * chooses a select's option (tools-redesign 3-5). Search boxes get Enter by themselves unless suggestions open;
 * elsewhere a trailing newline in a one-line field is Enter. Never into password/OTP/card fields (B-S1); Enter in a
 * POST form and any input on a plain-http page ask first.
 */
object Type : Tool {
    override val name = "type"
    override val groups = setOf(ToolGroup.BROWSER)
    override val approval = ApprovalMode.CONDITIONAL
    override val labelRes = R.string.tool_type
    override val description =
        "Fill a text field, or choose an option of a select, by its ref from the latest element list. Replaces the field's " +
            "value; empty text clears it. Fields listed as searchbox are submitted automatically, unless suggestions open. " +
            "To press Enter in another one-line field, end text with a newline (in a multi-line field the newline stays as " +
            "text). Password, one-time-code and card fields are refused: use request_handoff. Returns the page after typing."
    override val parameters = params {
        refParam()
        string("text", "Text to enter, or the visible text or value of the option to choose.", required = true)
    }

    override fun argSummary(args: JsonObject) = args.optString("ref").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val ref = args.string("ref")
        val text = args.optString("text") ?: throw ToolError("Missing required argument 'text'.")
        return onMain {
            val tab = ctx.workTab()
            val at = ctx.locate(tab, ref)
            guardInput(ref, at.info, at.sensitive)
            val label = at.info.label()
            val select = at.info.tag == "select"
            if (!select && !at.editable) throw ToolError("$ref is not a text field or select; use click.")
            val plan = if (select) TypeRules.Plan(text, enter = false) else TypeRules.plan(at.info, text)
            val reason = when {
                plan.enter && IrreversibleAction.reasonForEnter(at.info) != null -> IrreversibleAction.Reason.POST_FORM
                tab.url.startsWith("http://", ignoreCase = true) -> IrreversibleAction.Reason.INSECURE_INPUT
                else -> null
            }
            if (!ctx.guardActivate(name, tab, reason, label, at)) return@onMain ToolOutcome.DENIED
            ctx.highlight(tab, at, if (select) R.string.browser_highlight_select else R.string.browser_highlight_type)
            var typed: TypeResult? = null
            var suggested = false
            val acted = ctx.act(tab) {
                val before = if (plan.auto) PageScript.call<Suggestions>(tab, "suggestions").n else 0
                typed = PageScript.call<TypeResult>(tab, "type", refArgs(ref) { put("text", plan.value) }, retry = false)
                if (plan.auto) {
                    // G-m2: an autocomplete field opens its suggestions; Enter there would submit before one is chosen.
                    tab.awaitReady(now())
                    suggested = PageScript.call<Suggestions>(tab, "suggestions").n > before
                }
                if (plan.enter && !suggested) tab.key(KeyEvent.KEYCODE_ENTER, 0)
            }
            val pressed = plan.enter && !suggested
            val r = typed
            // The model's own text, escaped so a newline in it cannot break the line (G-m12).
            val shown = JsonPrimitive(plan.value.take(100)).toString()
            val first = when {
                select -> "Selected $shown in $ref"
                else -> "Typed $shown into $ref" + (if (pressed) " and pressed Enter" else "") +
                    if (plan.keptNewline) " (in this multi-line field the newline stays as text; Enter was not pressed)" else ""
            }
            val shows = r?.value?.takeIf { !select && norm(it) != norm(plan.value).take(200) }?.let { "The field now shows \"$it\"." }.orEmpty()
            val notes = listOfNotNull(
                "Suggestions opened under the field, so Enter was not pressed. Click one in the list below, or type again ending with a newline to submit."
                    .takeIf { suggested },
                r?.submitRef?.takeIf { !pressed }?.let { "Not submitted; the form's submit button is $it." },
            )
            ctx.actionResult(tab, first, acted, label, top = shows, note = notes.joinToString("\n"))
        }
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()
}

/** type's Enter (tools-redesign 3-5), pure. */
object TypeRules {
    /** [value] goes in; [enter] = press Enter after it; [auto] = Enter only because it is a search box; [keptNewline] = a multi-line field kept it. */
    data class Plan(val value: String, val enter: Boolean, val auto: Boolean = false, val keptNewline: Boolean = false)

    /** Empty text only clears; multi-line fields keep newlines as text; a trailing newline elsewhere is Enter; search boxes get Enter. */
    fun plan(i: ElementInfo, text: String): Plan = when {
        text.isEmpty() -> Plan(text, enter = false)
        i.tag != "input" -> Plan(text, enter = false, keptNewline = text.endsWith("\n"))
        text.endsWith("\n") -> Plan(text.trimEnd('\n', '\r'), enter = true)
        else -> Plan(text, enter = i.search, auto = i.search)
    }
}
