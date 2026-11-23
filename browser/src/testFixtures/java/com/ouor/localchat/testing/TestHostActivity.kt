package com.ouor.localchat.testing

import android.app.Activity
import android.os.Bundle
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.browser.BrowserTabHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** An Activity that is only the engine's host ([BrowserTabHost]), for device tests without the app. */
class TestHostActivity : Activity() {

    lateinit var host: BrowserTabHost
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = BrowserTabHost(this).also { it.bind(engine, null) }
        setContentView(host)
    }

    companion object {
        /** One engine per test process, like the app's one shared browser. */
        val engine: BrowserEngine by lazy {
            BrowserEngine(androidx.test.core.app.ApplicationProvider.getApplicationContext(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
    }
}
