package com.ouor.localchat.testing

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Device tests that draw (taps, screenshots, layout) fail when the screen dozes. Wakes the screen before each
 * test and keeps it on in every Activity the test starts. It only turns the screen on: a locked device fails
 * fast with a clear message instead of being unlocked (the keyguard is never dismissed or bypassed).
 *
 * ```
 * @get:Rule val screen = ScreenOnRule()
 * ```
 */
class ScreenOnRule : ExternalResource() {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val keepOn = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun before() {
        wake()
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        check(!keyguard.isKeyguardLocked) { "The device is locked. Unlock it by hand before running device tests." }
        app.registerActivityLifecycleCallbacks(keepOn)
    }

    /**
     * KEYCODE_WAKEUP does not leave Doze on every device (devphone03 stayed "Dozing", so the Activity never laid
     * out its views and parked WebViews stayed 0x0). POWER is sent only while the screen is not interactive, so it
     * can only turn the screen on. Fails fast when the screen still will not come on.
     */
    private fun wake() {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        for (key in listOf("KEYCODE_WAKEUP", "KEYCODE_POWER")) {
            if (power.isInteractive) return
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent $key").close()
            repeat(20) { if (!power.isInteractive) Thread.sleep(100) }
        }
        check(power.isInteractive) { "The screen did not turn on. Wake the device by hand before running device tests." }
    }

    override fun after() {
        app.unregisterActivityLifecycleCallbacks(keepOn)
    }
}
