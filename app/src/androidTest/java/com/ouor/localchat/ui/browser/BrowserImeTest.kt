package com.ouor.localchat.ui.browser

import android.net.Uri
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextField
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.browser.WebViewFactory
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.statusBarsPadding
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
    private var s by mutableStateOf(BrowserUiState())

    private val actions = object : BrowserActions {
        override fun setPanel(p: PanelState) { s = s.withPanel(p) }
        override fun keyboard(up: Boolean) { s = s.keyboard(up) }
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
        <input style="position:fixed;bottom:0;left:0;width:100%;height:48px;font-size:18px;box-sizing:border-box" id="f" placeholder="login">
        </body></html>"""

    private fun imeBottom(): Int = rule.runOnUiThread<Int> {
        ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
    }

    private fun giveBack(): Rect = rule.onAllNodesWithText(rule.activity.getString(R.string.ui_give_back_to_ai))
        .fetchSemanticsNodes().map { it.boundsInRoot }.maxBy { it.bottom } // the handoff banner's, at the panel bottom

    private fun pageBounds(): Rect = rule.onNodeWithTag(PAGE_TAG).fetchSemanticsNode().boundsInRoot

    /** Handoff on a user-controlled AI tab, panel at [panel], showing [html]. */
    /** A handoff on a user-controlled AI tab. */
    private fun handoff(panel: PanelState) = BrowserUiState(panel, listOf(TabInfo(1, "Login", "https://login.example/", Owner.Ai("A"), Control.USER, loading = false)),
        sessionId = "A", running = true, handoff = Pause.Handoff(1, "로그인이 필요합니다"))

    /** The user's own tab ("나"), as in the s25 feedback (FB5 B1). */
    private fun mine(panel: PanelState) = BrowserUiState(panel, listOf(TabInfo(1, "Google", "https://www.google.com/", Owner.User, Control.USER, loading = false)), sessionId = "A")

    private fun open(panel: PanelState, html: String, state: BrowserUiState = handoff(panel), outside: @Composable () -> Unit = {}) {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION") rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            web = WebViewFactory.create(rule.activity) // the app's settings (wide viewport, zoom)
        }
        s = state
        rule.setContent { LocalchatTheme(false) { Box(Modifier.fillMaxSize()) { outside(); BrowserOverlay(s, actions) } } }
        rule.waitForIdle()
        rule.runOnUiThread { web!!.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) } // loaded once on screen
        rule.waitUntil(10_000) { rule.runOnUiThread<Boolean> { web?.progress == 100 } }
        Thread.sleep(1000) // first frame of the page
        rule.waitForIdle()
    }

    /** Runs [script] in the page and returns its JSON result. */
    private fun js(script: String): String {
        val done = java.util.concurrent.CountDownLatch(1)
        var out = ""
        rule.runOnUiThread { web!!.evaluateJavascript(script) { out = it; done.countDown() } }
        assertTrue("script answered", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return out
    }

    private fun closeKeyboard() {
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        rule.waitUntil(10_000) { imeBottom() == 0 }
        Thread.sleep(800)
        rule.waitForIdle()
    }

    @Test fun keyboardKeepsTheFieldAndControlsAboveIt() {
        open(PanelState.Half, page)
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

        // FB4 fix M1: dragging the handle with the keyboard up moves it with the finger, never by the keyboard's height.
        val handle = rule.onNodeWithContentDescription(rule.activity.getString(R.string.fb_e4_handle_cd))
        val handleTop = handle.fetchSemanticsNode().boundsInRoot.top
        handle.performTouchInput { down(center) }
        repeat(6) { step ->
            handle.performTouchInput { moveBy(Offset(0f, -10f)) }
            rule.waitForIdle()
            val now = handle.fetchSemanticsNode().boundsInRoot.top
            assertTrue("step $step: handle at $now, started at $handleTop", now <= handleTop + 20f && now >= handleTop - 80f)
        }
        handle.performTouchInput { up() }
        rule.waitForIdle()
        Thread.sleep(500) // settle back to Half
        rule.waitForIdle()
        assertEquals(handleTop, handle.fetchSemanticsNode().boundsInRoot.top, 1f)

        // Keyboard closed: everything is back where it was.
        closeKeyboard()
        Shots.save("fb4-u2-3-closed")
        assertEquals(before, giveBack())
        assertEquals(pageBefore, pageBounds())
    }

    @Test fun pageKeyboardMakesThePanelFullAndBack() { // FB5 B1: the user's own tab ("나"), as in the s25 feedback
        open(PanelState.Half, page, mine(PanelState.Half))
        rule.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(centerX, bottom - 20f)) }
        rule.waitUntil(10_000) { imeBottom() > 0 }
        rule.waitUntil(5_000) { s.panel == PanelState.Full }
        Thread.sleep(1200) // keyboard animation and page resize
        rule.waitForIdle()
        Shots.save("fb5-b1-full-keyboard")
        val imeTop = rule.runOnUiThread<Int> { rule.activity.window.decorView.height } - imeBottom()
        assertTrue("page (and the field at its bottom) above the keyboard: ${pageBounds().bottom} <= $imeTop", pageBounds().bottom <= imeTop + 0.5f)
        InstrumentationRegistry.getInstrumentation().sendStringSync("seoul")
        rule.waitUntil(5_000) { js("document.getElementById('f').value") == "\"seoul\"" }
        closeKeyboard()
        rule.waitUntil(5_000) { s.panel == PanelState.Half } // back to the user's height
        Shots.save("fb5-b1-back-to-half")
    }

    @Test fun addressKeyboardMakesThePanelFull() { // FB5 B1 + B2: editing the address opens the keyboard for the panel too
        open(PanelState.Half, page, mine(PanelState.Half))
        rule.onNodeWithText("google.com/").performClick()
        rule.waitUntil(10_000) { imeBottom() > 0 }
        rule.waitUntil(5_000) { s.panel == PanelState.Full }
        rule.waitForIdle()
        Shots.save("fb5-b2-address-editing")
        closeKeyboard()
        rule.waitUntil(5_000) { s.panel == PanelState.Half }
    }

    @Test fun aKeyboardOutsideThePanelLeavesItsHeight() { // FB5 B1: only a keyboard for the panel resizes it
        open(PanelState.Half, page, mine(PanelState.Half)) {
            TextField("", {}, Modifier.statusBarsPadding().testTag("outside"))
        }
        rule.onNodeWithTag("outside").performClick()
        rule.waitUntil(10_000) { imeBottom() > 0 }
        Thread.sleep(1000)
        rule.waitForIdle()
        assertEquals(PanelState.Half, s.panel)
        closeKeyboard()
        assertEquals(PanelState.Half, s.panel)
    }

    @Test fun aKeyboardOutsideThePanelAfterAPageTapLeavesItsHeight() { // FB5 review ui m3: the page held focus before
        open(PanelState.Half, page, mine(PanelState.Half)) { TextField("", {}, Modifier.statusBarsPadding().testTag("outside")) }
        rule.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(centerX, bottom - 20f)) }
        rule.waitUntil(10_000) { imeBottom() > 0 }
        rule.waitUntil(5_000) { s.panel == PanelState.Full }
        closeKeyboard()
        rule.waitUntil(5_000) { s.panel == PanelState.Half }
        rule.onNodeWithTag("outside").performClick()
        rule.waitUntil(10_000) { imeBottom() > 0 }
        Thread.sleep(1000)
        rule.waitForIdle()
        assertEquals(PanelState.Half, s.panel)
        closeKeyboard()
        assertEquals(PanelState.Half, s.panel)
    }

    @Test fun aHandoffWhileTypingKeepsTheFullPanel() { // FB5 review ui M2 / arch M1: the run's height wins over the return height
        open(PanelState.Half, page, mine(PanelState.Half))
        rule.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(centerX, bottom - 20f)) }
        rule.waitUntil(10_000) { imeBottom() > 0 }
        rule.waitUntil(5_000) { s.panel == PanelState.Full }
        rule.runOnIdle { s = s.copy(handoff = Pause.Handoff(1, "로그인이 필요합니다")).withPanel(PanelState.Full) } // what the ViewModel does on a handoff
        closeKeyboard()
        Thread.sleep(500)
        rule.waitForIdle()
        assertEquals(PanelState.Full, s.panel)
    }

    @Test fun halfKeyboardTypesIntoTheField() { // FB4 QA F6: the keyboard was attached but the text never reached the page
        open(PanelState.Half, page)
        rule.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(centerX, bottom - 20f)) }
        rule.waitUntil(10_000) { imeBottom() > 0 }
        Thread.sleep(1500) // keyboard animation, panel rise and page resize
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendStringSync("seoul") // through the IME, like `input text`
        rule.waitUntil(5_000) { js("document.getElementById('f').value") == "\"seoul\"" }
        Shots.save("fb4-u4-f6-half-typed")
        closeKeyboard()
    }

    /**
     * A search page centred in the viewport like html.duckduckgo.com (QA F1): when the keyboard shrinks the page, the
     * centred block re-lays out and its field lands below the visible page unless the page scrolls to it again.
     */
    private val centredPage = """<html><head><meta name="viewport" content="width=device-width"></head>
        <body style="margin:0"><div style="min-height:100vh;display:flex;flex-direction:column;justify-content:center">
        <div style="height:200px;background:#e8f0fe"></div>
        <input id="f" style="display:block;width:100%;height:48px;font-size:18px;box-sizing:border-box" placeholder="search">
        </div></body></html>"""

    @Test fun fullKeyboardScrollsTheFocusedFieldIntoView() { // FB4 QA F1
        open(PanelState.Full, centredPage)
        val scale = pageBounds().width / js("innerWidth").toFloat()
        val fieldTop = js("document.getElementById('f').getBoundingClientRect().top").toFloat()
        rule.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(centerX, (fieldTop + 24f) * scale)) }
        rule.waitUntil(10_000) { imeBottom() > 0 }
        Thread.sleep(1200) // keyboard animation + page resize
        rule.waitForIdle()
        Shots.save("fb4-u3-f1-full-keyboard")
        assertEquals("the field has focus", "\"f\"", js("document.activeElement.id"))
        val r = js("(function(){var r=document.getElementById('f').getBoundingClientRect();return [r.top,r.bottom,innerHeight].join(',')})()")
            .trim('"').split(',').map { it.toFloat() }
        assertTrue("field inside the visible page: top ${r[0]}, bottom ${r[1]}, viewport ${r[2]}", r[0] >= 0f && r[1] <= r[2] + 1f) // innerHeight is whole CSS px
        closeKeyboard()
    }

    @Test fun fullKeyboardHandleFollowsTheFinger() { // FB4 QA F4
        open(PanelState.Full, page)
        rule.onNodeWithTag(PAGE_TAG).performTouchInput { click(Offset(centerX, bottom - 20f)) }
        rule.waitUntil(10_000) { imeBottom() > 0 }
        Thread.sleep(800)
        rule.waitForIdle()
        val handle = rule.onNodeWithContentDescription(rule.activity.getString(R.string.fb_e4_handle_cd))
        val handleTop = handle.fetchSemanticsNode().boundsInRoot.top
        handle.performTouchInput { down(center) }
        repeat(30) { handle.performTouchInput { moveBy(Offset(0f, 10f)) }; rule.waitForIdle() }
        val moved = handle.fetchSemanticsNode().boundsInRoot.top - handleTop
        Shots.save("fb4-u3-f4-full-drag")
        // 300px down: past the touch slop the handle follows (it was a dead zone as tall as the keyboard, ~1270px here).
        assertTrue("handle moved $moved px for a 300 px drag", moved >= 120f)
        handle.performTouchInput { up() }
        rule.waitForIdle()
        Thread.sleep(800)
        rule.waitForIdle()
    }
}
