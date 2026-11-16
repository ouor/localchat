package com.ouor.localchat.speech

/** Pure text rules for read-aloud (M-09). */
internal object SpeechText {

    /** Markdown to what a person would read out: no code blocks, symbols, URLs or `[n]` citations. */
    fun plain(markdown: String): String = markdown
        .replace(Regex("""(?ms)^ {0,3}(```|~~~).*?^ {0,3}\1[^\n]*$"""), "")   // fenced code blocks
        .replace(Regex("""(?ms)^ {0,3}(```|~~~).*"""), "")                       // unclosed fence (cut-off reply)
        .replace(Regex("""\$\$.*?\$\$""", RegexOption.DOT_MATCHES_ALL), "")       // display math
        .replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")                        // images -> alt
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")                         // links -> text
        .replace(Regex("""\[\d+]"""), "")                                         // citation markers
        .replace(Regex("""<[^>\n]+>"""), "")                                      // html tags
        .replace(Regex("""https?://\S+"""), "")                                   // bare urls
        .replace(Regex("""(?m)^ {0,3}#{1,6}[ \t]*"""), "")                        // headings
        .replace(Regex("""(?m)^ {0,3}>[ \t]?"""), "")                             // blockquotes
        .replace(Regex("""(?m)^[ \t]*(?:[-*+]|\d+[.)])[ \t]+(?:\[[ xX]][ \t]+)?"""), "") // list markers, task boxes
        .replace(Regex("""(?m)^[ \t]*(?:[-*_][ \t]*){3,}$"""), "")                // horizontal rules
        .replace(Regex("""(?m)^[ \t]*\|?(?:[ \t]*:?-+:?[ \t]*\|)+[ \t]*:?-*:?[ \t]*$"""), "") // table separator rows
        .replace("|", " ")
        .replace(Regex("""[*_~`]+"""), "")                                        // emphasis, inline code ticks
        .lines().joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    /**
     * Splits [text] into pieces of at most [max] chars (TextToSpeech.getMaxSpeechInputLength),
     * preferring paragraph, then sentence, then word boundaries.
     */
    fun chunks(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = listOf("\n\n", "\n", ". ", "。", "! ", "? ", " ")
                .map { window.lastIndexOf(it).let { i -> if (i > 0) i + it.length else -1 } }
                .firstOrNull { it > max / 3 } ?: max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotBlank() }
    }
}
