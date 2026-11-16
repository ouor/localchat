package com.ouor.localchat.ui.markdown

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.theme.LocalchatTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getBoundsInRoot
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FB-4: table cells wrap fully (no one-line ellipsis) and keep citation badges. */
@RunWith(AndroidJUnit4::class)
class MarkdownTableTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createComposeRule()

    private val long = "평화국 로베타(ロベッタ)에서 만난 마녀와 함께 여행을 이어가며 여러 사건을 겪는 긴 설명 셀"
    private val md = """
        || 국가 | 설명 |
        ||---|---|
        || 로베타 | $long[1] |
        || 빈 칸 |  |
    """.trimMargin()

    @Test fun longCellWrapsWithoutEllipsis() {
        rule.setContent {
            LocalchatTheme(darkTheme = false) {
                MarkdownMessage(md, listOf(Source(1, "https://example.com", "ex")), streaming = false, onLinkClick = {}, onCitationClick = {}, modifier = Modifier.width(360.dp))
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("평화국 로베타", substring = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        assertTrue("lines=${layout.lineCount}", layout.lineCount > 1)
        assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertFalse(layout.hasVisualOverflow)
        // Citation token became inline content, not raw text.
        rule.onAllNodesWithText(TOKEN_OPEN.toString(), substring = true).assertCountEquals(0)
    }

    @Test fun anotherTableInTheSameSlotDoesNotInheritColumnWidths() {
        val wide = "W".repeat(80) // clamps both columns to 240dp
        var md by mutableStateOf("| A | B |\n|---|---|\n| $wide | $wide |")
        rule.setContent {
            LocalchatTheme(darkTheme = false) {
                MarkdownMessage(md, emptyList(), streaming = false, onLinkClick = {}, onCitationClick = {}, modifier = Modifier.width(360.dp))
            }
        }
        shows(wide)
        md = "| x | y |\n|---|---|\n| 1 | 2 |"
        shows("x")
        // Two short columns share the 360dp; with leaked 240dp widths the first cell would start a 480dp table.
        val x = rule.onNodeWithText("x", substring = true, useUnmergedTree = true).getBoundsInRoot()
        val y = rule.onNodeWithText("y", substring = true, useUnmergedTree = true).getBoundsInRoot()
        val col = y.left - x.left
        assertTrue("first column $col should be ~180dp (360 / 2), not the old 240dp", col < 220.dp)
    }

    @Test fun streamedRowsKeepColumnsFromShrinking() {
        val wide = "W".repeat(80)
        var md by mutableStateOf("| x | y |\n|---|---|\n| $wide | 2 |")
        rule.setContent {
            LocalchatTheme(darkTheme = false) {
                MarkdownMessage(md, emptyList(), streaming = true, onLinkClick = {}, onCitationClick = {}, modifier = Modifier.width(360.dp))
            }
        }
        shows("y")
        val before = rule.onNodeWithText("y", substring = true, useUnmergedTree = true).getBoundsInRoot().left
        // Same table, the wide row replaced while streaming: the first column keeps its width.
        md = "| x | y |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        shows("4")
        assertEquals(before, rule.onNodeWithText("y", substring = true, useUnmergedTree = true).getBoundsInRoot().left)
    }

    @Test fun tableAnswersIntrinsicSizes() {
        // FB1 deferred: WrapTable was BoxWithConstraints (SubcomposeLayout), which throws when a parent asks intrinsics.
        rule.setContent {
            LocalchatTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Row(Modifier.width(360.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    MarkdownMessage("| x | y |\n|---|---|\n| 1 | 2 |", emptyList(), streaming = false, onLinkClick = {}, onCitationClick = {}, modifier = Modifier.width(360.dp))
                }
            }
        }
        shows("y")
    }

    /** The markdown is parsed off the main thread; wait until [text] is laid out. */
    private fun shows(text: String) = try {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        throw AssertionError("'$text' not shown in " + rule.onRoot(useUnmergedTree = true).printToString(), e)
    }
}
