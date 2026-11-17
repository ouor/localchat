package com.ouor.localchat.ui.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.R
import com.ouor.localchat.backup.BackupScope
import com.ouor.localchat.backup.BackupSummary
import com.ouor.localchat.backup.RestorePlan
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.Shots
import com.ouor.localchat.ui.components.ConnectionStatus
import com.ouor.localchat.ui.components.ConnectionUi
import com.ouor.localchat.ui.components.SettingsGroup
import com.ouor.localchat.ui.components.SettingsRow
import com.ouor.localchat.ui.format.CONTEXT_PRESETS
import com.ouor.localchat.ui.providers.CONTEXT_RANGE
import com.ouor.localchat.ui.providers.CheckUi
import com.ouor.localchat.ui.providers.EndpointFieldActions
import com.ouor.localchat.ui.providers.EndpointUiState
import com.ouor.localchat.ui.providers.KeySection
import com.ouor.localchat.ui.providers.LimitRow
import com.ouor.localchat.ui.format.OUTPUT_PRESETS
import com.ouor.localchat.ui.providers.OUTPUT_RANGE
import com.ouor.localchat.ui.providers.UrlField
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.LocalchatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FB3 track C, P-STABLE: what the user operates stays where it was across the interaction (bounds before == after). */
@RunWith(AndroidJUnit4::class)
class Fb3CStableTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        LocalchatTheme(false) { Column(Modifier.fillMaxSize().background(LcTheme.colors.surface)) { content() } }
    }

    private fun SemanticsNodeInteraction.box(): DpRect = getBoundsInRoot()

    private fun below() = rule.onNodeWithTag("below").box()

    private object NoActions : EndpointFieldActions {
        override fun onUrl(v: String) {}
        override fun onAllowHttp(v: Boolean) {}
        override fun onKey(v: String) {}
        override fun check() {}
        override fun saveKey() {}
        override fun editKey() {}
        override fun cancelEditKey() {}
    }

    @Test fun m7_typingTheUrlDoesNotMoveWhatIsBelow() {
        var url by mutableStateOf("")
        show {
            UrlField(EndpointUiState(isNew = true, baseUrl = url), NoActions)
            Box(Modifier.size(10.dp).testTag("below"))
        }
        val start = below()
        // Empty, invalid, http (warning + allow row), https: the helper and the http row are always there.
        listOf("h", "http://192.168.0.2:8080/v1", "https://api.example.com/v1", "").forEach {
            url = it
            rule.waitForIdle()
            assertEquals(it, start, below())
        }
    }

    @Test fun m11_checkButtonStaysAndTurnsIntoRecheck() {
        var ui by mutableStateOf<ConnectionUi?>(null)
        show {
            ConnectionStatus(ui, {}, {}, Modifier.testTag("status"))
            Box(Modifier.size(10.dp).testTag("below"))
        }
        val button = rule.onNodeWithText(ctx.getString(R.string.ui_check_connection)).box()
        val start = below()
        ui = ConnectionUi.Checking
        rule.waitForIdle()
        assertEquals(button.left, rule.onNodeWithText(ctx.getString(R.string.ui_check_connection)).box().left)
        assertEquals(button.top, rule.onNodeWithText(ctx.getString(R.string.ui_check_connection)).box().top)
        assertEquals(start, below())
        ui = ConnectionUi.Error("401 · bad key")
        rule.waitForIdle()
        val recheck = rule.onNodeWithText(ctx.getString(R.string.ui_conn_recheck)).box()
        assertEquals(button.left, recheck.left)
        assertEquals(button.top, recheck.top)
        assertEquals(start, below())
        ui = ConnectionUi.Ok(12)
        rule.waitForIdle()
        assertEquals(start, below())
    }

    @Test fun l12_replacingTheKeyKeepsTheCheckArea() {
        var editing by mutableStateOf(false)
        show {
            KeySection(EndpointUiState(isNew = false, keyHint = "abcd", editingKey = editing, check = CheckUi.Ok(3)), NoActions, 3)
            Box(Modifier.size(10.dp).testTag("below"))
        }
        val button = rule.onNodeWithText(ctx.getString(R.string.ui_check_connection)).box()
        val start = below()
        editing = true
        rule.waitForIdle()
        rule.onNodeWithText(ctx.getString(R.string.ui_api_key)).assertExists() // the dialog's field
        assertEquals(button, rule.onNodeWithText(ctx.getString(R.string.ui_check_connection), useUnmergedTree = false).box())
        assertEquals(start, below())
    }

    private fun plan(unlocked: Boolean = false, builtInOff: Boolean = false) =
        RestorePlan(Uri.EMPTY, RestorePlan.Kind.FULL, 3, 1, containsSecrets = true, secretsUnlocked = unlocked, conflicts = emptyList(), turnsOffBuiltInSensitive = builtInOff)

    @Test fun m8_unlockingKeepsThePlanAndItsButtons() {
        var step by mutableStateOf(BackupStep.Plan(plan()))
        show { Column { PlanStep(step, {}, {}, { _, _ -> }) } }
        val cancel = rule.onNodeWithTag("cancel").box()
        val restore = rule.onNodeWithTag("restore").box()
        val unlock = rule.onNodeWithTag("unlock").box()
        step = step.copy(unlocking = true)
        rule.waitForIdle()
        assertEquals(cancel, rule.onNodeWithTag("cancel").box())
        assertEquals(restore, rule.onNodeWithTag("restore").box())
        assertEquals(unlock.top, rule.onNodeWithTag("unlock").box().top)
        rule.onNodeWithTag("restore").assertIsNotEnabled()
        // Wrong password: the same layout, error in the helper line that was already there.
        step = BackupStep.Plan(plan(), passwordError = true)
        rule.waitForIdle()
        assertEquals(cancel, rule.onNodeWithTag("cancel").box())
        rule.onNodeWithTag("restore").assertIsEnabled()
        rule.onNodeWithText(ctx.getString(R.string.ui_password_wrong)).assertExists()
    }

    @Test fun fb2_restoreSaysWhenItTurnsTheBuiltInListOff() {
        var step by mutableStateOf(BackupStep.Plan(plan(unlocked = true)))
        show { Column { PlanStep(step, {}, {}, { _, _ -> }) } }
        rule.onNodeWithText(ctx.getString(R.string.fb3_c_restore_builtin_off)).assertDoesNotExist()
        step = BackupStep.Plan(plan(unlocked = true, builtInOff = true))
        rule.waitForIdle()
        rule.onNodeWithText(ctx.getString(R.string.fb3_c_restore_builtin_off)).assertExists()
        Shots.save("fb3-c-restore-builtin-off")
    }

    @Test fun m9_passwordErrorsDoNotMoveTheFields() {
        show { Column { ExportStep(BackupStep.Export(BackupScope.Full, BackupSummary(2, 1, 1024)), {}, { _, _, _ -> }) } }
        rule.onNode(isToggleable()).performClick()
        rule.waitForIdle()
        val fields = rule.onAllNodes(hasSetTextAction())
        val second = fields[1].box()
        fields[0].performTextInput("abc") // too short: error in the first helper
        rule.waitForIdle()
        assertEquals(second, rule.onAllNodes(hasSetTextAction())[1].box())
        fields[0].performTextInput("defgh")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("x") // mismatch: error in the second helper
        rule.waitForIdle()
        assertEquals(second, rule.onAllNodes(hasSetTextAction())[1].box())
        rule.onNodeWithText(ctx.getString(R.string.ui_password_mismatch)).assertExists()
    }

    @Test fun l6_limitWarningDoesNotPushTheNextRow() {
        var context by mutableStateOf(65_536)
        show {
            SettingsGroup {
                LimitRow("컨텍스트 창", Sourced(context, CapSource.USER), Sourced(131_072, CapSource.SERVER), CONTEXT_PRESETS, CONTEXT_RANGE, null, false) {}
                LimitRow("최대 출력 토큰", Sourced(8_192, CapSource.USER), Sourced(null, CapSource.UNKNOWN), OUTPUT_PRESETS, OUTPUT_RANGE, "4K" to 4_096, true) {}
            }
        }
        val next = rule.onNodeWithText("최대 출력 토큰").box()
        val warn = ctx.getString(R.string.fb_e3_limit_warn_gt_server, "128K")
        rule.onNodeWithContentDescription(warn, useUnmergedTree = true).assertDoesNotExist()
        context = 204_800 // above the server's 128K: warns
        rule.waitForIdle()
        rule.onNodeWithContentDescription(warn, useUnmergedTree = true).assertExists()
        assertEquals(next, rule.onNodeWithText("최대 출력 토큰").box())
        Shots.save("fb3-c-limit-warning-icon")
        // The warning text is in the menu.
        rule.onNodeWithText("컨텍스트 창").performClick()
        rule.onNodeWithText(warn).assertExists()
    }

    @Test fun l7_valueLengthDoesNotChangeTheRowHeight() {
        var value by mutableStateOf("gpt")
        show {
            // Narrow: the label leaves < 96dp, so the value goes under it.
            Box(Modifier.width(240.dp)) {
                SettingsGroup {
                    SettingsRow("새 대화 기본 모델 아주 긴 라벨", value = value, onClick = {})
                    SettingsRow("언어", divider = true, modifier = Modifier.testTag("next"), onClick = {})
                }
            }
        }
        val next = rule.onNodeWithTag("next").box()
        value = "provider-name/very-long-model-identifier-that-would-wrap-to-two-lines"
        rule.waitForIdle()
        assertEquals(next, rule.onNodeWithTag("next").box())
    }

    @Test fun settingsRowAnswersIntrinsicSizes() {
        // SubcomposeLayout threw here; a Layout answers.
        show {
            Row(Modifier.height(IntrinsicSize.Min).width(IntrinsicSize.Max).testTag("row")) {
                SettingsRow("언어", value = "English", subtitle = "sub")
            }
        }
        rule.onNodeWithText("English", useUnmergedTree = true).assertExists()
    }

    @Test fun l8_deletingADomainLeavesTheNextXInPlace() {
        var domains by mutableStateOf(listOf("a.example.com", "b.example.com", "c.example.com"))
        show { SettingsGroup { SensitiveDomainRows(domains) { domains = it } } }
        val del = ctx.getString(R.string.ui_delete)
        val secondX = rule.onAllNodesWithContentDescription(del)[1].box()
        rule.onAllNodesWithContentDescription(del)[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf("b.example.com", "c.example.com"), domains)
        Shots.save("fb3-c-domain-undo")
        // b's X is now the first X on screen, and it is where it was.
        assertEquals(secondX, rule.onAllNodesWithContentDescription(del)[0].box())
        rule.onNodeWithText(ctx.getString(R.string.fb3_c_undo)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a.example.com", "b.example.com", "c.example.com"), domains)
        assertEquals(secondX, rule.onAllNodesWithContentDescription(del)[1].box())
    }

    @Test fun fix_m2_limitValueStaysWhenTheWarningComes() { // FB3 fix ui M2
        var server by mutableStateOf(262_144)
        show { SettingsGroup { LimitRow("컨텍스트 창", Sourced(204_800, CapSource.USER), Sourced(server, CapSource.SERVER), CONTEXT_PRESETS, CONTEXT_RANGE, null, false) {} } }
        val value = rule.onNodeWithText("200K", useUnmergedTree = true).box()
        server = 131_072 // the server now says 128K: warns
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e3_limit_warn_gt_server, "128K"), useUnmergedTree = true).assertExists()
        assertEquals(value, rule.onNodeWithText("200K", useUnmergedTree = true).box())
    }

    @Test fun fix_m3_recheckKeepsItsLabelWhileChecking() { // FB3 fix ui m3
        var ui by mutableStateOf<ConnectionUi?>(ConnectionUi.Error("401 · bad key"))
        show { ConnectionStatus(ui, {}, null) }
        val recheck = rule.onNodeWithText(ctx.getString(R.string.ui_conn_recheck)).box()
        ui = ConnectionUi.Checking
        rule.waitForIdle()
        val checking = rule.onNodeWithText(ctx.getString(R.string.ui_conn_recheck)).box() // same label; the spinner widens it (as M11)
        assertEquals(recheck.left, checking.left)
        assertEquals(recheck.top, checking.top)
        ui = ConnectionUi.Ok(3)
        rule.waitForIdle()
        rule.onNodeWithText(ctx.getString(R.string.ui_check_connection)).assertExists()
    }

    @Test fun fix_m4_removedDomainSaysSoToTalkBack() { // FB3 fix ui m4
        var domains by mutableStateOf(listOf("a.example.com", "b.example.com"))
        show { SettingsGroup { SensitiveDomainRows(domains) { domains = it } } }
        val removed = hasStateDescription(ctx.getString(R.string.fb3_c_removed))
        rule.onNode(removed).assertDoesNotExist()
        rule.onAllNodesWithContentDescription(ctx.getString(R.string.ui_delete))[0].performClick()
        rule.waitForIdle()
        rule.onNode(removed).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.fb3_c_undo)).performClick()
        rule.waitForIdle()
        rule.onNode(removed).assertDoesNotExist()
    }
}
