package com.ouor.localchat.tools.browser

import com.ouor.localchat.R
import com.ouor.localchat.browser.PdfPrinter
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.params
import kotlinx.serialization.json.JsonObject

/** B-08: the PDF becomes a chat attachment the user can share or save. */
object PrintToPdf : Tool {
    override val name = "print_to_pdf"
    override val groups = setOf(ToolGroup.BROWSER)
    override val labelRes = R.string.tool_print_to_pdf
    override val description = "Save the page in your tab as an A4 PDF attached to the chat, for the user to keep or share."
    override val parameters = params { tabIdParam() }

    override fun argSummary(args: JsonObject) = ""

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val tab = onMain { ctx.tabFor(args).also { it.awaitLoaded(LOAD_TIMEOUT) } }
        val bytes = PdfPrinter.print(tab)
        val fileName = tab.title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(60).ifEmpty { "page" } + ".pdf"
        val id = ctx.saveArtifact(fileName, "application/pdf", bytes)
        return ToolOutcome("Saved $fileName (${bytes.size / 1024} KB) and attached it to the chat. ${pageLine(tab)}", artifactIds = listOf(id), summary = fileName)
    }
}
