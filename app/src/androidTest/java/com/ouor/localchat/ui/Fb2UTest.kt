package com.ouor.localchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ouor.localchat.R
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.components.LcTopBar
import com.ouor.localchat.ui.components.LocalReducedMotion
import com.ouor.localchat.ui.components.SettingsGroup
import com.ouor.localchat.ui.components.SettingsRow
import com.ouor.localchat.ui.nav.LcNavDisplay
import com.ouor.localchat.ui.providers.CONTEXT_PRESETS
import com.ouor.localchat.ui.providers.CONTEXT_RANGE
import com.ouor.localchat.ui.providers.LimitRow
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.LocalchatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** FB2-1 (limit menu at the row's end edge) and FB2-3 (shared axis / fade through / reduced motion). */
@RunWith(AndroidJUnit4::class)
class Fb2UTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    private val density get() = rule.activity.resources.displayMetrics.density

    @Test fun limitMenuOpensAlignedToTheRowEndEdge() {
        rule.setContent {
            LocalchatTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(LcTheme.colors.surface)) {
                    Spacer(Modifier.height(160.dp))
                    SettingsGroup {
                        LimitRow("컨텍스트 창", Sourced(65_536, CapSource.USER), Sourced(131_072, CapSource.SERVER), CONTEXT_PRESETS, CONTEXT_RANGE, null, true) {}
                        LimitRow("최대 출력 토큰", Sourced(8_192, CapSource.USER), Sourced(null, CapSource.UNKNOWN), CONTEXT_PRESETS, CONTEXT_RANGE, null, false) {}
                    }
                }
            }
        }
        val rowNode = rule.onNodeWithText("컨텍스트 창").fetchSemanticsNode()
        rule.onNodeWithText("컨텍스트 창").performClick()
        val item = rule.onNodeWithText(ctx.getString(R.string.fb_e3_limit_auto_server, "128K")).fetchSemanticsNode()
        val rowRight = rowNode.positionOnScreen.x + rowNode.size.width
        val itemLeft = item.positionOnScreen.x
        val itemRight = itemLeft + item.size.width
        assertTrue("menu right $itemRight vs row right $rowRight", abs(itemRight - rowRight) <= 16 * density)
        assertTrue("menu left $itemLeft must clear the 16dp screen margin", itemLeft > 16 * density)
        // Below the row when there is room.
        assertTrue(item.positionOnScreen.y >= rowNode.positionOnScreen.y + rowNode.size.height - 1)
        Shots.save("fb2-u-limit-menu")
    }

    private val stack = mutableStateListOf("home")

    private fun nav(reduced: Boolean = false) = rule.setContent {
        LocalchatTheme(darkTheme = true) {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                LcNavDisplay(stack, { stack.removeAt(stack.lastIndex) }, listOf(rememberSaveableStateHolderNavEntryDecorator())) { key ->
                    NavEntry(key) {
                        Column(Modifier.fillMaxSize().testTag("page-$key").background(LcTheme.colors.surface)) {
                            LcTopBar("screen-$key")
                            SettingsGroup { repeat(4) { SettingsRow("$key row $it", value = "값", divider = it < 3) } }
                        }
                    }
                }
            }
        }
    }

    private fun left(key: String) = rule.onNodeWithTag("page-$key").getBoundsInRoot().left

    @Test fun pushSlidesInFromTheEndThenPopReturns() {
        nav()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { stack.add("detail") }
        rule.mainClock.advanceTimeBy(48)
        // Mid-push: both screens composed, the new one offset toward the end edge, not scaled.
        rule.onNodeWithText("screen-home").assertExists()
        assertTrue(left("detail") > 0.dp)
        val rootWidth = rule.onRoot().getBoundsInRoot().run { right - left }
        assertEquals(rootWidth.value, rule.onNodeWithTag("page-detail").getBoundsInRoot().run { right - left }.value, 0.5f) // no scale
        Shots.save("fb2-u-push-mid")
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("screen-home").assertDoesNotExist()
        assertEquals(0f, left("detail").value, 0.5f)

        rule.runOnIdle { stack.removeAt(stack.lastIndex) }
        rule.mainClock.advanceTimeBy(48)
        assertTrue("pop enters from the start side", left("home") < 0.dp)
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("screen-detail").assertDoesNotExist()
        rule.onNodeWithText("screen-home").assertExists()
    }

    @Test fun replacingTheWholeStackFadesWithoutSliding() {
        nav()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { stack.clear(); stack.add("other") }
        rule.mainClock.advanceTimeBy(80)
        assertEquals(0f, left("other").value, 0.5f)
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithText("screen-home").assertDoesNotExist()
        rule.onNodeWithText("screen-other").assertExists()
    }

    @Test fun reducedMotionSwitchesInstantly() {
        nav(reduced = true)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { stack.add("detail") }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("screen-home").assertDoesNotExist()
        assertEquals(0f, left("detail").value, 0.5f)
    }
}
