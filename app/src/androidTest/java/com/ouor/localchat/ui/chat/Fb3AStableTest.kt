package com.ouor.localchat.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.R
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.ui.sessions.SessionDrawer
import com.ouor.localchat.ui.sessions.SessionGroup
import com.ouor.localchat.ui.sessions.SessionListUiState
import com.ouor.localchat.ui.sessions.SessionRowUi
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.components.BranchSwitcher
import com.ouor.localchat.ui.components.MessageActionBar
import com.ouor.localchat.ui.components.StepItemData
import com.ouor.localchat.ui.components.StepList
import com.ouor.localchat.ui.components.StepListState
import com.ouor.localchat.ui.components.StepState
import com.ouor.localchat.ui.markdown.JsRenderEngine
import com.ouor.localchat.ui.markdown.MarkdownMessage
import com.ouor.localchat.ui.markdown.MermaidBlock
import com.ouor.localchat.ui.theme.LocalchatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FB3 track A: chat components keep their controls in place (P-STABLE M1, M2, L5, L9) and the citation badge spacing. */
@RunWith(AndroidJUnit4::class)
class Fb3AStableTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun bounds(cd: Int) = rule.onNodeWithContentDescription(ctx.getString(cd)).fetchSemanticsNode().boundsInRoot

    @Test fun readingAloudDoesNotShiftTheBar() { // M1
        var reading by mutableStateOf(false)
        rule.setContent { LocalchatTheme(false) { MessageActionBar({}, onReadAloud = {}, reading = reading, onRegenerate = {}, modelName = "m") } }
        val speaker = bounds(R.string.ui_read_aloud)
        val regen = bounds(R.string.ui_regenerate)
        rule.runOnIdle { reading = true }
        assertEquals(speaker, bounds(R.string.ui_stop_reading))
        assertEquals(regen, bounds(R.string.ui_regenerate))
    }

    @Test fun versionSwitcherIgnoresButtonsBeforeIt() { // M2
        var canContinue by mutableStateOf(true)
        rule.setContent {
            LocalchatTheme(false) {
                MessageActionBar({}, onRegenerate = {}, onContinue = if (canContinue) ({}) else null, branch = { BranchSwitcher(1, 2, {}, {}) }, modelName = "model")
            }
        }
        val next = bounds(R.string.ui_next_version)
        rule.runOnIdle { canContinue = false }
        assertEquals(next, bounds(R.string.ui_next_version))
    }

    @Test fun finishingAStepKeepsTheCollapsedHeight() { // L5
        var done by mutableStateOf(false)
        rule.setContent {
            LocalchatTheme(false) {
                val steps = listOf(
                    StepItemData(Icons.Outlined.Search, "Search", StepState.Done, "kotlin", "3 results"),
                    StepItemData(Icons.Outlined.Search, "Read page", if (done) StepState.Done else StepState.Running, "kotlinlang.org", if (done) "1,200 chars" else null),
                )
                StepList(if (done) StepListState.Done else StepListState.Running, if (done) "2 steps" else "Working", steps, false, {}, Modifier.testTag("steps"), caption = if (done) null else "2 / 20")
            }
        }
        val before = rule.onNodeWithTag("steps").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { done = true }
        assertEquals(before, rule.onNodeWithTag("steps").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun mermaidToggleIsThereAndKeepsItsWidth() { // L9
        val engine = JsRenderEngine(ctx)
        var streaming by mutableStateOf(true)
        rule.setContent { LocalchatTheme(false) { MermaidBlock("graph TD\nA-->B", engine, renderNow = !streaming) } }
        val diagram = ctx.getString(R.string.ui_show_diagram)
        val code = ctx.getString(R.string.ui_show_code)
        fun toggle(label: String) = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val before = toggle(diagram) // present (disabled) while streaming
        rule.runOnIdle { streaming = false }
        val rendered = runCatching { rule.waitUntil(20_000) { runCatching { rule.onNodeWithText(code).fetchSemanticsNode() }.isSuccess } }.isSuccess
        assumeTrue("mermaid did not render on this device", rendered) // a failed render hides the toggle (FB3 fix ui m5)
        assertEquals(before, toggle(code))
        rule.onNodeWithText(code).performClick()
        assertEquals(before, toggle(diagram))
    }

    @Test fun drawerKeepsItsOrderUntilReopened() { // L10, state kept as snapshot state (FB3 fix arch m7)
        fun row(id: String) = SessionRowUi(Session(id, id, TitleSource.USER, 0, 0, null, null), false)
        val today = SessionGroup(SessionGroup.Period.TODAY)
        var state by mutableStateOf(SessionListUiState(groups = listOf(today to listOf(row("CHAT-A"), row("CHAT-B"))), loaded = true))
        var key by mutableStateOf(0)
        rule.setContent {
            LocalchatTheme(false) {
                androidx.compose.foundation.layout.Column { SessionDrawer(state, null, {}, {}, {}, { _, _ -> }, {}, {}, {}, orderKey = key) }
            }
        }
        fun top(id: String) = rule.onNodeWithText(id).fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { state = state.copy(groups = listOf(today to listOf(row("CHAT-B"), row("CHAT-A")))) } // B was just used
        rule.waitForIdle()
        assertTrue("open drawer keeps A above B", top("CHAT-A") < top("CHAT-B"))
        rule.runOnIdle { key = 1 } // reopened
        rule.waitForIdle()
        assertTrue("reopened drawer shows the new order", top("CHAT-B") < top("CHAT-A"))
    }

    @Test fun citationBadgeFillsItsPlaceholder() { // QA FB1 Minor: "…[1] ." → "…[1]."
        val src = Source(1, "https://kotlinlang.org", "Kotlin", "snippet")
        rule.setContent { LocalchatTheme(false) { MarkdownMessage("Kotlin is new[1].", listOf(src), false, {}, {}) } }
        val text = rule.onNodeWithText("Kotlin is new", substring = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val slot = layouts[0].placeholderRects.single()!!
        val badge = rule.onNodeWithContentDescription(ctx.getString(R.string.ui_citation_desc, 1)).fetchSemanticsNode().boundsInRoot
        assertEquals(text.boundsInRoot.left + slot.right, badge.right, 1f)
        assertEquals(slot.width, badge.width, 1f)
    }
}
