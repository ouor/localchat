package com.ouor.localchat.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ouor.localchat.R
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.Sourced
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.components.SettingsGroup
import com.ouor.localchat.ui.format.CONTEXT_PRESETS
import com.ouor.localchat.ui.markdown.MarkdownMessage
import com.ouor.localchat.ui.providers.CONTEXT_RANGE
import com.ouor.localchat.ui.providers.LimitRow
import com.ouor.localchat.ui.settings.TextListRows
import com.ouor.localchat.ui.theme.LcTheme
import com.ouor.localchat.ui.theme.LocalchatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FB3 QA track U regressions. P-STABLE ones assert bounds before == after the interaction. */
@RunWith(AndroidJUnit4::class)
class Fb3UTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx get() = rule.activity

    @Test fun u1_undoAtTheBottomOfThePageKeepsTheListInPlace() { // M2
        var domains by mutableStateOf(listOf("a.example.com", "b.example.com", "c.example.com"))
        var pending: List<String>? = null // the real save is async (DataStore): the list comes back a few frames later
        rule.setContent {
            LocalchatTheme(false) {
                Column(Modifier.height(400.dp).verticalScroll(rememberScrollState()).background(LcTheme.colors.surface)) {
                    Spacer(Modifier.height(800.dp))
                    SettingsGroup { TextListRows(domains) { pending = it } }
                    Box(Modifier.size(10.dp).testTag("end"))
                }
            }
        }
        fun save() { rule.runOnIdle { pending?.let { domains = it }; pending = null } }
        rule.onNodeWithTag("end").performScrollTo()
        val del = ctx.getString(R.string.ui_delete)
        rule.onAllNodesWithContentDescription(del)[0].performClick()
        save()
        val undo = rule.onNodeWithText(ctx.getString(R.string.fb3_c_undo))
        val undoAt = undo.getBoundsInRoot()
        val rowAt = rule.onNodeWithText("a.example.com").getBoundsInRoot()
        val nextX = rule.onAllNodesWithContentDescription(del)[0].getBoundsInRoot() // b's X
        val end = rule.onNodeWithTag("end").getBoundsInRoot()
        undo.performClick()
        rule.waitForIdle() // a frame before the save lands
        save()
        assertEquals(rowAt, rule.onNodeWithText("a.example.com").getBoundsInRoot())
        // Where 되돌리기 was is now a's own X, not another domain's.
        val aX = rule.onAllNodesWithContentDescription(del)[0].getBoundsInRoot()
        assertTrue("a's X $aX in the undo row $undoAt", aX.top <= undoAt.top && aX.bottom >= undoAt.bottom)
        assertEquals(nextX, rule.onAllNodesWithContentDescription(del)[1].getBoundsInRoot())
        assertEquals(end, rule.onNodeWithTag("end").getBoundsInRoot())
        assertEquals(listOf("a.example.com", "b.example.com", "c.example.com"), domains)
    }

    @Test fun u2_backClosesTheOpenDrawerAndStaysInTheApp() { // M3
        val drawer = DrawerState(DrawerValue.Open)
        rule.setContent {
            LocalchatTheme(false) {
                CompactDrawer(drawer, { Text("DRAWER", Modifier.width(300.dp).fillMaxSize()) }) { Text("CONTENT", Modifier.fillMaxSize()) }
            }
        }
        rule.waitForIdle()
        assertTrue(drawer.isOpen)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { drawer.isClosed }
        assertFalse(rule.activity.isFinishing)
        // Closed: back is no longer ours (the screen or the system gets it).
        rule.runOnIdle { assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Test fun u4_sourceTagChangeKeepsTheValueInPlace() { // m4
        var source by mutableStateOf(CapSource.SERVER)
        rule.setContent {
            LocalchatTheme(false) {
                SettingsGroup { LimitRow("컨텍스트 창", Sourced(1_048_576, source), Sourced(1_048_576, CapSource.SERVER), CONTEXT_PRESETS, CONTEXT_RANGE, null, false) {} }
            }
        }
        val value = rule.onNodeWithText("1M", useUnmergedTree = true)
        val at = value.getBoundsInRoot()
        CapSource.entries.forEach {
            source = it
            rule.waitForIdle()
            assertEquals(it.name, at, value.getBoundsInRoot())
        }
    }

    @Test fun u6_singleTildeIsTextAndBoldClosesBeforeKorean() { // m6
        rule.setContent {
            LocalchatTheme(false) {
                MarkdownMessage("~a~ ~b~ 그리고 이것은 **\"강조\"**입니다", emptyList(), streaming = false, onLinkClick = {}, onCitationClick = {}, modifier = Modifier.width(360.dp))
            }
        }
        val text = rule.onNodeWithText("강조", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals("~a~ ~b~ 그리고 이것은 \"강조\"입니다", text.text)
        assertTrue(text.spanStyles.none { it.item.textDecoration == TextDecoration.LineThrough })
        val bold = text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { text.text.substring(it.start, it.end) }
        assertEquals(listOf("\"강조\""), bold)
    }

    private fun showMd(md: String) = rule.setContent {
        LocalchatTheme(false) { MarkdownMessage(md, emptyList(), streaming = false, onLinkClick = {}, onCitationClick = {}, modifier = Modifier.width(360.dp)) }
    }

    @Test fun r1_tokenLikeTextShowsAsWrittenWithoutACrash() { // review 1
        val md = "리터럴 ⦃c⦄ ⦃j⦄ ⦃m⦄ ⦃m0⦄ ⦃c1⦄ ⦃⦄ 끝"
        showMd(md)
        assertEquals(md, rule.onNodeWithText("리터럴", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
    }

    @Test fun r4_joinerNeverShows() { // review 4: inside bold, in a table cell, in link text
        showMd("앞 **강조**\" 뒤\n\n| 열 |\n|---|\n| **\"표\"**입니다 |\n\n[**\"링크\"**입니다](https://example.com)")
        rule.waitForIdle()
        val texts = rule.onAllNodes(androidx.compose.ui.test.hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
        assertTrue(texts.toString(), texts.none { "⦃" in it || "⦄" in it })
        listOf("강조\" 뒤", "\"표\"입니다", "\"링크\"입니다").forEach { s -> assertTrue("$s in $texts", texts.any { s in it }) }
    }

    @Test fun r2_drawerBackWinsOverTheBrowserPanelWhateverTheOrder() { // review 2
        val drawer = DrawerState(DrawerValue.Open)
        var s by mutableStateOf(
            com.ouor.localchat.ui.browser.BrowserUiState(
                com.ouor.localchat.ui.browser.PanelState.Half,
                listOf(com.ouor.localchat.browser.TabInfo(1, "t", "https://example.com")),
                sessionId = "A",
            ),
        )
        val panels = mutableListOf<com.ouor.localchat.ui.browser.PanelState>()
        val actions = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(com.ouor.localchat.ui.browser.BrowserActions::class.java),
        ) { proxy, m, args ->
            when (m.name) {
                "setPanel" -> { val p = args[0] as com.ouor.localchat.ui.browser.PanelState; panels += p; s = s.copy(panel = p); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                "toString" -> "actions"
                else -> null // every other action is a no-op here
            }
        } as com.ouor.localchat.ui.browser.BrowserActions
        var panel by mutableStateOf(false)
        rule.setContent {
            LocalchatTheme(false) {
                CompactDrawer(drawer, { Text("DRAWER", Modifier.width(300.dp).fillMaxSize()) }) {
                    Box(Modifier.fillMaxSize()) { if (panel) com.ouor.localchat.ui.browser.BrowserOverlay(s, actions) }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { panel = true } // the panel's back callback now registers after the drawer's
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { drawer.isClosed }
        rule.runOnIdle { assertEquals(emptyList<Any>(), panels) } // the panel stayed Half
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() } // drawer closed: now the panel's
        rule.runOnIdle { assertEquals(listOf(com.ouor.localchat.ui.browser.PanelState.Peek), panels) }
    }

    @Test fun r6_undoneDomainIsForgottenOnceSaved() { // review 6
        var domains by mutableStateOf(listOf("a.example.com", "b.example.com"))
        rule.setContent { LocalchatTheme(false) { SettingsGroup { TextListRows(domains) { domains = it } } } }
        rule.onAllNodesWithContentDescription(ctx.getString(R.string.ui_delete))[0].performClick()
        rule.onNodeWithText(ctx.getString(R.string.fb3_c_undo)).performClick()
        rule.waitForIdle()
        // Removed elsewhere later (e.g. a restore): the row goes, it does not come back greyed with 되돌리기.
        rule.runOnIdle { domains = listOf("b.example.com") }
        rule.onNodeWithText("a.example.com").assertDoesNotExist()
        rule.onNodeWithText(ctx.getString(R.string.fb3_c_undo)).assertDoesNotExist()
    }
}
