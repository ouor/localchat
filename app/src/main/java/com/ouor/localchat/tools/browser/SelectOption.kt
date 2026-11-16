package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.browser.script.SelectResult
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.optStringList
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object SelectOption : Tool {
    override val name = "select_option"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_select_option
    override val description =
        "Choose option(s) of a <select> (combobox/listbox in the snapshot) by visible text or value. " +
            "For custom dropdowns, click the option instead."
    override val parameters = params {
        targetParam()
        stringArray("values", "Option texts or values; several only for multi-selects.", required = true)
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = args.optString("target").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val target = args.string("target")
        val values = args.optStringList("values")?.takeIf { it.isNotEmpty() } ?: throw ToolError("Missing required argument 'values'.")
        return onMain {
            val tab = ctx.tabFor(args)
            val at = locate(tab, target)
            ctx.highlight(tab, at, R.string.browser_highlight_select)
            val r = PageScript.call<SelectResult>(tab, "select", targetArgs(target) { putJsonArray("values") { values.forEach { add(JsonPrimitive(it)) } } })
            ToolOutcome("Selected ${r.selected.joinToString { "\"$it\"" }} in ${at.info.label()}.${dialogSuffix(tab)}", summary = r.selected.joinToString())
        }
    }
}
