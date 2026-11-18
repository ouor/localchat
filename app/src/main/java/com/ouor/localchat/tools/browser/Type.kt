package com.ouor.localchat.tools.browser

import android.view.KeyEvent
import com.ouor.localchat.R
import com.ouor.localchat.browser.safety.ElementInfo
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.script.PageScript
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
import kotlinx.serialization.json.put

/**
 * Sets a field's value through the native setter + input/change events (React-compatible, architecture 7.4), or
 * chooses a select's option (tools-redesign 3-5). Search boxes get Enter by themselves; elsewhere a trailing newline
 * is Enter. Never into password/OTP/card fields (B-S1); Enter in a POST form and any input on a plain-http page ask first.
 */
object Type : Tool {
    override val name = "type"
    override val groups = setOf(ToolGroup.BROWSER)
    override val approval = ApprovalMode.CONDITIONAL
    override val labelRes = R.string.tool_type
    override val description =
        "Fill a text field, or choose an option of a select, by its ref from the latest element list. Replaces the field's " +
            "value. Search boxes are submitted automatically; to press Enter in any other field, end text with a newline. " +
            "Password, one-time-code and card fields are refused: use request_handoff. Returns the page after typing."
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
            val at = locate(tab, ref)
            guardInput(at.info, at.sensitive)
            val label = at.info.label()
            val select = at.info.tag == "select"
            if (!select && !at.editable) throw ToolError("$label is not a text field or select; use click.")
            val (value, enter) = if (select) text to false else TypeRules.enter(at.info, text)
            val reason = when {
                enter && IrreversibleAction.reasonForEnter(at.info) != null -> IrreversibleAction.Reason.POST_FORM
                tab.url.startsWith("http://", ignoreCase = true) -> IrreversibleAction.Reason.INSECURE_INPUT
                else -> null
            }
            if (!ctx.guardActivate(name, tab, reason, label, at)) return@onMain ToolOutcome.DENIED
            ctx.highlight(tab, at, if (select) R.string.browser_highlight_select else R.string.browser_highlight_type)
            var typed: TypeResult? = null
            val acted = ctx.act(tab) {
                typed = PageScript.call<TypeResult>(tab, "type", refArgs(ref) { put("text", value) })
                if (enter) tab.key(KeyEvent.KEYCODE_ENTER, 0)
            }
            val r = typed
            val first = if (select) {
                "Selected \"${r?.selected ?: value}\" in $label"
            } else {
                "Typed \"${value.take(100)}\" into $label" + if (enter) " and pressed Enter" else ""
            }
            val shows = r?.value?.takeIf { !select && norm(it) != norm(value).take(200) }?.let { " The field now shows \"$it\"." }.orEmpty()
            val submit = r?.submitRef?.takeIf { !enter }?.let { " Not submitted; the form's submit button is ${r.submitLabel} [ref=$it]." }.orEmpty()
            val result = ctx.actionResult(tab, first, acted, label)
            // The notes go after the change on the first line.
            val lines = result.text.split('\n', limit = 2)
            result.copy(text = lines[0] + shows + submit + lines.drop(1).joinToString("") { "\n$it" })
        }
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()
}

/** type's Enter (tools-redesign 3-5), pure. */
object TypeRules {
    private val SEARCH_NAMES = setOf("q", "query", "search", "keyword", "keywords", "s", "search_query", "wd")
    private val SEARCH_WORD = Regex("search|검색", RegexOption.IGNORE_CASE)

    /** A one-line search field: Enter only runs the search. A combobox is left out (Enter may pick a suggestion). */
    fun isSearchBox(i: ElementInfo): Boolean = i.role != "combobox" && (
        i.type == "search" || i.role == "searchbox" || i.search || i.nameAttr?.lowercase() in SEARCH_NAMES ||
            i.name?.let(SEARCH_WORD::containsMatchIn) == true || i.placeholder?.let(SEARCH_WORD::containsMatchIn) == true
        )

    /** (text to put in, press Enter after). Multi-line fields keep newlines as text; a trailing newline elsewhere is Enter. */
    fun enter(i: ElementInfo, text: String): Pair<String, Boolean> = when {
        i.tag != "input" -> text to false
        text.endsWith("\n") -> text.trimEnd('\n', '\r') to true
        else -> text to isSearchBox(i)
    }
}
