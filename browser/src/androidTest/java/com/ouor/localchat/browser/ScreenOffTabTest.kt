package com.ouor.localchat.browser

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.testing.TestHostActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tab the AI opens while the screen is off gets a real size: no layout pass runs then, so the WebView used to
 * stay 0x0 (page innerWidth 0, no screenshots or approval previews). No ScreenOnRule: the test sleeps the screen itself.
 */
@RunWith(AndroidJUnit4::class)
class ScreenOffTabTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var scenario: ActivityScenario<TestHostActivity>? = null

    private fun key(code: String) = instr.uiAutomation.executeShellCommand("input keyevent $code").close()

    private fun waitInteractive(on: Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (power.isInteractive != on && System.currentTimeMillis() < end) Thread.sleep(100)
        check(power.isInteractive == on) { "screen did not turn ${if (on) "on" else "off"}" }
    }

    /** Some devices ignore WAKEUP while dozing; POWER then toggles the screen on. */
    private fun wakeUp() {
        key("KEYCODE_WAKEUP")
        runCatching { waitInteractive(true) }.onFailure { key("KEYCODE_POWER"); waitInteractive(true) }
    }

    @After fun wake() {
        wakeUp()
        scenario?.close()
    }

    @Test fun aiTabOpenedWithTheScreenOffHasRealSize() {
        wakeUp()
        check(!(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "Unlock the device by hand first." }
        val engine = TestHostActivity.engine
        scenario = ActivityScenario.launch(TestHostActivity::class.java)

        key("KEYCODE_SLEEP")
        waitInteractive(false)
        Thread.sleep(1_000) // let the Activity stop

        runBlocking {
            val id = withContext(Dispatchers.Main) { engine.open("about:blank") }
            try {
                val tab = withContext(Dispatchers.Main) { engine.tab(id) }
                tab.awaitReady(android.os.SystemClock.uptimeMillis())
                val (w, h) = withContext(Dispatchers.Main) { tab.webView.value!!.width to tab.webView.value!!.height }
                assertTrue("WebView ${w}x$h", w > 0 && h > 0)
                val inner = tab.evaluate("window.innerWidth", 5_000).toFloat()
                assertTrue("innerWidth $inner", inner > 0f)
                assertTrue("screen still off during the check", !power.isInteractive)
            } finally {
                withContext(Dispatchers.Main) { engine.close(id) }
            }
        }
    }
}
