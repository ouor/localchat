package com.ouor.localchat.ui.browser

import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserPrompt
import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.theme.LocalchatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FB-1c status strip and FB-5 handle drag (fixed-state Compose tests; the WebView is the test placeholder). */
@RunWith(AndroidJUnit4::class)
class BrowserPanelTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private var s by mutableStateOf(BrowserUiState())
    private val panels = mutableListOf<PanelState>()
    private val takeOvers = mutableListOf<Int>()
    private var cover = -1

    private val actions = object : BrowserActions {
        override fun setPanel(p: PanelState) { panels += p; s = s.copy(panel = p) }
        override fun select(id: Int) {}
        override fun showTabs(v: Boolean) {}
        override fun close(id: Int) {}
        override fun open(url: String?) {}
        override fun newTab(url: String?) {}
        override fun navigate(input: String) {}
        override fun back() {}
        override fun forward() {}
        override fun reload() {}
        override fun takeOver(id: Int) { takeOvers += id }
        override fun dismissTouch() {}
        override fun giveBack(id: Int) {}
        override fun handToAi(id: Int) {}
        override fun takeTab(id: Int) {}
        override fun stopRun() {}
        override fun answerDialog(p: BrowserPrompt.Dialog, accept: Boolean, text: String?) {}
        override fun answerFiles(p: BrowserPrompt.FileChooser, uris: List<Uri>?) {}
        override fun answerPermission(p: BrowserPrompt.Permission, grant: Boolean) {}
        override fun answerDownload(p: BrowserPrompt.Download, allow: Boolean) {}
        override fun webView(id: Int): WebView? = null
        override fun park(id: Int) {}
    }

    private val aiTab = TabInfo(1, "인사 규정", "https://wiki.corp.example/hr", Owner.Ai("A"), Control.AI, loading = false)

    private fun show(state: BrowserUiState) {
        s = state
        rule.setContent {
            LocalchatTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    Column { BrowserPeek(s, actions) }
                    BrowserOverlay(s, actions) { cover = it }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun handle() = rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e4_handle_cd))

    @Test fun stripShowsWhileTheRunOperatesAndOpensHalf() {
        show(BrowserUiState(PanelState.Peek, listOf(aiTab), sessionId = "A", running = true))
        rule.onNodeWithText("인사 규정").assertIsDisplayed()
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e4_strip_cd, "인사 규정")).performClick()
        rule.waitForIdle()
        assertEquals(PanelState.Half, panels.last())
        handle().assertIsDisplayed()
        assertTrue("panel covers the bottom at Half", cover > 0)
    }

    @Test fun stripTakeOverSwitchesControlAndOpens() {
        show(BrowserUiState(PanelState.Peek, listOf(aiTab), sessionId = "A", running = true))
        rule.onNodeWithText(ctx.getString(R.string.ui_take_over)).performClick()
        assertEquals(listOf(1), takeOvers)
        assertEquals(PanelState.Half, panels.last())
    }

    @Test fun userTabPeekShowsNothing() {
        show(BrowserUiState(PanelState.Peek, listOf(aiTab.copy(owner = Owner.User)), running = true))
        rule.onNodeWithText("인사 규정").assertDoesNotExist()
        assertEquals(0, cover)
    }

    @Test fun handleDragsHalfToFullAndDownToPeek() {
        show(BrowserUiState(PanelState.Half, listOf(aiTab), sessionId = "A"))
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_collapse)).assertDoesNotExist()
        handle().performTouchInput { swipeUp(startY = centerY, endY = centerY - 900f, durationMillis = 250) }
        rule.waitForIdle()
        assertEquals(PanelState.Full, panels.last())
        handle().performTouchInput { swipeDown(startY = centerY, endY = centerY + 3000f, durationMillis = 300) }
        rule.waitForIdle()
        assertEquals(PanelState.Peek, panels.last())
        assertEquals(0, cover)
        handle().assertDoesNotExist()
    }

    @Test fun pageAreaDragDoesNotMoveThePanel() {
        show(BrowserUiState(PanelState.Half, listOf(aiTab), sessionId = "A"))
        val h = rule.onRoot().fetchSemanticsNode().size.height.toFloat()
        rule.onRoot().performTouchInput { swipeUp(startY = h - 100f, endY = h - 900f, durationMillis = 250) }
        rule.waitForIdle()
        assertTrue(panels.isEmpty())
    }

    @Test fun handleAccessibilityActionsStep() {
        show(BrowserUiState(PanelState.Half, listOf(aiTab), sessionId = "A"))
        fun custom(label: String) = handle().fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }.action()
        rule.runOnIdle { custom(ctx.getString(R.string.ui_expand)) }
        rule.waitForIdle()
        assertEquals(PanelState.Full, panels.last())
        rule.runOnIdle { custom(ctx.getString(R.string.ui_collapse)) }
        rule.waitForIdle()
        assertEquals(PanelState.Half, panels.last())
        handle().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(PanelState.Full, panels.last())
    }
}
