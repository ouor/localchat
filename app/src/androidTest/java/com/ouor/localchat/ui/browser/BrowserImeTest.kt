package com.ouor.localchat.ui.browser

import android.net.Uri
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserPrompt
import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.Shots
import com.ouor.localchat.ui.theme.LocalchatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FB4 U-2: a page field focused in the Half panel opens the keyboard; the field and the panel's controls stay above it,
 * and they return to their place when it closes. Needs a soft keyboard on the device. Runs edge-to-edge with
 * adjustResize like MainActivity, so the keyboard reaches Compose as an inset.
 */
@RunWith(AndroidJUnit4::class)
class BrowserImeTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var web: WebView? = null

    private val actions = object : BrowserActions {
        override fun setPanel(p: PanelState) {}
        override fun select(id: Int) {}
        override fun showTabs(v: Boolean) {}
        override fun close(id: Int) {}
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
        override fun webView(id: Int): WebView? = web
        override fun park(id: Int) {}
    }

    /** The field sits at the bottom of the viewport, where the keyboard comes up. */
    private val page = """<html><head><meta name="viewport" content="width=device-width"></head>
        <body style="margin:0;background:#e8f0fe">
        <input style="position:fixed;bottom:0;left:0;width:100%;height:48px;font-size:18px;box-sizing:border-box" placeholder="login">
        </body></html>"""

    private fun imeBottom(): Int = rule.runOnUiThread<Int> {
        ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
    }

    private fun giveBack(): Rect = rule.onAllNodesWithText(rule.activity.getString(R.string.ui_give_back_to_ai))
        .fetchSemanticsNodes().map { it.boundsInRoot }.maxBy { it.bottom } // the handoff banner's, at the panel bottom

    private fun pageBounds(): Rect = rule.onNodeWithTag(PAGE_TAG).fetchSemanticsNode().boundsInRoot

    @Test fun keyboardKeepsTheFieldAndControlsAboveIt() {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION") rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            web = WebView(rule.activity)
        }
        val tab = TabInfo(1, "Login", "https://login.example/", Owner.Ai("A"), Control.USER, loading = false)
        val s = BrowserUiState(PanelState.Half, listOf(tab), sessionId = "A", running = true, handoff = Pause.Handoff(1, "로그인이 필요합니다"))
        rule.setContent { LocalchatTheme(false) { Box(Modifier.fillMaxSize()) { BrowserOverlay(s, actions) } } }
        rule.waitForIdle()
        rule.runOnUiThread { web!!.loadDataWithBaseURL(null, page, "text/html", "utf-8", null) } // loaded once on screen
        rule.waitUntil(10_000) { rule.runOnUiThread<Boolean> { web?.progress == 100 } }
        Thread.sleep(1000) // first frame of the page
        rule.waitForIdle()
        val before = giveBack()
        val pageBefore = pageBounds()
        Shots.save("fb4-u2-1-half")

        // Tap the field (bottom of the page): the keyboard comes up.
        rule.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(centerX, bottom - 20f)) }
        rule.waitUntil(10_000) { imeBottom() > 0 }
        Thread.sleep(800) // keyboard animation
        rule.waitForIdle()
        Shots.save("fb4-u2-2-keyboard")
        val imeTop = rule.runOnUiThread<Int> { rule.activity.window.decorView.height } - imeBottom()
        assertTrue("controls above the keyboard: ${giveBack().bottom} <= $imeTop", giveBack().bottom <= imeTop + 0.5f)
        assertTrue("page (and the field at its bottom) above the keyboard: ${pageBounds().bottom} <= $imeTop", pageBounds().bottom <= imeTop + 0.5f)
        assertTrue("page still usable", pageBounds().height > 100f)

        // Keyboard closed: everything is back where it was.
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        rule.waitUntil(10_000) { imeBottom() == 0 }
        Thread.sleep(800)
        rule.waitForIdle()
        Shots.save("fb4-u2-3-closed")
        assertEquals(before, giveBack())
        assertEquals(pageBefore, pageBounds())
    }
}
