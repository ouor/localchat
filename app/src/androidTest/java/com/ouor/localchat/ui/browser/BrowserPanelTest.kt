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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
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
    /** Null draws the blank placeholder; [realWebViewStillScrollsAfterHandleDrags] sets a real one. */
    private var page: WebView? = null

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
        override fun webView(id: Int): WebView? = page
        override fun park(id: Int) {}
    }

    private val aiTab = TabInfo(1, "인사 규정", "https://wiki.corp.example/hr", Owner.Ai("A"), Control.AI, loading = false)

    private val covers = mutableListOf<Int>()
    /** Screen height in px: swipes are fractions of it, so they cross the same anchors on any density. */
    private var rootH = 0f

    private fun show(state: BrowserUiState, reduced: Boolean = false, idle: Boolean = true) {
        s = state
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.ouor.localchat.ui.components.LocalReducedMotion provides reduced) {
                LocalchatTheme(false) {
                    Box(Modifier.fillMaxSize()) {
                        Column { BrowserPeek(s, actions) }
                        BrowserOverlay(s, actions) { cover = it; covers += it }
                    }
                }
            }
        }
        if (idle) rule.waitForIdle()
        rootH = rule.onRoot().fetchSemanticsNode().size.height.toFloat()
    }

    private fun handle() = rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e4_handle_cd))

    @Test fun stripShowsWhileTheRunOperatesAndOpensHalf() {
        show(BrowserUiState(PanelState.Peek, listOf(aiTab), sessionId = "A", running = true))
        rule.onNodeWithText("인사 규정", substring = true).assertIsDisplayed()
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
        handle().performTouchInput { swipeUp(startY = centerY, endY = centerY - rootH * 0.5f, durationMillis = 250) }
        rule.waitForIdle()
        assertEquals(PanelState.Full, panels.last())
        handle().performTouchInput { swipeDown(startY = centerY, endY = centerY + rootH, durationMillis = 300) }
        rule.waitForIdle()
        assertEquals(PanelState.Peek, panels.last())
        assertEquals(0, cover)
        handle().assertDoesNotExist()
    }

    @Test fun pageAreaDragDoesNotMoveThePanel() {
        show(BrowserUiState(PanelState.Half, listOf(aiTab), sessionId = "A"))
        val h = rule.onRoot().fetchSemanticsNode().size.height.toFloat()
        rule.onRoot().performTouchInput { swipeUp(startY = h * 0.9f, endY = h * 0.6f, durationMillis = 250) }
        rule.waitForIdle()
        assertTrue(panels.isEmpty())
    }

    @Test fun handleAccessibilityActionsStep() {
        show(BrowserUiState(PanelState.Half, listOf(aiTab), sessionId = "A"))
        fun custom(label: String) { val a = handle().fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }; rule.runOnIdle { a.action() } }
        custom(ctx.getString(R.string.ui_expand))
        rule.waitForIdle()
        assertEquals(PanelState.Full, panels.last())
        custom(ctx.getString(R.string.ui_collapse))
        rule.waitForIdle()
        assertEquals(PanelState.Half, panels.last())
        handle().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(PanelState.Full, panels.last())
    }

    /** Device check: handle drags move the panel, page drags scroll the real WebView and never move the panel. */
    @Test fun realWebViewStillScrollsAfterHandleDrags() {
        rule.runOnIdle {
            page = WebView(ctx).apply {
                loadDataWithBaseURL(null, "<html><body style='margin:0'>" + (1..300).joinToString("") { "<p>line $it</p>" } + "</body></html>", "text/html", "utf-8", null)
            }
        }
        show(BrowserUiState(PanelState.Peek, listOf(aiTab), sessionId = "A", running = true))
        shot("peek-strip")
        rule.onNodeWithText("인사 규정", substring = true).performClick()
        rule.waitUntil(5_000) { rule.runOnIdle { page!!.contentHeight } > 0 }
        rule.waitForIdle()
        shot("half")
        fun scrollPage() {
            val before = rule.runOnIdle { page!!.scrollY }
            val n = panels.size
            val h = rule.onRoot().fetchSemanticsNode().size.height.toFloat()
            rule.onRoot().performTouchInput { swipeUp(startY = h * 0.9f, endY = h * 0.75f, durationMillis = 300) }
            rule.waitForIdle()
            assertTrue("page scrolls", rule.runOnIdle { page!!.scrollY } > before)
            assertEquals("page drag leaves the panel alone", n, panels.size)
        }
        scrollPage()
        handle().performTouchInput { swipeUp(startY = centerY, endY = centerY - rootH * 0.5f, durationMillis = 250) }
        rule.waitForIdle()
        assertEquals(PanelState.Full, panels.last())
        shot("full")
        scrollPage()
        handle().performTouchInput { swipeDown(startY = centerY, endY = centerY + rootH * 0.3f, durationMillis = 400) }
        rule.waitForIdle()
        assertEquals(PanelState.Half, panels.last())
        scrollPage()
    }

    /** Saves the window to the app's external files (pulled into docs/dev/ui/shots by hand). */
    private fun shot(name: String) {
        val dir = ctx.getExternalFilesDir("shots") ?: return
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(dir, "fb1-e4-$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- R2a ----

    @Test fun coverIsReportedOnlyWhenSettledAndNeverForFull() { // R2a-1
        show(BrowserUiState(PanelState.Peek, listOf(aiTab), sessionId = "A", running = true))
        rule.onNodeWithText("인사 규정", substring = true).performClick()
        rule.waitForIdle()
        val half = cover
        assertTrue(half > 0)
        assertEquals("only the settled heights, no animation frames", listOf(0, half), covers.distinct())
        handle().performTouchInput { swipeUp(startY = centerY, endY = centerY - rootH * 0.5f, durationMillis = 250) }
        rule.waitForIdle()
        assertEquals(PanelState.Full, panels.last())
        assertEquals("Full is not reported", listOf(0, half), covers.distinct())
    }

    @Test fun reducedMotionSnapsThePanelAndStopsTheWorkingBar() { // R2a-2
        rule.mainClock.autoAdvance = false
        show(BrowserUiState(PanelState.Peek, listOf(aiTab), sessionId = "A", running = true), reduced = true, idle = false)
        rule.mainClock.advanceTimeByFrame()
        val strip = rule.onNodeWithText("인사 규정", substring = true)
        val a = strip.captureToImage().asAndroidBitmap()
        rule.mainClock.advanceTimeBy(600)
        val b = strip.captureToImage().asAndroidBitmap()
        assertTrue("working bar stands still", a.sameAs(b))
        rule.runOnIdle { s = s.copy(panel = PanelState.Half) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val top = handle().fetchSemanticsNode().boundsInRoot.top
        assertEquals("no slide: Half on the next frames", rootH * 0.45f, top, rootH * 0.05f)
    }

    @Test fun panelAlreadyHalfStartsAtHalf() { // R2a-4: recreation (rotation) keeps Half, no slide from Peek
        rule.mainClock.autoAdvance = false
        show(BrowserUiState(PanelState.Half, listOf(aiTab), sessionId = "A"), idle = false)
        rule.mainClock.advanceTimeByFrame()
        val top = handle().fetchSemanticsNode().boundsInRoot.top
        assertEquals(rootH * 0.45f, top, rootH * 0.05f)
        assertTrue(panels.isEmpty())
    }
}
