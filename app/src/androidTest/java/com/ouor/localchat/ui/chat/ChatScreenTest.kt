package com.ouor.localchat.ui.chat

import android.net.Uri
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.background
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.R
import com.ouor.localchat.browser.BrowserPrompt
import com.ouor.localchat.chat.run.ApprovalInfo
import com.ouor.localchat.chat.run.Pause
import com.ouor.localchat.chat.run.Resolution
import com.ouor.localchat.chat.run.RunPhase
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.ui.browser.BrowserActions
import com.ouor.localchat.ui.browser.BrowserUiState
import com.ouor.localchat.ui.browser.PanelState
import com.ouor.localchat.testing.ScreenOnRule
import com.ouor.localchat.ui.theme.LocalchatTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed-state ChatScreen tests (architecture 11): empty, streaming, approval wait, error. */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule val screen = ScreenOnRule()
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val resolved = mutableListOf<Resolution>()
    private val read = mutableListOf<Pair<String, String>>()
    private val opened = mutableListOf<String?>()

    private val chat = object : ChatActions {
        override val sessionId = "s"
        override fun stop() {}
        override fun selectVersion(messageId: String) {}
        override fun regenerate(assistantId: String, model: ModelRef) {}
        override fun continueReply(assistantId: String) {}
        override fun editAndResend(userId: String, text: String, attachmentIds: List<String>, model: ModelRef) {}
        override fun resolve(resolution: Resolution) { resolved += resolution }
        override fun retryWrites() {}
        override fun rename(title: String) {}
        override fun delete() {}
        override fun compactNow() {}
        override fun readAloud(messageId: String, text: String) { read += messageId to text }
        override fun toolStep(block: com.ouor.localchat.core.model.ToolCallBlock, result: com.ouor.localchat.core.model.ToolResultBlock?) =
            com.ouor.localchat.chat.ToolStep(R.string.ui_read_aloud, "bank.example", failureRes = R.string.chat_step_fail_sensitive)
    }

    private val composer = object : ComposerActions {
        override fun onText(value: String) {}
        override fun attach(uris: List<Uri>) {}
        override fun retry(id: String) {}
        override fun remove(id: String) {}
        override fun selectModel(ref: ModelRef) {}
        override fun toggleReasoning() {}
        override fun setEffort(effort: String?) {}
        override fun toggleSearch() {}
        override fun toggleBrowser() {}
        override fun startVoice() {}
        override fun stopVoice() {}
        override fun cancelVoice() {}
        override fun thumbnail(a: com.ouor.localchat.core.model.Attachment): Any? = null
        override fun sendWithoutImages() {}
        override fun send() {}
    }

    private val browser = object : BrowserActions {
        override fun setPanel(p: PanelState) {}
        override fun select(id: Int) {}
        override fun showTabs(v: Boolean) {}
        override fun close(id: Int) {}
        override fun open(url: String?) { opened += url }
        override fun newTab(url: String?) {}
        override fun navigate(input: String) {}
        override fun back() {}
        override fun forward() {}
        override fun reload() {}
        override fun takeOver(id: Int) {}
        override fun dismissTouch() {}
        override fun giveBack(id: Int) {}
        override fun handToAi(id: Int) {}
        override fun takeTab(id: Int) {}
        override fun stopRun() {}
        override fun answerDialog(p: BrowserPrompt.Dialog, accept: Boolean, text: String?) {}
        override fun answerFiles(p: BrowserPrompt.FileChooser, uris: List<Uri>?) {}
        override fun answerPermission(p: BrowserPrompt.Permission, grant: Boolean) {}
        override fun answerDownload(p: BrowserPrompt.Download, allow: Boolean) {}
        override fun webView(id: Int): WebView? = null
        override fun park(id: Int) {}
    }

    private val nav = ChatNav(null, {}, {}, {}, {}, {}, { _, _ -> })

    private fun show(state: ChatUiState, cs: ComposerUiState = ComposerUiState()) = rule.setContent {
        LocalchatTheme(false) { ChatScreen(state, cs, BrowserUiState(), chat, composer, browser, nav) }
    }

    private fun turn(question: String, body: MessageBody.Assistant, live: Boolean = false): TurnUi {
        val u = MessageNode("u", "s", null, null, Role.USER, 1, MessageBody.User(question))
        val a = MessageNode("a", "s", "u", null, Role.ASSISTANT, 2, body)
        return TurnUi(UserUi(u, question, emptyList(), 1, listOf("u")), AssistantUi(a, body, 1, listOf("a"), live, !live, false))
    }

    private fun reply(text: String, status: ReplyStatus = ReplyStatus.DONE, error: ChatError? = null) =
        MessageBody.Assistant(listOf(TextBlock(text, 0)), status, error = error, model = null, options = GenOptions())

    @Test fun emptyChatShowsWelcomeAndProviderBanner() {
        show(ChatUiState(loading = false))
        rule.onNodeWithText(ctx.getString(R.string.ui_welcome_title)).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.ui_no_provider)).assertIsDisplayed()
    }

    @Test fun streamingReplyShowsPartialText() {
        show(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Partial answer", ReplyStatus.STREAMING), live = true)), runPhase = RunPhase.Streaming))
        rule.onNodeWithText("hi").assertIsDisplayed()
        rule.onNodeWithText("Partial answer", substring = true).assertIsDisplayed()
    }

    @Test fun approvalSheetShowsTargetAndAllows() {
        val info = ApprovalInfo("click", 3, "https://shop.example/cart", "Place order")
        show(ChatUiState(loading = false, turns = listOf(turn("buy it", reply(""), live = true)), runPhase = RunPhase.Paused(Pause.Approval("p1", info))))
        rule.onNodeWithText("Place order").assertIsDisplayed()
        rule.onNodeWithText("https://shop.example/cart", substring = true).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.ui_allow)).performClick()
        rule.runOnIdle { assertEquals(listOf<Resolution>(Resolution.ApprovalAnswer("p1", true)), resolved) }
    }

    @Test fun errorReplyShowsErrorCard() {
        show(ChatUiState(loading = false, turns = listOf(turn("hi", reply("", ReplyStatus.ERROR, ChatError(ChatErrorKind.AUTH, 401))))))
        rule.onNodeWithText(ctx.getString(R.string.ui_err_auth_title)).assertIsDisplayed()
    }

    @Test fun readAloudButtonReadsTheReply() { // BUG-01
        show(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello there")))))
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_read_aloud)).performClick()
        rule.runOnIdle { assertEquals(listOf("a" to "Hello there"), read) }
    }

    @Test fun readingReplyShowsStop() {
        show(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello there"))), readingId = "a"))
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_stop_reading)).assertIsDisplayed()
    }

    @Test fun streamingFollowsTheBottom() { // BUG-03
        var state by mutableStateOf(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Start", ReplyStatus.STREAMING), live = true)), runPhase = RunPhase.Streaming))
        rule.setContent { LocalchatTheme(false) { ChatScreen(state, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav) } }
        rule.waitForIdle()
        val long = (1..60).joinToString("\n\n") { "Paragraph $it." } + "\n\nTAIL-MARKER"
        state = state.copy(turns = listOf(turn("hi", reply(long, ReplyStatus.STREAMING), live = true)))
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("TAIL-MARKER", substring = true).assertIsDisplayed() }.isSuccess }
    }

    @Test fun failedStepShowsItsReason() { // BUG-10
        val call = com.ouor.localchat.core.model.ToolCallBlock("c1", "page_text", "{}", 0, com.ouor.localchat.core.model.ToolCallState.FAILED, 1)
        val body = MessageBody.Assistant(listOf(call, TextBlock("Done", 1)), ReplyStatus.DONE, model = null, options = GenOptions())
        show(ChatUiState(loading = false, turns = listOf(turn("read it", body))))
        rule.onNodeWithText(ctx.getString(R.string.ui_steps_failed, 1, 1)).performClick()
        rule.onNodeWithText(ctx.getString(R.string.chat_step_fail_sensitive), substring = true).assertIsDisplayed()
    }

    @Test fun stepLimitStopEndsTheRun() { // BUG-18
        show(ChatUiState(loading = false, turns = listOf(turn("go", reply(""), live = true)), runPhase = RunPhase.Paused(Pause.StepLimit(5))))
        rule.onNodeWithText(ctx.getString(R.string.ui_stop)).performClick()
        rule.runOnIdle { assertEquals(listOf<Resolution>(Resolution.StepLimitAnswer(false)), resolved) }
    }

    @Test fun modelNameLongPressShowsUsage() { // BUG-14
        val body = MessageBody.Assistant(listOf(TextBlock("Hi", 0)), ReplyStatus.DONE, model = ModelRef("e", "m", "Model-X"), options = GenOptions(),
            usage = com.ouor.localchat.core.model.Usage(1200, 34, 1000))
        show(ChatUiState(loading = false, turns = listOf(turn("hi", body))))
        rule.onNodeWithText("Model-X").performTouchInput { longClick() }
        rule.onNodeWithText(ctx.getString(R.string.ui_usage_title)).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.ui_usage_body, "1,200", "34"), substring = true).assertIsDisplayed()
    }

    @Test fun citationBadgeOpensPreviewFirst() { // BUG-15
        val src = com.ouor.localchat.core.model.Source(1, "https://kotlinlang.org", "Kotlin releases", "Kotlin 2.x is the latest.")
        val result = com.ouor.localchat.core.model.ToolResultBlock("c1", 0, "", false, sources = listOf(src))
        val body = MessageBody.Assistant(listOf(result, TextBlock("Kotlin [1]: latest.", 1)), ReplyStatus.DONE, model = null, options = GenOptions())
        show(ChatUiState(loading = false, turns = listOf(turn("kotlin?", body))))
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_citation_desc, 1)).performClick()
        rule.onNodeWithText(ctx.getString(R.string.ui_open_page)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(emptyList<String?>(), opened) }
        rule.onNodeWithText(ctx.getString(R.string.ui_open_page)).performClick()
        rule.runOnIdle { assertEquals(listOf<String?>("https://kotlinlang.org"), opened) }
    }

    @Test fun stoppedBrowserRunSaysActionsStay() { // BUG-16
        val call = com.ouor.localchat.core.model.ToolCallBlock("c1", "click", "{}", 0, com.ouor.localchat.core.model.ToolCallState.CANCELLED, 1)
        val body = MessageBody.Assistant(listOf(call), ReplyStatus.STOPPED, model = null, options = GenOptions())
        show(ChatUiState(loading = false, turns = listOf(turn("do it", body))))
        rule.onNodeWithText(ctx.getString(R.string.ui_stopped_no_undo), substring = true).assertIsDisplayed()
    }

    @Test fun pdfArtifactShowsFileCardWithActions() { // BUG-17
        val pdf = com.ouor.localchat.core.model.Attachment("f1", "Example_Domain.pdf", "application/pdf", 120_000, com.ouor.localchat.core.model.AttachmentKind.ARTIFACT,
            com.ouor.localchat.core.model.PrepState.Ready, hasOriginal = true, createdAt = 1)
        val call = com.ouor.localchat.core.model.ToolCallBlock("c1", "print_to_pdf", "{}", 0, com.ouor.localchat.core.model.ToolCallState.DONE, 1)
        val result = com.ouor.localchat.core.model.ToolResultBlock("c1", 0, "saved", false, artifactIds = listOf("f1"))
        val body = MessageBody.Assistant(listOf(call, result, TextBlock("Saved.", 1)), ReplyStatus.DONE, model = null, options = GenOptions())
        show(ChatUiState(loading = false, turns = listOf(turn("pdf", body)), attachments = mapOf("f1" to pdf)))
        rule.onNodeWithText("Example_Domain.pdf").assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.ui_share)).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.ui_save)).assertIsDisplayed()
    }

    // ---- FB-1 ----

    private val aiTab = com.ouor.localchat.browser.TabInfo(1, "Wiki", "https://wiki.example/hr", com.ouor.localchat.browser.Owner.Ai("s"),
        com.ouor.localchat.browser.Control.AI, loading = false)

    @Test fun emptyChatOpensOptionsWithoutHeroChipOrCaveat() { // FB-1a, FB-1b, lead decision 1
        show(ChatUiState(loading = false))
        rule.onNodeWithText(ctx.getString(R.string.ui_search), useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText(ctx.getString(R.string.ui_choose_model), substring = true, useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_hide)).assertIsDisplayed()
        listOf("참고용", "for reference").forEach { rule.onAllNodesWithText(it, substring = true, useUnmergedTree = true).assertCountEquals(0) }
    }

    @Test fun chatInProgressCollapsesOptionsUntilExpanded() { // FB-1b
        show(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))))
        rule.onNodeWithText(ctx.getString(R.string.ui_search), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_show)).performClick()
        rule.onNodeWithText(ctx.getString(R.string.ui_search), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_hide)).performClick()
        rule.onNodeWithText(ctx.getString(R.string.ui_search), useUnmergedTree = true).assertDoesNotExist()
    }

    private fun longChat(bs: BrowserUiState) {
        val long = (1..40).joinToString("\n\n") { "Paragraph $it." } + "\n\nTAIL-MARKER"
        val state = ChatUiState(loading = false, turns = listOf(turn("hi", reply(long, ReplyStatus.STREAMING), live = true)), runPhase = RunPhase.Streaming)
        rule.setContent { LocalchatTheme(false) { ChatScreen(state, ComposerUiState(), bs, chat, composer, browser, nav) } }
        rule.waitForIdle()
    }

    private fun tailBottom() = rule.onNodeWithText("TAIL-MARKER", substring = true).fetchSemanticsNode().boundsInRoot.bottom

    @Test fun statusStripNeverCoversTheLastLine() { // FB-1c
        longChat(BrowserUiState(PanelState.Peek, listOf(aiTab), sessionId = "s", running = true))
        val strip = rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e4_strip_cd, "Wiki"))
        strip.assertIsDisplayed()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("TAIL-MARKER", substring = true).assertIsDisplayed() }.isSuccess }
        assertTrue(tailBottom() <= strip.fetchSemanticsNode().boundsInRoot.top)
    }

    /** FB-1 screenshots into the app's external files (pulled into docs/dev/ui/shots by hand); also checks the strip at 200% + dark. */
    @Test fun fb1Screenshots() {
        val long = (1..12).joinToString("\n\n") { "문단 $it. 강남구 아파트 전세 시세는 최근 3개월 기준 보합 흐름입니다." } + "\n\nTAIL-MARKER 마지막 줄"
        val chatState = ChatUiState(loading = false, turns = listOf(turn("전세 시세 알려줘", reply(long))))
        val strip = BrowserUiState(PanelState.Peek, listOf(aiTab), sessionId = "s", running = true)
        var scene by mutableStateOf(0)
        rule.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            val big = scene == 3
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, if (big) 2f else 1f)) {
                LocalchatTheme(big) {
                    // The app's root surface paints the window; the test draws it so dark mode reads right.
                    androidx.compose.runtime.key(scene) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(com.ouor.localchat.ui.theme.LcTheme.colors.surface)) {
                        when (scene) {
                            0 -> ChatScreen(ChatUiState(loading = false), ComposerUiState(), BrowserUiState(), chat, composer, browser, nav)
                            1 -> ChatScreen(chatState, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav)
                            else -> ChatScreen(chatState, ComposerUiState(), strip, chat, composer, browser, nav)
                        }
                    } }
                }
            }
        }
        listOf("empty-expanded", "progress-collapsed", "strip-last-line", "strip-200-dark").forEachIndexed { i, name ->
            scene = i
            rule.waitForIdle()
            if (i >= 1) rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("TAIL-MARKER", substring = true).assertIsDisplayed() }.isSuccess }
            if (i >= 2) assertTrue(tailBottom() <= rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e4_strip_cd, "Wiki")).fetchSemanticsNode().boundsInRoot.top)
            val dir = ctx.getExternalFilesDir("shots") ?: return@forEachIndexed
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(dir, "fb1-e1-$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun halfPanelLetsTheLastLineScrollAboveIt() { // FB-1c with the FB-5 Half panel
        longChat(BrowserUiState(PanelState.Half, listOf(aiTab), sessionId = "s", running = true))
        val handle = rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e4_handle_cd))
        rule.waitUntil(5_000) { runCatching { tailBottom() <= handle.fetchSemanticsNode().boundsInRoot.top }.getOrDefault(false) }
    }
}
