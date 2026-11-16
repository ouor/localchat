package com.ouor.localchat.ui.markdown

/**
 * The single preprocessing step before mikepenz (architecture 10.4). Pure, JVM-tested (PreprocessTest).
 *
 * 1. [splitSegments]: top-level Markdown blocks + display math. Closed blocks keep identical strings between
 *    streaming updates, so their parse/render is reused (R12); only the last block changes.
 * 2. [tokenizeInline]: inline math and `[n]` citations → placeholder tokens the annotator swaps for inline content.
 *    Code spans and fences are never touched. Unclosed math stays raw text (streaming).
 * 3. [MathRules]: the four latex-renderer workarounds from Phase 0 spike (a).
 */
sealed interface MdSegment {
    data class Markdown(val text: String) : MdSegment
    data class Math(val tex: String) : MdSegment
}

private val FENCE_OPEN = Regex("""^ {0,3}(`{3,}|~{3,})""")
private val LIST_ITEM = Regex("""^ {0,3}([-*+]|\d{1,9}[.)])\s""")

fun splitSegments(text: String): List<MdSegment> {
    val out = mutableListOf<MdSegment>()
    val cur = StringBuilder()
    var curIsList = false
    var fence: String? = null
    var blankSeen = false

    fun flush() {
        val s = cur.toString().trimEnd('\n', ' ')
        if (s.isNotBlank()) out += MdSegment.Markdown(s)
        cur.clear(); curIsList = false; blankSeen = false
    }
    fun add(line: String) {
        if (cur.isEmpty()) curIsList = LIST_ITEM.containsMatchIn(line)
        cur.append(line).append('\n')
    }

    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (fence != null) {
            add(line)
            val t = line.trimStart()
            val f = fence!!
            if (t.startsWith(f) && t.trimEnd().all { it == f[0] }) fence = null
            i++; continue
        }
        if (line.isBlank()) { if (cur.isNotEmpty()) { blankSeen = true; add(line) }; i++; continue }

        val trimmed = line.trim()
        val mathOpen = when {
            trimmed.startsWith("$$") -> "$$"
            trimmed.startsWith("\\[") -> "\\]"
            else -> null
        }
        if (mathOpen != null) {
            val closed = findDisplayMath(lines, i, mathOpen)
            if (closed != null) {
                flush()
                out += MdSegment.Math(closed.first.trim())
                i = closed.second + 1
                continue
            }
            // Unclosed (streaming): fall through and keep it as raw text.
        }

        val startsNew = blankSeen && !line.startsWith(" ") && !line.startsWith("\t") &&
            !(curIsList && LIST_ITEM.containsMatchIn(line))
        if (startsNew) flush()
        FENCE_OPEN.find(line)?.let { fence = it.groupValues[1].take(3) }
        add(line)
        i++
    }
    flush()
    return out
}

private val TABLE_ROW = Regex("""^\s*\|""")

/**
 * Chat convention (QA1 decision): a single line break inside a paragraph shows as a line break, so
 * "• a\n• b" lists from the model stay on separate lines. Adds a Markdown hard break (two spaces);
 * fenced code and table rows are left as they are. Display math is already a separate segment.
 */
fun hardBreaks(markdown: String): String {
    val lines = markdown.split('\n')
    var fence: String? = null
    return lines.mapIndexed { i, line ->
        val t = line.trimStart()
        if (fence != null) {
            if (t.startsWith(fence!!) && t.trimEnd().all { it == fence!![0] }) fence = null
            return@mapIndexed line
        }
        FENCE_OPEN.find(line)?.let { fence = it.groupValues[1].take(3); return@mapIndexed line }
        val next = lines.getOrNull(i + 1)
        val keep = next == null || line.isBlank() || next.isBlank() || line.endsWith("  ") || line.endsWith("\\") ||
            TABLE_ROW.containsMatchIn(line) || TABLE_ROW.containsMatchIn(next) || t.startsWith("#")
        if (keep) line else "$line  "
    }.joinToString("\n")
}

/** Returns (tex, lastLineIndex) when the display math starting at [start] is closed. */
private fun findDisplayMath(lines: List<String>, start: Int, close: String): Pair<String, Int>? {
    val first = lines[start].trim().drop(2)
    val sameLine = first.indexOf(close)
    if (sameLine >= 0) {
        // Only a whole-line formula ("$$x$$") becomes a block; trailing text keeps it inline-ish markdown.
        return if (first.substring(sameLine + 2).isBlank()) first.substring(0, sameLine) to start else null
    }
    val sb = StringBuilder(first)
    for (j in start + 1 until lines.size) {
        val l = lines[j]
        val k = l.indexOf(close)
        if (k >= 0) {
            if (l.substring(k + 2).isNotBlank()) return null
            sb.append('\n').append(l.substring(0, k))
            return sb.toString() to j
        }
        sb.append('\n').append(l)
    }
    return null
}

// ---------------- Inline tokens ----------------

const val TOKEN_OPEN = '⦃'
const val TOKEN_CLOSE = '⦄'
val INLINE_TOKEN = Regex("⦃([mc])(\\d+)⦄")

/** [text] with tokens `⦃mN⦄` (math index N in [math]) and `⦃cN⦄` (citation number N). */
data class InlineParts(val text: String, val math: List<String>)

