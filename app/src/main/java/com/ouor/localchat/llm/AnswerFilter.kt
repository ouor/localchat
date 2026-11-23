package com.ouor.localchat.llm

import com.ouor.localchat.tools.UNTRUSTED_TAG
import java.util.logging.Logger

/**
 * FB3 QA M1, m1: keeps two kinds of model-written markup out of the answer text, across chunk borders.
 * - DeepSeek's tool-call markup ("<｜DSML｜…", fullwidth bars) written as text: dropped with the rest of the attempt.
 * - The page-content boundary tags the app puts around page text ([UNTRUSTED_TAG]): the tags are removed.
 * One instance per attempt.
 */
internal class AnswerFilter {
    private val held = StringBuilder()
    private var dropping = false

    /** Returns the part of [piece] that may be shown now; a possible start of markup is held for the next piece. */
    fun feed(piece: String): String {
        if (dropping) return ""
        val s = BOUNDARY.replace(held.append(piece).toString(), "")
        held.clear()
        DSML.find(s)?.let {
            // shortcut: everything after the markup is dropped too; a model writing on after its calls would lose that text.
            dropping = true
            log.warning("tool-call markup removed from the answer")
            return s.substring(0, it.range.first).trimEnd()
        }
        val lt = s.lastIndexOf('<')
        if (lt >= 0 && s.length - lt < MAX_HELD && isPrefix(s.substring(lt))) {
            held.append(s, lt, s.length)
            return s.substring(0, lt)
        }
        return s
    }

    /** What is still held at the end of the attempt (never markup). */
    fun flush(): String = if (dropping) "" else held.toString().also { held.clear() }

    private fun isPrefix(tail: String) =
        DSML_PREFIX.matches(tail) || TAGS.any { it.startsWith(tail, ignoreCase = true) }

    companion object {
        private val TAGS = listOf("<$UNTRUSTED_TAG>", "</$UNTRUSTED_TAG>")
        private val BOUNDARY = Regex("</?$UNTRUSTED_TAG>", RegexOption.IGNORE_CASE)
        private val DSML = Regex("<[｜|][｜|\\s]{0,3}DSML")
        private val DSML_PREFIX = Regex("<(?:[｜|][｜|\\s]{0,3}(?:D(?:S(?:M)?)?)?)?")
        private const val MAX_HELD = 32
        private val log = Logger.getLogger("localchat")
    }
}
