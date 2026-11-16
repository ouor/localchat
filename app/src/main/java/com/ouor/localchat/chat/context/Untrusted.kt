package com.ouor.localchat.chat.context

/** The B-S5 boundary around page-derived text. One literal, one escape rule. */
object Untrusted {
    const val TAG = "untrusted_page_content"

    /** Pages cannot open or close the boundary themselves (SE 5): `<untrusted...` and `</untrusted...` lose their `<`. */
    fun wrap(text: String): String =
        "<$TAG>\n" + text.replace(ESCAPE) { "&lt;" + it.value.drop(1) } + "\n</$TAG>"

    private val ESCAPE = Regex("</?\\s*$TAG", RegexOption.IGNORE_CASE)
}
