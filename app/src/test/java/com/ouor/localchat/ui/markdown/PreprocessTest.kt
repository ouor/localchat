package com.ouor.localchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessTest {

    private fun md(vararg s: String) = s.map { MdSegment.Markdown(it) }

    @Test fun `paragraphs split at blank lines`() {
        assertEquals(md("a", "b"), splitSegments("a\n\nb"))
    }

    @Test fun `closed blocks stay identical while the tail streams`() {
        val first = splitSegments("Intro text.\n\n- one\n- tw")
        val later = splitSegments("Intro text.\n\n- one\n- two\n\nMore")
        assertEquals(first[0], later[0])
        assertEquals(3, later.size)
    }

    @Test fun `loose list items stay in one block`() {
        assertEquals(md("1. a\n\n2. b"), splitSegments("1. a\n\n2. b"))
    }

    @Test fun `indented continuation stays in the block`() {
        assertEquals(1, splitSegments("- item\n\n  continued").size)
    }

    @Test fun `blank lines inside a fence do not split`() {
        val text = "```kotlin\nval a = 1\n\nval b = 2\n```\n\nafter"
        assertEquals(md("```kotlin\nval a = 1\n\nval b = 2\n```", "after"), splitSegments(text))
    }

    @Test fun `unclosed fence keeps everything raw`() {
        assertEquals(1, splitSegments("```\ncode\n\n\$\$x\$\$").size)
    }

    @Test fun `display math becomes its own segment`() {
        assertEquals(
            listOf(MdSegment.Markdown("before"), MdSegment.Math("x^2"), MdSegment.Markdown("after")),
            splitSegments("before\n\$\$x^2\$\$\nafter"),
        )
        assertEquals(listOf(MdSegment.Math("a\n+b")), splitSegments("\\[a\n+b\\]"))
    }

    @Test fun `unclosed display math stays raw while streaming`() {
        assertEquals(md("$$\\frac{a}{b}"), splitSegments("$$\\frac{a}{b}"))
    }

    @Test fun `inline math becomes tokens`() {
        val p = tokenizeInline("Energy \$E=mc^2\$ and \\(a+b\\).", emptySet())
        assertEquals("Energy ⦃m0⦄ and ⦃m1⦄.", p.text)
        assertEquals(listOf("E=mc^2", "a+b"), p.math)
    }

    @Test fun `currency is not math`() {
        val p = tokenizeInline("It costs \$5 and \$10 today, or $ 3 $.", emptySet())
        assertTrue(p.math.isEmpty())
    }

    @Test fun `unclosed inline math stays raw`() {
        assertEquals("so \$x^2 + ", tokenizeInline("so \$x^2 + ", emptySet()).text)
    }

    @Test fun `citations only for known sources`() {
        val p = tokenizeInline("Prices rose [1][2] but [9] and [link](u) and [x][1] stay.", setOf(1, 2))
        assertEquals("Prices rose ⦃c1⦄⦃c2⦄ but [9] and [link](u) and [x]⦃c1⦄ stay.", p.text)
    }

    @Test fun `colon after a citation and line-leading definition`() {
        // FB-1e: `a[1]` index exception dropped (lead gate) — a known source number after a letter is a badge.
        val p = tokenizeInline("See [1]: it says so. arr[1] is one.\n[2]: https://example.com", setOf(1, 2))
        assertEquals("See ⦃c1⦄: it says so. arr⦃c1⦄ is one.\n[2]: https://example.com", p.text)
    }

    // FB-1e: cases from s25 screenshots 1 and 5 (Korean text right before the bracket).
    @Test fun `korean sentence end citations`() {
        val src = setOf(14, 21, 22, 36)
        assertEquals("해이기도 합니다⦃c36⦄.", tokenizeInline("해이기도 합니다[36].", src).text)
        assertEquals("상태입니다⦃c22⦄⦃c36⦄.", tokenizeInline("상태입니다[22][36].", src).text)
        assertEquals("합니다.⦃c36⦄", tokenizeInline("합니다.[36]", src).text)
        assertEquals("• 참고로 없습니다⦃c21⦄.", tokenizeInline("• 참고로 없습니다[21].", src).text)
        assertEquals("- 대표적입니다⦃c14⦄.", tokenizeInline("- 대표적입니다[14].", src).text)
        assertEquals("**일레이나 22명이다⦃c14⦄**", tokenizeInline("**일레이나 22명이다[14]**", src).text)
    }

    @Test fun `citation lists and unknown numbers`() {
        val src = setOf(1, 2)
        assertEquals("다⦃c1⦄⦃c2⦄.", tokenizeInline("다[1, 2].", src).text)
        assertEquals("다⦃c1⦄⦃c2⦄", tokenizeInline("다[1,2]", src).text)
        assertEquals("다[99].", tokenizeInline("다[99].", src).text)
        assertEquals("다[1, 99].", tokenizeInline("다[1, 99].", src).text)
        assertEquals("![1](img) \\[1]", tokenizeInline("![1](img) \\[1]", src).text)
        assertEquals("다 `x[1]`\n```\n다[1]\n```", tokenizeInline("다 `x[1]`\n```\n다[1]\n```", src).text)
    }

    @Test fun `m-1 link targets and URLs keep their brackets`() {
        val src = setOf(1, 2)
        assertEquals("[a](https://x.com/p[1])", tokenizeInline("[a](https://x.com/p[1])", src).text)
        assertEquals("see https://x.com/a[2]b now", tokenizeInline("see https://x.com/a[2]b now", src).text)
        assertEquals("<https://x.com/a[2]>", tokenizeInline("<https://x.com/a[2]>", src).text)
        // A citation right after a link or URL still becomes a badge.
        assertEquals("[a](https://x.com/p[1])⦃c2⦄ and https://x.com ⦃c1⦄", tokenizeInline("[a](https://x.com/p[1])[2] and https://x.com [1]", src).text)
    }

    @Test fun `code spans and fences are untouched`() {
        val text = "use `\$a\$ [1]` here\n```\n\$b\$ [1]\n```\nand \$c\$ [1]"
        val p = tokenizeInline(text, setOf(1))
        assertEquals("use `\$a\$ [1]` here\n```\n\$b\$ [1]\n```\nand ⦃m0⦄ ⦃c1⦄", p.text)
        assertEquals(listOf("c"), p.math)
    }

    @Test fun `rule 1 inline is textstyle`() {
        assertEquals("\\textstyle \\sum_i i", MathRules.inline("\\sum_i i"))
    }

    @Test fun `rule 2 cases becomes array`() {
        val expected = "f=\\left\\{\\begin{array}{ll} a & b \\\\ c & d \\end{array}\\right."
        assertEquals(expected, MathRules.display("f=\\begin{cases} a & b \\\\ c & d \\end{cases}"))
        assertEquals(expected, MathRules.display("f=\\begin{dcases} a & b \\\\ c & d \\end{dcases}"))
    }

    @Test fun `rule 3 detects malformed tex`() {
        assertTrue(MathRules.isWellFormed("\\frac{a}{b} + \\left( x \\right) + \\{1\\}"))
        assertTrue(MathRules.isWellFormed("\\begin{pmatrix} a & b \\end{pmatrix}"))
        assertFalse(MathRules.isWellFormed("\\frac{a}{"))
        assertFalse(MathRules.isWellFormed("\\left( x"))
        assertFalse(MathRules.isWellFormed("\\begin{matrix} a & b"))
        assertFalse(MathRules.isWellFormed("\\begin{matrix} a \\end{pmatrix}"))
        assertFalse(MathRules.isWellFormed("a}"))
    }

    @Test fun `single line breaks become hard breaks`() {
        assertEquals("• one  \n• two  \n• three", hardBreaks("• one\n• two\n• three"))
        assertEquals("para one\n\npara two", hardBreaks("para one\n\npara two"))
        assertEquals("already  \nbroken", hardBreaks("already  \nbroken"))
        assertEquals("# Title\ntext", hardBreaks("# Title\ntext"))
    }

    @Test fun `code fences and tables stay as they are`() {
        val code = "```kotlin\nval a = 1\nval b = 2\n```\nafter"
        assertEquals(code, hardBreaks(code))
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(table, hardBreaks(table))
        assertEquals("text  \nmore\n\n| a |\n|---|", hardBreaks("text\nmore\n\n| a |\n|---|"))
    }

    /** Element types the library's parser (GFM, as mikepenz uses it) finds in the preprocessed text. */
    private fun parsed(md: String): List<Pair<String, String>> {
        val t = tokenizeInline(md, emptySet()).text
        val out = mutableListOf<Pair<String, String>>()
        fun walk(n: org.intellij.markdown.ast.ASTNode) {
            out += n.type.name to t.substring(n.startOffset, n.endOffset).replace(JOINER, "")
            n.children.forEach(::walk)
        }
        walk(org.intellij.markdown.parser.MarkdownParser(org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor()).buildMarkdownTreeFromString(t))
        return out
    }

    @Test fun `fb3 m6 strikethrough needs two tildes`() {
        // The parser paired these single tildes before.
        listOf("~a~ ~b~", "**~a~**", "~~a~", "1~2일과 3~4일").forEach { md ->
            assertTrue(md, parsed(md).none { it.first == "STRIKETHROUGH" })
        }
        assertEquals("~~취소~~", parsed("1~2와 ~~취소~~ 3~4").single { it.first == "STRIKETHROUGH" }.second)
        assertEquals("a `~b~` \\~", tokenizeInline("a `~b~` \\~", emptySet()).text) // code and escapes untouched
    }

    @Test fun `fb3 m6 bold closes before a Korean letter after punctuation`() {
        assertEquals("**\"강조\"**", parsed("이것은 **\"강조\"**입니다").single { it.first == "STRONG" }.second)
        assertEquals("**\"강조\"**", parsed("이것은**\"강조\"**를").single { it.first == "STRONG" }.second)
        assertEquals("*\"기울임\"*", parsed("*\"기울임\"*이다").single { it.first == "EMPH" && it.second.length > 1 }.second)
        // Already fine stays fine, and plain text is untouched.
        assertEquals("**강조**입니다", tokenizeInline("**강조**입니다", emptySet()).text)
        assertEquals("**굵게**", parsed("**굵게**.").single { it.first == "STRONG" }.second)
        assertEquals("* 목록", tokenizeInline("* 목록", emptySet()).text)
    }
}
