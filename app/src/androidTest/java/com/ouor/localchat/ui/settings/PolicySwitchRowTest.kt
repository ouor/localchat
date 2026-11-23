package com.ouor.localchat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.R
import com.ouor.localchat.agent.R as PolicyR
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.Shots
import com.ouor.localchat.ui.components.SettingsGroup
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.LocalchatTheme
import com.ouor.localchat.policy.SettingItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FB2-2 through a policy switch row: turning the built-in list off asks once (cancel keeps it on); turning it on does not ask. */
@RunWith(AndroidJUnit4::class)
class PolicySwitchRowTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var enabled by mutableStateOf(true)

    private fun switch() = rule.onNode(isToggleable())
    private fun title() = rule.onNodeWithText(ctx.getString(PolicyR.string.agent_builtin_off_title))

    @Test fun offAsksCancelKeepsOnConfirmTurnsOff() {
        rule.setContent {
            LocalchatTheme(true) {
                Column(Modifier.fillMaxSize().background(LcTheme.colors.surface).statusBarsPadding().padding(top = 16.dp)) {
                    SettingsGroup {
                        // A policy's switch drawn by shape (browser-layers 4-1): here the built-in sensitive list's.
                        val description = if (enabled) ctx.resources.getQuantityString(PolicyR.plurals.agent_builtin_on, 156, 156) else ctx.getString(PolicyR.string.agent_builtin_off)
                        val confirm = SettingItem.Confirm(ctx.getString(PolicyR.string.agent_builtin_off_title), ctx.getString(PolicyR.string.agent_builtin_off_body), ctx.getString(PolicyR.string.agent_builtin_off_confirm))
                        SwitchSettingRow(ctx.getString(PolicyR.string.agent_sensitive_builtin), description, enabled, confirm) { enabled = it }
                    }
                }
            }
        }
        switch().assertIsOn()
        rule.onNodeWithText(ctx.resources.getQuantityString(PolicyR.plurals.agent_builtin_on, 156, 156)).assertExists()
        Shots.save("fb2-s-on")

        switch().performClick()
        title().assertExists()
        Shots.save("fb2-s-confirm")
        rule.onNodeWithText(ctx.getString(R.string.ui_cancel)).performClick()
        title().assertDoesNotExist()
        switch().assertIsOn()

        switch().performClick()
        rule.onNodeWithText(ctx.getString(PolicyR.string.agent_builtin_off_confirm)).performClick()
        title().assertDoesNotExist()
        switch().assertIsOff()
        rule.onNodeWithText(ctx.getString(PolicyR.string.agent_builtin_off)).assertExists()
        Shots.save("fb2-s-off")

        switch().performClick() // turning on asks nothing
        title().assertDoesNotExist()
        switch().assertIsOn()
    }
}
