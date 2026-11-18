package com.ouor.localchat.browser

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ouor.localchat.MainActivity
import com.ouor.localchat.testing.ScreenOnRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Two BrowserSurfaces can show one tab (chat cross-fade): the first one leaving must not hide it (FB5 m2). */
@RunWith(AndroidJUnit4::class)
class ShowParkTest {
    @get:Rule val screen = ScreenOnRule()

    @Test fun theTabIsParkedWhenTheLastHolderLeaves() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val engine = activity.browserEngine
                val id = engine.openAiTab("show-park", "about:blank")
                try {
                    val w = engine.webView(id)
                    assertEquals(View.INVISIBLE, w.visibility)
                    engine.show(id); engine.show(id)
                    engine.park(id)
                    assertEquals("one holder left", View.VISIBLE, w.visibility)
                    engine.park(id)
                    assertEquals("no holder left", View.INVISIBLE, w.visibility)
                } finally {
                    engine.close(id)
                }
            }
        }
    }
}
