package com.ouor.localchat.ui.browser

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import com.ouor.localchat.ui.components.TAB_COUNT_TAG
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.R
import com.ouor.localchat.browser.Answer
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.ToolActivity
import com.ouor.localchat.policy.Notice
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.theme.LocalchatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** P-STABLE M5, M6, L2, L8: browser panel controls and the page keep their place when banners, loading or closing happen. */
@RunWith(AndroidJUnit4::class)
class BrowserStableTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private var s by mutableStateOf(BrowserUiState())
    private val navigated = mutableListOf<String>()

    private val actions = object : BrowserActions {
        override fun setPanel(p: PanelState) { s = s.withPanel(p) }
        override fun keyboard(up: Boolean) { s = s.keyboard(up) }
        override fun select(id: Int) {}
        override fun showTabs(v: Boolean) { s = s.copy(showTabs = v) }
        override fun close(id: Int) { s = s.copy(tabs = s.tabs.filterNot { it.id == id }) }
        override fun open(url: String?) {}
        override fun newTab(url: String?) {}
        override fun navigate(input: String) { navigated += input }
        override fun back() {}
        override fun forward() {}
        override fun reload() {}
        override fun reload(id: Int) {}
        override fun dismissOpenFailed() {}
        override fun stopRun(sessionId: String?) {}
        override fun userStepDone() {}
        override fun answer(e: BrowserEvent, answer: Answer) {}
        override fun savePdf(id: Int, uri: android.net.Uri, done: (Boolean) -> Unit) {}
        override fun webView(id: Int): kotlinx.coroutines.flow.StateFlow<WebView?>? = null
        override fun show(id: Int) {}
        override fun park(id: Int) {}
    }

    private fun tab(id: Int, title: String) = TabInfo(id, title, "https://e$id.example/", loading = false)

    private fun show(state: BrowserUiState) {
        s = state
        rule.setContent { LocalchatTheme(false) { Box(Modifier.fillMaxSize()) { BrowserOverlay(s, actions) } } }
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot
    private fun page() = rule.onNodeWithTag(PAGE_TAG)
    private fun tabsButton() = rule.onNodeWithText("1")

    private fun change(update: (BrowserUiState) -> BrowserUiState) {
        rule.runOnIdle { s = update(s) }
        rule.waitForIdle()
    }

    @Test fun userStepBannerSitsUnderThePageWithoutCoveringIt() { // M5, FB3 ui B1, ask_user (6-4)
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "Login")), front = 1, sessionId = "A", running = true))
        val page = page().bounds()
        val tabs = tabsButton().bounds()
        change { it.copy(userStep = Pause.UserStep(1, "로그인")) }
        val banner = rule.onNodeWithTag(USER_STEP_TAG)
        banner.assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.ui_user_step_here, "로그인")).assertIsDisplayed()
        val after = page().bounds()
        // Page top and the controls stay put; only the viewport's bottom edge rises above the banner.
        assertEquals(page.top, after.top, 0.5f)
        assertEquals(page.left, after.left, 0.5f)
        assertTrue("page shrank from the bottom", after.bottom < page.bottom)
        assertTrue("banner below the page, not over it", banner.bounds().top >= after.bottom - 0.5f)
        assertEquals(tabs, tabsButton().bounds())
    }

    @Test fun aiActivityDoesNotMoveThePageOrTheControls() { // P-STABLE: the activity bar has a fixed slot above the page
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "Page")), front = 1, sessionId = "A"))
        val page = page().bounds()
        val tabs = tabsButton().bounds()
        change { it.copy(activity = mapOf(1 to ToolActivity("B", "test_tool")), running = true) }
        rule.onNodeWithText(ctx.getString(R.string.ui_stop)).assertIsDisplayed()
        assertTrue(rule.onNodeWithText(ctx.getString(R.string.ui_stop)).bounds().bottom <= page.top + 0.5f)
        assertEquals(page, page().bounds())
        assertEquals(tabs, tabsButton().bounds())
        change { it.copy(activity = emptyMap(), running = false) }
        assertEquals(page, page().bounds())
        assertEquals(1, s.front)
    }

    private fun bannerNotice(tabId: Int) = Notice("test-banner", "정책 안내", tabId, Notice.Style.BANNER)

    @Test fun closedPolicyBannerStaysClosedOnTheSameSite() { // FB3 ui m2
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "Bank")), sessionId = "A", notices = listOf(bannerNotice(1))))
        rule.onNodeWithText(ctx.getString(R.string.ui_close)).performClick()
        change { it.copy(tabs = it.tabs.map { t -> t.copy(url = "https://e1.example/accounts") }) }
        rule.onNodeWithText("정책 안내").assertDoesNotExist()
        change { it.copy(tabs = it.tabs.map { t -> t.copy(url = "https://other-bank.example/") }) }
        rule.onNodeWithText("정책 안내").assertIsDisplayed()
    }

    @Test fun policyBannerFloatsOverThePageAndCloses() { // M6
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "Bank")), sessionId = "A"))
        val page = page().bounds()
        change { it.copy(notices = listOf(bannerNotice(1))) }
        rule.onNodeWithText("정책 안내").assertIsDisplayed()
        assertEquals(page, page().bounds())
        rule.onNodeWithText(ctx.getString(R.string.ui_close)).performClick()
        rule.onNodeWithText("정책 안내").assertDoesNotExist()
        assertEquals(page, page().bounds())
    }

    @Test fun loadingBarDoesNotMoveThePage() { // L2
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "Page")), sessionId = "A"))
        val page = page().bounds()
        change { it.copy(tabs = it.tabs.map { t -> t.copy(loading = true) }) }
        assertEquals(page, page().bounds())
        change { it.copy(tabs = it.tabs.map { t -> t.copy(loading = false) }) }
        assertEquals(page, page().bounds())
    }

    @Test fun addressIsEditedInPlace() { // FB5 B2: no dialog; the bar and its buttons keep their place
        val url = "https://www.e1.example/search?q=a"
        show(BrowserUiState(PanelState.Full, listOf(TabInfo(1, "Mine", url, loading = false)), sessionId = "A"))
        val controls = listOf(R.string.ui_back, R.string.ui_forward, R.string.ui_reload).map { rule.onNodeWithContentDescription(ctx.getString(it)) }
        val before = controls.map { it.bounds() } + tabsButton().bounds()
        val shown = rule.onNodeWithText("e1.example/search?q=a")
        val bar = shown.bounds()
        shown.performClick()
        val editor = rule.onNodeWithContentDescription(ctx.getString(R.string.ui_edit_url))
        editor.assertIsFocused()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(url)))
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, url.length))) // all selected
        assertEquals(before, controls.map { it.bounds() } + tabsButton().bounds())
        assertEquals(bar.center.y, editor.bounds().center.y, 2f) // same line as the shown address (the bar node is the whole pill)
        rule.onNodeWithText(ctx.getString(R.string.ui_edit_url)).assertDoesNotExist() // the old dialog's title
        editor.performTextReplacement("kotlinlang.org")
        editor.performImeAction() // Go
        rule.waitForIdle()
        assertEquals(listOf("kotlinlang.org"), navigated)
        editor.assertDoesNotExist()
        // Back cancels: nothing is navigated and the address shows again.
        shown.performClick()
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_edit_url)).assertIsFocused()
        Espresso.pressBack()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription(ctx.getString(R.string.ui_edit_url)).fetchSemanticsNodes().isEmpty() } // the keyboard takes this Back; its closing ends the edit
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_edit_url)).assertDoesNotExist()
        shown.assertIsDisplayed()
        assertEquals(listOf("kotlinlang.org"), navigated)
        assertEquals(before, controls.map { it.bounds() } + tabsButton().bounds())
    }

    @Test fun tabCountSitsInTheMiddleOfItsBox() { // FB5 B3: the digits' ink is centred, not pushed down by the line height
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "One")), sessionId = "A"))
        for (n in listOf(1, 12)) { // one and two digits
            change { st -> st.copy(tabs = (1..n).map { tab(it, "T$it") }) }
            val img = rule.onNodeWithTag(TAB_COUNT_TAG, useUnmergedTree = true).captureToImage().asAndroidBitmap() // merged into the tabs button
            val inset = (2 * ctx.resources.displayMetrics.density).toInt() + 2 // past the 1.8dp border
            val ink = (inset until img.height - inset).filter { y ->
                (inset until img.width - inset).any { x ->
                    val c = img.getPixel(x, y)
                    (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3 < 128
                }
            }
            assertTrue("$n: digits drawn", ink.isNotEmpty())
            val inkMid = (ink.first() + ink.last() + 1) / 2f
            assertTrue("$n: digits' centre $inkMid vs box centre ${img.height / 2f}", kotlin.math.abs(inkMid - img.height / 2f) <= 1.5f)
        }
    }

    @Test fun closingATabLeavesItsSlotEmpty() { // L8
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "Alpha"), tab(2, "Beta"), tab(3, "Gamma")), sessionId = "A", showTabs = true))
        val closeCd = ctx.getString(R.string.ui_close_tab)
        val firstX = rule.onAllNodesWithContentDescription(closeCd)[0].bounds()
        val beta = rule.onNodeWithText("Beta").bounds()
        rule.onAllNodesWithContentDescription(closeCd)[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Alpha").assertDoesNotExist()
        assertEquals(beta, rule.onNodeWithText("Beta").bounds())
        val xs = rule.onAllNodesWithContentDescription(closeCd).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(2, xs.size)
        assertTrue("no close button slid under the finger", firstX !in xs)
    }
}
