package com.ouor.localchat.ui

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Whole-screen PNG (popups included) at /data/local/tmp/<name>.png for the review report.
 * Written by the shell user so it survives the uninstall after connectedAndroidTest; pull with adb.
 */
object Shots {
    fun save(name: String) {
        val ins = InstrumentationRegistry.getInstrumentation()
        ins.waitForIdleSync()
        Thread.sleep(400) // let menu/sheet enter animations finish
        ins.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name.png").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } // wait for screencap to finish
        }
    }
}
