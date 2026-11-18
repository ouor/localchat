package com.ouor.localchat

import android.app.Application
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.chat.run.RunServiceController
import com.ouor.localchat.chat.run.StartupRecovery
import com.ouor.localchat.core.ApplicationScope
import com.ouor.localchat.policy.Policies
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject lateinit var recovery: StartupRecovery

    @Inject lateinit var runService: RunServiceController

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    @Inject lateinit var browser: BrowserEngine

    @Inject lateinit var policies: Policies

    override fun onCreate() {
        super.onCreate()
        scope.launch { recovery.run() }
        runService.start()
        // Browser policies act through the engine's main-thread API (discard).
        policies.attach(browser, CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        browser.reportMemory(level)
    }
}
