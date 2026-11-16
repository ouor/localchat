package com.ouor.localchat.ui.chat

import android.net.Uri
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
