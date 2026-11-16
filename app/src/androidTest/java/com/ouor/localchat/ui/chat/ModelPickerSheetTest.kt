package com.ouor.localchat.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.R
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.ModelInfo
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.testing.ScreenOnRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getBoundsInRoot
import com.ouor.localchat.ui.providers.providerModelGroup
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.ouor.localchat.ui.theme.LocalchatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FB-3: provider groups start collapsed; headers toggle their own rows; recent stays open. */
@RunWith(AndroidJUnit4::class)
class ModelPickerSheetTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val caps = ModelCapabilities(
        Sourced(true, CapSource.SERVER), Sourced(false, CapSource.DEFAULT), Sourced(null, CapSource.DEFAULT),
        Sourced(131_072, CapSource.SERVER), Sourced(null, CapSource.UNKNOWN),
    )
    private fun model(ep: String, id: String, used: Long? = null) = ModelInfo(ep, id, id, false, true, false, used, caps)
    private val names = mapOf("a" to "Alpha", "b" to "Beta", "z" to "Zero")
    private fun rows(prefix: String) = rule.onAllNodes(hasText(prefix, substring = true), useUnmergedTree = true)
    private fun header(name: String) = rule.onNodeWithContentDescription("$name,", substring = true)

    private fun show(models: List<ModelInfo>, current: ModelRef? = null, showRecent: Boolean = true) = rule.setContent {
        LocalchatTheme(false) { ModelPickerList(models, current, { names[it].orEmpty() }, showRecent, listOf("a", "b", "z"), {}) }
    }

    @Test fun groupsStartCollapsedAndToggle() {
        show(List(5) { model("a", "alpha-m$it") } + List(300) { model("b", "beta-m$it") }, current = ModelRef("b", "beta-m3", "beta-m3"))
        rows("alpha-m").assertCountEquals(0)
        rows("beta-m").assertCountEquals(0)
        rule.onAllNodesWithContentDescription(",", substring = true).assertCountEquals(3)
        // The selected model's group says so while collapsed.
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e3_group_cd_selected, "Beta", 300)).assertExists()

        header("Alpha").performClick()
        rows("alpha-m").assertCountEquals(5)
        rows("beta-m").assertCountEquals(0)
        header("Beta").performClick() // several groups open at once
        rule.onNodeWithText("beta-m0", useUnmergedTree = true).assertExists()
        rows("alpha-m").assertCountEquals(5)
        header("Alpha").performClick()
        rows("alpha-m").assertCountEquals(0)
    }

    @Test fun emptyProviderShowsHint() {
        show(listOf(model("a", "alpha-m0")))
        rule.onNodeWithText(ctx.getString(R.string.fb_e3_group_empty)).assertDoesNotExist()
        header("Zero").performClick()
        rule.onNodeWithText(ctx.getString(R.string.fb_e3_group_empty)).assertExists()
    }

    @Test fun recentStaysOpenAndHidesWhenEmpty() {
        show(listOf(model("a", "alpha-m0", used = 5L), model("a", "alpha-m1")))
        rule.onNodeWithText(ctx.getString(R.string.ui_recent)).assertExists()
        rows("alpha-m").assertCountEquals(1)
    }

    @Test fun sheetOpensCollapsed() {
        val models = listOf(model("a", "alpha-m0", used = 5L)) + List(4) { model("a", "alpha-m${it + 1}") } + List(308) { model("b", "beta-m$it") }
        rule.setContent {
            LocalchatTheme(false) {
                ModelPickerSheet(models, ModelRef("b", "beta-m3", "beta-m3"), { names[it].orEmpty() }, {}, endpointIds = listOf("a", "b", "z")) {}
            }
        }
        rule.waitForIdle()
        rows("beta-m").assertCountEquals(0)
        rows("alpha-m").assertCountEquals(1) // recent only
        com.ouor.localchat.ui.Shots.save("fb1-e3-model-sheet-collapsed")
        header("Beta").performClick()
        rule.waitForIdle()
        com.ouor.localchat.ui.Shots.save("fb1-e3-model-sheet-expanded")
    }

    @Test fun disabledProviderIsNotAGroup() {
        fun ep(id: String, enabled: Boolean) = com.ouor.localchat.core.model.Endpoint(id, names.getValue(id), "https://$id", null, enabled, allowHttp = false, hasKey = true)
        // Same rule the chat (ComposerViewModel) and settings (SettingsViewModel) sheets use.
        val ids = pickerEndpoints(listOf(ep("a", true), ep("b", false), ep("z", true))).map { it.id }
        rule.setContent { LocalchatTheme(false) { ModelPickerList(listOf(model("a", "alpha-m0")), null, { names[it].orEmpty() }, false, ids, {}) } }
        header("Alpha").assertExists()
        header("Zero").assertExists()
        header("Beta").assertDoesNotExist()
    }

    @Test fun providerScreenModelListIsLazy() {
        val many = List(308) { model("b", "beta-m$it") }
        var open by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            LocalchatTheme(false) {
                androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.height(600.dp)) {
                    providerModelGroup(many, open, { open = !open }, false, {}, {}, {}, {})
                }
            }
        }
        rows("beta-m").assertCountEquals(0)
        header(ctx.getString(R.string.ui_models)).performClick()
        rule.onNodeWithText("beta-m0", useUnmergedTree = true).assertExists()
        // Only the rows on screen are composed, not all 308.
        val composed = rows("beta-m").fetchSemanticsNodes().size
        org.junit.Assert.assertTrue("composed $composed rows", composed in 1..30)
        header(ctx.getString(R.string.ui_models)).performClick()
        rows("beta-m").assertCountEquals(0)
    }

    @Test fun expandingAGroupDoesNotMoveItsHeader() {
        val models = List(5) { model("a", "alpha-m$it") } + List(300) { model("b", "beta-m$it") }
        rule.setContent {
            LocalchatTheme(false) { ModelPickerSheet(models, null, { names[it].orEmpty() }, {}, endpointIds = listOf("a", "b", "z")) {} }
        }
        rule.waitForIdle()
        val before = header("Beta").getBoundsInRoot().top
        header("Beta").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("beta-m0", useUnmergedTree = true).assertExists()
        org.junit.Assert.assertEquals(before, header("Beta").getBoundsInRoot().top)
        val alpha = header("Alpha").getBoundsInRoot().top
        header("Alpha").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(alpha, header("Alpha").getBoundsInRoot().top)
    }

    @Test fun backupSheetCheckboxStaysPutWhenFieldsAppear() {
        // Same LcBottomSheet(heightFraction) setup as DataScreen's full-backup sheet (H5).
        var withKeys by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            LocalchatTheme(false) {
                com.ouor.localchat.ui.components.LcBottomSheet({}, "Backup", heightFraction = 0.9f) {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.Text("summary")
                        androidx.compose.foundation.layout.Row {
                            com.ouor.localchat.ui.components.LcCheckbox(withKeys, { withKeys = it }, androidx.compose.ui.Modifier.testTag("keys"))
                        }
                        if (withKeys) repeat(3) { androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(90.dp)) }
                        androidx.compose.material3.Text("file.zip")
                    }
                }
            }
        }
        rule.waitForIdle()
        val before = rule.onNodeWithTag("keys").getBoundsInRoot().top
        rule.onNodeWithTag("keys").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(before, rule.onNodeWithTag("keys").getBoundsInRoot().top)
    }

    @Test fun noRecentSectionWithoutHistory() {
        show(listOf(model("a", "alpha-m0")))
        rule.onNodeWithText(ctx.getString(R.string.ui_recent)).assertDoesNotExist()
        rows("alpha-m").assertCountEquals(0)
    }
}
