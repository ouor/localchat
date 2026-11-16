package com.ouor.localchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.then
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import com.ouor.localchat.ui.components.SourceBadge
import com.ouor.localchat.R
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.providers.CONTEXT_PRESETS
import com.ouor.localchat.ui.providers.CONTEXT_RANGE
import com.ouor.localchat.ui.providers.LimitRow
import com.ouor.localchat.ui.components.CapabilityIcons
import com.ouor.localchat.ui.components.EndpointGroupHeader
import com.ouor.localchat.ui.components.ModelRow
import com.ouor.localchat.ui.components.SessionListItem
import com.ouor.localchat.ui.components.SettingsGroup
import com.ouor.localchat.ui.components.SettingsRow
import com.ouor.localchat.ui.theme.LocalchatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FB-2: extreme strings at 200% font and 360dp keep labels on one line and trailing icons on screen. */
@RunWith(AndroidJUnit4::class)
class LayoutStressTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private val model80 = (1..16).joinToString("-") { "mdl$it" }.take(80)
    private val provider40 = "Provider-" + "x".repeat(31)
    private val title100 = "아주 긴 대화 제목 ".repeat(10).take(100)
    private val cjk60 = "가나다라마바사아자차카타파하".repeat(5).take(60)

    private fun stress(content: @Composable () -> Unit) = rule.setContent {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f) then DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 780.dp))) {
            LocalchatTheme(darkTheme = false) { Column(Modifier.fillMaxWidth()) { content() } }
        }
    }

    private fun lines(n: SemanticsNodeInteraction): Int {
        val out = mutableListOf<TextLayoutResult>()
        n.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.first().lineCount
    }

    private fun assertOnScreen(n: SemanticsNodeInteraction) {
        val root = rule.onRoot().getBoundsInRoot()
        val b = n.getBoundsInRoot()
        assertTrue("$b outside $root", b.left >= root.left && b.right <= root.right && b.right > b.left)
    }

    @Test fun settingsRowKeepsLabelOnOneLine() {
        stress {
            SettingsGroup {
                SettingsRow("새 대화 기본 모델", value = model80, onClick = {}) { Icon(Icons.Rounded.ChevronRight, "chev1", Modifier.size(20.dp)) }
                SettingsRow("새 대화 기본 모델 $cjk60", value = model80, divider = true, onClick = {}) { Icon(Icons.Rounded.ChevronRight, "chev2", Modifier.size(20.dp)) }
                SettingsRow(provider40, subtitle = "api.example.com", divider = true) { Icon(Icons.Rounded.ChevronRight, "chev3", Modifier.size(20.dp)) }
            }
        }
        assertEquals(1, lines(rule.onNodeWithText("새 대화 기본 모델", useUnmergedTree = true)))
        assertEquals(1, lines(rule.onNodeWithText("새 대화 기본 모델 $cjk60", useUnmergedTree = true)))
        assertEquals(1, lines(rule.onNodeWithText(provider40, useUnmergedTree = true)))
        // The value is never squeezed into a vertical column: one line beside, or at most two below.
        rule.onAllNodesWithTextCount(model80).forEach { assertTrue(lines(it) <= 2) }
        listOf("chev1", "chev2", "chev3").forEach { assertOnScreen(rule.onNodeWithContentDescription(it, useUnmergedTree = true)) }
    }

    @Test fun modelRowAndHeaderKeepIconsOnScreen() {
        stress {
            EndpointGroupHeader(provider40, Color.Red, 308, expanded = false, onToggle = {}, hasSelected = true, http = true)
            ModelRow(model80, {}, subtitle = model80, notListed = true, selected = true) {
                CapabilityIcons(tools = true, image = true, reasoning = true)
            }
            SessionListItem(title100, {}, running = true)
            SessionListItem(cjk60, {})
        }
        rule.onAllNodesWithTextCount(model80).forEach { assertEquals(1, lines(it)) }
        assertOnScreen(rule.onNodeWithContentDescription(ctx.getString(R.string.ui_selected), useUnmergedTree = true))
        assertOnScreen(rule.onNodeWithContentDescription(ctx.getString(R.string.ui_cap_reasoning), substring = true, useUnmergedTree = true))
        assertOnScreen(rule.onNodeWithContentDescription(provider40, substring = true))
        assertEquals(1, lines(rule.onNodeWithText(title100, useUnmergedTree = true)))
        assertEquals(1, lines(rule.onNodeWithText(cjk60, useUnmergedTree = true)))
    }

    @Test fun limitMenuItemsStayOnOneLine() {
        stress {
            SettingsGroup {
                LimitRow(
                    "컨텍스트 창", Sourced(200_000, CapSource.USER), Sourced(131_072, CapSource.SERVER),
                    CONTEXT_PRESETS, CONTEXT_RANGE, null, false,
                ) {}
            }
        }
        assertEquals(1, lines(rule.onNodeWithText("컨텍스트 창", useUnmergedTree = true)))
        rule.onNodeWithText("컨텍스트 창", useUnmergedTree = true).performClick()
        val auto = ctx.getString(R.string.fb_e3_limit_auto_server, "128K")
        assertEquals(1, lines(rule.onNodeWithText(auto, useUnmergedTree = true)))
        assertEquals(1, lines(rule.onNodeWithText(ctx.getString(R.string.fb_e3_limit_custom_n, "200,000"), useUnmergedTree = true)))
        // User value above the server value is allowed, with a note under the row.
        rule.onNodeWithText(ctx.getString(R.string.fb_e3_limit_warn_gt_server, "128K")).assertExists()
    }

    @Test fun limitMenuReachesCustomItemAndOpensDialog() {
        // Popups take their font scale from the window's resources, not from DeviceConfigurationOverride:
        // scale this test activity's resources (only this activity; no system setting is touched).
        rule.runOnUiThread {
            val res = rule.activity.resources
            @Suppress("DEPRECATION")
            res.updateConfiguration(android.content.res.Configuration(res.configuration).apply { fontScale = 2f }, res.displayMetrics)
        }
        stress {
            SettingsGroup {
                LimitRow("컨텍스트 창", Sourced(131_072, CapSource.SERVER), Sourced(131_072, CapSource.SERVER), CONTEXT_PRESETS, CONTEXT_RANGE, null, false) {}
            }
        }
        rule.onNodeWithText("컨텍스트 창", useUnmergedTree = true).performClick()
        val custom = rule.onNodeWithText(ctx.getString(R.string.fb_e3_limit_custom), useUnmergedTree = true)
        // The menu itself runs at 200% too (popup inherits the font scale).
        assertEquals(2f, layout(custom).layoutInput.density.fontScale)
        custom.performScrollTo()
        rule.onNodeWithText("1M", useUnmergedTree = true).assertIsDisplayed()
        custom.assertIsDisplayed()
        Shots.save("fb1-e3-limit-menu")
        custom.performClick()
        rule.onNodeWithText(ctx.getString(R.string.fb_e3_limit_range, "1,024", "10,000,000")).assertIsDisplayed()
    }

    @Test fun badgeTextFitsAt200Percent() {
        stress { SourceBadge(CapSource.USER, Modifier.testTag("badge")) }
        val badge = rule.onNodeWithTag("badge").getBoundsInRoot()
        val text = layout(rule.onNodeWithText(ctx.getString(R.string.ui_src_user), useUnmergedTree = true))
        val badgePx = with(rule.density) { (badge.bottom - badge.top).toPx() }
        assertTrue("text ${text.multiParagraph.height}px > badge ${badgePx}px", text.multiParagraph.height <= badgePx + 0.5f)
    }

    private fun layout(n: SemanticsNodeInteraction): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        n.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.first()
    }

    @Test fun settingsShot() {
        stress {
            SettingsGroup {
                SettingsRow(ctx.getString(R.string.ui_providers), subtitle = provider40, onClick = {}) { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp)) }
                SettingsRow(ctx.getString(R.string.ui_default_model), value = model80, divider = true, onClick = {}) { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp)) }
                SettingsRow(ctx.getString(R.string.ui_default_model), value = "deepseek-chat", divider = true, onClick = {}) { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp)) }
                SettingsRow(ctx.getString(R.string.ui_language), value = "English", divider = true, onClick = {})
            }
        }
        rule.waitForIdle()
        Shots.save("fb1-e3-settings-row")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): List<SemanticsNodeInteraction> {
        val all = onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true)
        return (0 until all.fetchSemanticsNodes().size).map { all[it] }
    }
}