// `$x$`: no space right inside the dollars, closing `$` not followed by a digit (avoids "$5 and $10").
private val INLINE_MATH = Regex("""(?<![\\$])\$(?=[^\s$])([^$\n]*?[^\s$\\])\$(?!\d)|\\\((.+?)\\\)""")
// `[1][2]` are adjacent citations (reference-style links `[x][1]` are rare in model output and render fine as badges).
// BUG-15: "[1]:" mid-sentence is a citation; only a line-leading "[1]:" is a reference definition.
// FB-1e: any letter may precede (Korean "다[36]"); `[1, 2]` is a citation list, kept raw unless every number is a source.
private val CITATION = Regex("""(?<![\\!])\[(\d{1,3}(?:\s*,\s*\d{1,3})*)](?!\()""")
private val CODE_SPAN = Regex("""(`+)[\s\S]*?\1""")

fun tokenizeInline(markdown: String, citable: Set<Int>): InlineParts {
    val math = mutableListOf<String>()
    val protectedRanges = protectedRanges(markdown)
    val sb = StringBuilder()
    var last = 0
    for (r in protectedRanges) {
        sb.append(tokenizePlain(markdown.substring(last, r.first), citable, math))
        sb.append(markdown, r.first, r.last + 1)
        last = r.last + 1
    }
    sb.append(tokenizePlain(markdown.substring(last), citable, math))
    return InlineParts(sb.toString(), math)
}

private fun tokenizePlain(s: String, citable: Set<Int>, math: MutableList<String>): String {
    if (s.isEmpty()) return s
    val withMath = INLINE_MATH.replace(s) { m ->
        math += m.groupValues[1].ifEmpty { m.groupValues[2] }
        "$TOKEN_OPEN" + "m${math.size - 1}$TOKEN_CLOSE"
    }
    if (citable.isEmpty()) return withMath
    return CITATION.replace(withMath) { m ->
        val ns = m.groupValues[1].split(',').map { it.trim().toInt() }
        val definition = withMath.getOrNull(m.range.last + 1) == ':' &&
            withMath.substring(0, m.range.first).substringAfterLast('\n').isBlank()
        if (ns.all { it in citable } && !definition) ns.joinToString("") { "$TOKEN_OPEN" + "c$it$TOKEN_CLOSE" } else m.value
    }
}

/** Fenced code blocks and inline code spans (sorted, non-overlapping). */
private fun protectedRanges(md: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var offset = 0
    var fenceStart = -1
    var fence: String? = null
    for (line in md.split('\n')) {
        val end = offset + line.length
        if (fence == null) {
            FENCE_OPEN.find(line)?.let { fence = it.groupValues[1].take(3); fenceStart = offset }
        } else if (line.trimStart().startsWith(fence!!)) {
            ranges += fenceStart until minOf(end + 1, md.length).coerceAtLeast(fenceStart + 1)
            fence = null
        }
        offset = end + 1
    }
    if (fence != null) ranges += fenceStart until md.length
    val result = ranges.toMutableList()
    CODE_SPAN.findAll(md).forEach { m -> if (ranges.none { m.range.first in it }) result += m.range }
    return result.sortedBy { it.first }
}

// ---------------- latex-renderer workarounds (phase0.md 3(a)) ----------------

object MathRules {
    /** Rule 1 + 2: inline math is text style (the library renders display size otherwise). */
    fun inline(tex: String): String = "\\textstyle " + rewriteCases(tex.trim())

    /** Rule 2. */
    fun display(tex: String): String = rewriteCases(tex.trim())

    /** Rule 2: `cases`/`dcases` swap columns in the library → equivalent `array` inside `\left\{ … \right.`. */
    fun rewriteCases(tex: String): String = tex
        .replace(Regex("""\\begin\{d?cases\}"""), "\\\\left\\\\{\\\\begin{array}{ll}")
        .replace(Regex("""\\end\{d?cases\}"""), "\\\\end{array}\\\\right.")

    /**
     * Rule 3 (R-03 is decided by the app): unbalanced `{}`, `\left`/`\right` or `\begin`/`\end` → show source.
     * The caller also falls back when the measured width is 0.
     * Rule 4 (short hyphen glyph for minus) is accepted as is.
     */
    fun isWellFormed(tex: String): Boolean {
        var depth = 0
        var i = 0
        while (i < tex.length) {
            val ch = tex[i]
            if (ch == '\\' && i + 1 < tex.length) { i += 2; continue } // escaped char (\{ \} \\)
            if (ch == '{') depth++
            if (ch == '}') { depth--; if (depth < 0) return false }
            i++
        }
        if (depth != 0) return false
        val lefts = Regex("""\\left(?![a-zA-Z])""").findAll(tex).count()
        val rights = Regex("""\\right(?![a-zA-Z])""").findAll(tex).count()
        if (lefts != rights) return false
        val stack = ArrayDeque<String>()
        // `}` escaped: Android's ICU regex rejects a bare one (JVM accepts it, so unit tests could not catch this).
        Regex("""\\(begin|end)\{([^\}]*)\}""").findAll(tex).forEach { m ->
            if (m.groupValues[1] == "begin") stack.addLast(m.groupValues[2])
            else if (stack.removeLastOrNull() != m.groupValues[2]) return false
        }
        return stack.isEmpty()
    }
}
