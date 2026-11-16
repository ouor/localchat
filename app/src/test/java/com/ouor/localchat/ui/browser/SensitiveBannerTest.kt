package com.ouor.localchat.ui.browser

import app.cash.turbine.test
import com.ouor.localchat.browser.Control
import com.ouor.localchat.browser.Owner
import com.ouor.localchat.browser.TabInfo
import com.ouor.localchat.browser.safety.SensitiveUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** FB2 sec M2: switching the sensitive settings updates the panel's banner without any tab event. */
class SensitiveBannerTest {

    @Test fun `a rule change alone re-marks sensitive tabs`() = runTest {
        val tabs = listOf(TabInfo(1, "bank", "https://www.paypal.com/", Owner.User, Control.USER, loading = false))
        val ui = MutableStateFlow(BrowserUiState(tabs = tabs))
        val rule = MutableStateFlow(SensitiveUrls(listOf("paypal.com"), emptyList()))
        ui.withSensitive(rule.map { it::isSensitive }).test {
            assertEquals(setOf(1), awaitItem().sensitive)
            rule.value = SensitiveUrls(emptyList(), emptyList()) // built-in list switched off
            assertEquals(emptySet<Int>(), awaitItem().sensitive)
            rule.value = SensitiveUrls(emptyList(), listOf("paypal.com")) // user domain added
            assertEquals(setOf(1), awaitItem().sensitive)
        }
    }
}
