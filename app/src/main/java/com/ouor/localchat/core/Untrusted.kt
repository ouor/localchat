package com.ouor.localchat.core

/**
 * The page-content boundary tag (browser-layers 3-3). In core so tools.ToolResultFormat and chat.context (wrap page
 * text) and llm.AnswerFilter (strips it from answers; llm may depend on core only) share one literal.
 */
const val UNTRUSTED_TAG = "untrusted_page_content"

/** The boundary around page-derived text. One literal, one escape rule. */
object Untrusted {
    const val TAG = UNTRUSTED_TAG

    /** Pages cannot open or close the boundary themselves (SE 5): `<untrusted...` and `</untrusted...` lose their `<`. */
    fun wrap(text: String): String =
        "<$TAG>\n" + text.replace(ESCAPE) { "&lt;" + it.value.drop(1) } + "\n</$TAG>"

    /** A tool result as the model reads it: the app's [lead], the [page] text inside the boundary, the app's [note]. */
    fun result(lead: String, page: String, note: String): String =
        listOf(lead, if (page.isEmpty()) "" else wrap(page), note).filter { it.isNotEmpty() }.joinToString("\n")

    private val ESCAPE = Regex("</?\\s*$TAG", RegexOption.IGNORE_CASE)
}
