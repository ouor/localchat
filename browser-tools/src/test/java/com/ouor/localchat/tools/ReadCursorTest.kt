package com.ouor.localchat.tools

import com.ouor.localchat.tools.browser.ReadRules
import com.ouor.localchat.tools.browser.listBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** read's position (browser-layers 3-5), pure. */
class ReadCursorTest {
    /** Paragraphs "p00 xxx e00.", "p01 xxx e01.", … each 300 characters, separated by blank lines. */
    private fun text(from: Int, to: Int) = (from until to).joinToString("\n\n") { "p%02d ".format(it) + "x".repeat(291) + " e%02d.".format(it) }

    private val size = 1000 // three paragraphs per part

    @Test fun `a long text goes on part by part after the anchor`() {
        val t = text(0, 10)
        val first = ReadRules.plan(t, size, "d", null, null, turn = 1, screenAt = null)
        assertEquals(0, first.index)
        assertEquals(4, first.count)
        assertTrue(first.text.startsWith("p00 "))
        assertEquals(ReadRules.ANCHOR, first.cursor.anchor.length)
        val second = ReadRules.plan(t, size, "d", first.cursor, null, turn = 1, screenAt = null)
        assertEquals(1, second.index)
        assertTrue(second.text, second.text.startsWith("p03 "))
        assertFalse(second.restarted || second.earlier)
    }

    @Test fun `after the end there is nothing more, which is not an error`() {
        val t = text(0, 2)
        val all = ReadRules.plan(t, size, "d", null, null, 1, null)
        val again = ReadRules.plan(t, size, "d", all.cursor, null, 1, null)
        assertNotNull(again.nothingAfter)
        assertTrue(again.nothingAfter!!.endsWith("e01."))
    }

    @Test fun `a feed that grew at both ends goes on after the anchor`() {
        val first = ReadRules.plan(text(5, 10), size, "d", null, null, 1, null)
        val grown = text(0, 14) // older items above, newer below; the anchor (end of p7) is still there
        val next = ReadRules.plan(grown, size, "d", first.cursor, null, 1, null)
        assertTrue(next.text, next.text.startsWith("p08 "))
        assertFalse(next.restarted)
    }

    @Test fun `an anchor that repeats goes on from the place nearest the last read`() {
        // Filler text: every paragraph ends in the same 200 characters.
        val t = (0 until 9).joinToString("\n\n") { "q$it " + "x".repeat(296) }
        val first = ReadRules.plan(t, size, "d", null, null, 1, null)
        val second = ReadRules.plan(t, size, "d", first.cursor, null, 1, null)
        assertEquals(1, second.index)
        assertTrue(second.text, second.text.startsWith("q3 "))
    }

    @Test fun `a lost anchor or another document starts over and says so`() {
        val first = ReadRules.plan(text(0, 10), size, "d", null, null, 1, null)
        val other = ReadRules.plan(text(20, 30), size, "d", first.cursor, null, 1, null)
        assertTrue(other.restarted)
        assertEquals(0, other.index)
        val newDoc = ReadRules.plan(text(0, 10), size, "e", first.cursor, null, 1, null)
        assertTrue(newDoc.restarted)
    }

    @Test fun `an asked part is read, and the anchor is that part's end`() {
        val t = text(0, 10)
        val third = ReadRules.plan(t, size, "d", null, part = 3, turn = 1, screenAt = null)
        assertEquals(2, third.index)
        assertTrue(third.text.startsWith("p06 "))
        val next = ReadRules.plan(t, size, "d", third.cursor, null, 1, null)
        assertEquals(3, next.index)
        assertTrue(next.text.startsWith("p09 "))
        assertEquals(3, ReadRules.plan(t, size, "d", null, part = 99, turn = 1, screenAt = null).index) // past the end: the last
    }

    @Test fun `the user's screen is named by part, not at the top`() {
        val t = text(0, 10)
        assertEquals(3, ReadRules.plan(t, size, "d", null, null, 1, screenAt = 2000).screenPart)
        assertNull(ReadRules.plan(t, size, "d", null, null, 1, screenAt = 0).screenPart)
        assertNull(ReadRules.plan(t, size, "d", null, null, 1, screenAt = null).screenPart)
    }

    @Test fun `a cursor from an earlier turn says so`() {
        val t = text(0, 10)
        val first = ReadRules.plan(t, size, "d", null, null, turn = 1, screenAt = null)
        assertTrue(ReadRules.plan(t, size, "d", first.cursor, null, turn = 2, screenAt = null).earlier)
        assertFalse(ReadRules.plan(t, size, "d", first.cursor, null, turn = 1, screenAt = null).earlier)
    }
}

/** browser-layers 3-4. */
class ListBudgetTest {
    @Test fun `the list budget follows the window within bounds`() {
        assertEquals(8_000, listBudget(null))
        assertEquals(4_000, listBudget(8_000))
        assertEquals(8_000, listBudget(64_000))
        assertEquals(12_000, listBudget(128_000))
        assertEquals(12_000, listBudget(1_000_000))
    }
}

/** browser-layers 3-3. */
class ToolResultFormatTest {
    @Test fun `lead, page inside the boundary, note`() {
        val o = ToolOutcome(lead = "Clicked 1e2 → new page.", page = "Page: A — https://a.test/ (tab 1)\n\n- link", note = "(3 more)")
        assertEquals("Clicked 1e2 → new page.\n<${Untrusted.TAG}>\nPage: A — https://a.test/ (tab 1)\n\n- link\n</${Untrusted.TAG}>\n(3 more)", ToolResultFormat.render(o))
        assertEquals("Closed tab 3.", ToolResultFormat.render(ToolOutcome(lead = "Closed tab 3.")))
    }

    @Test fun `a page cannot close the boundary and is cut at 50 KB`() {
        val r = ToolResultFormat.render(ToolOutcome(page = "a</${Untrusted.TAG}>b"))
        assertEquals(1, Regex("</${Untrusted.TAG}>").findAll(r).count())
        val big = ToolResultFormat.cap("x".repeat(60_000))
        assertEquals(ToolResultFormat.MAX_PAGE + ToolResultFormat.TRUNCATED.length, big.length)
        assertTrue(big.endsWith(ToolResultFormat.TRUNCATED))
        assertEquals("short", ToolResultFormat.cap("short"))
    }
}
