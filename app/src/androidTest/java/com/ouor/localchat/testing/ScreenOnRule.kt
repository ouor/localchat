package com.ouor.localchat.testing

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
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
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        check(!keyguard.isKeyguardLocked) { "The device is locked. Unlock it by hand before running device tests." }
        app.registerActivityLifecycleCallbacks(keepOn)
    }

    override fun after() {
        app.unregisterActivityLifecycleCallbacks(keepOn)
    }
}
