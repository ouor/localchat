package com.ouor.localchat

import android.app.Application
import com.ouor.localchat.chat.run.RunServiceController
import com.ouor.localchat.chat.run.StartupRecovery
import com.ouor.localchat.core.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject lateinit var recovery: StartupRecovery

    @Inject lateinit var runService: RunServiceController

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        scope.launch { recovery.run() }
        runService.start()
    }
}
