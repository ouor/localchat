package com.ouor.localchat.ui.browser

import android.net.Uri
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserPrompt
import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.TabInfo
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

    private val actions = object : BrowserActions {
        override fun setPanel(p: PanelState) { s = s.copy(panel = p) }
        override fun select(id: Int) {}
        override fun showTabs(v: Boolean) { s = s.copy(showTabs = v) }
        override fun close(id: Int) { s = s.copy(tabs = s.tabs.filterNot { it.id == id }) }
        override fun open(url: String?) {}
        override fun newTab(url: String?) {}
        override fun navigate(input: String) {}
        override fun back() {}
        override fun forward() {}
        override fun reload() {}
        override fun takeOver(id: Int) {}
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

    private fun tab(id: Int, title: String) = TabInfo(id, title, "https://e$id.example/", Owner.Ai("A"), Control.AI, loading = false)

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

    @Test fun handoffBannerSitsUnderThePageWithoutCoveringIt() { // M5, FB3 ui B1
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "Login")), sessionId = "A", running = true))
        val page = page().bounds()
        val tabs = tabsButton().bounds()
        change { it.copy(handoff = Pause.Handoff(1, "로그인이 필요합니다"), tabs = it.tabs.map { t -> t.copy(control = Control.USER) }) }
        rule.onNodeWithText("로그인이 필요합니다").assertIsDisplayed()
        val after = page().bounds()
        // Page top and the controls stay put; only the viewport's bottom edge rises above the banner.
        assertEquals(page.top, after.top, 0.5f)
        assertEquals(page.left, after.left, 0.5f)
        assertTrue("page shrank from the bottom", after.bottom < page.bottom)
        assertTrue("banner below the page, not over it", rule.onNodeWithText("로그인이 필요합니다").bounds().top >= after.bottom - 0.5f)
        assertEquals(tabs, tabsButton().bounds())
        // The ownership bar stays right under the toolbar (above the page), not at the panel bottom.
        assertTrue(rule.onNodeWithText(ctx.getString(R.string.ui_me)).bounds().bottom <= page.top + 0.5f)
    }

    @Test fun closedSensitiveBannerStaysClosedOnTheSameSite() { // FB3 ui m2
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "Bank")), sessionId = "A", sensitive = setOf(1)))
        rule.onNodeWithText(ctx.getString(R.string.ui_close)).performClick()
        change { it.copy(tabs = it.tabs.map { t -> t.copy(url = "https://e1.example/accounts") }) }
        rule.onNodeWithText(ctx.getString(R.string.ui_sensitive_site)).assertDoesNotExist()
        change { it.copy(tabs = it.tabs.map { t -> t.copy(url = "https://other-bank.example/") }) }
        rule.onNodeWithText(ctx.getString(R.string.ui_sensitive_site)).assertIsDisplayed()
    }

    @Test fun sensitiveBannerFloatsOverThePageAndCloses() { // M6
        show(BrowserUiState(PanelState.Full, listOf(tab(1, "Bank")), sessionId = "A"))
        val page = page().bounds()
        change { it.copy(sensitive = setOf(1)) }
        rule.onNodeWithText(ctx.getString(R.string.ui_sensitive_site)).assertIsDisplayed()
        assertEquals(page, page().bounds())
        rule.onNodeWithText(ctx.getString(R.string.ui_close)).performClick()
        rule.onNodeWithText(ctx.getString(R.string.ui_sensitive_site)).assertDoesNotExist()
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
