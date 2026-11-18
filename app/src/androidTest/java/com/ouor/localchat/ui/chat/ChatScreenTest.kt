package com.ouor.localchat.ui.chat

import android.net.Uri
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    /** Snapshot state, so a test can move a new chat (null) to its id like the first send does. */
    private var sid by mutableStateOf<String?>("s")

    private val chat = object : ChatActions {
        override val sessionId: String? get() = sid
        override fun stop() {}
        override fun selectVersion(messageId: String) {}
        override fun regenerate(assistantId: String, model: ModelRef, options: GenOptions) {}
        override fun continueReply(assistantId: String) {}
        override fun editAndResend(userId: String, text: String, attachmentIds: List<String>, model: ModelRef, options: GenOptions) {}
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
        override fun keyboard(up: Boolean) {}
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
        override fun savePdf(id: Int, uri: Uri, done: (Boolean) -> Unit) {}
        override fun webView(id: Int): WebView? = null
        override fun show(id: Int) {}
        override fun park(id: Int) {}
    }

    private val nav = ChatNav(null, {}, {}, {}, {}, {}, { _, _ -> })

    private fun show(state: ChatUiState, cs: ComposerUiState = ComposerUiState()) = rule.setContent {
        LocalchatTheme(false) { ChatScreen(state, cs, BrowserUiState(), chat, composer, browser, nav) }
    }

    private fun turn(question: String, body: MessageBody.Assistant, live: Boolean = false, id: String = ""): TurnUi {
        val u = MessageNode("u$id", "s", null, null, Role.USER, 1, MessageBody.User(question))
        val a = MessageNode("a$id", "s", "u$id", null, Role.ASSISTANT, 2, body)
        return TurnUi(UserUi(u, question, emptyList(), 1, listOf("u$id")), AssistantUi(a, body, 1, listOf("a$id"), live, !live, false))
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

    @Test fun approvalClosesTheOpenModelSheet() { // FB4 U-4: one sheet at a time
        var state by mutableStateOf(ChatUiState(loading = false, turns = listOf(turn("buy it", reply(""), live = true)), runPhase = RunPhase.Streaming))
        rule.setContent { LocalchatTheme(false) { ChatScreen(state, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav) } }
        val chooseModel = ctx.getString(R.string.ui_choose_model)
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_show)).performClick() // a chat in progress folds them
        rule.onNodeWithText(chooseModel, substring = true, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(chooseModel, substring = true, useUnmergedTree = true).assertCountEquals(2) // chip + sheet title
        state = state.copy(runPhase = RunPhase.Paused(Pause.Approval("p1", ApprovalInfo("click", 3, "https://shop.example/cart", "Place order"))))
        rule.waitForIdle()
        rule.onNodeWithText("Place order").assertIsDisplayed()
        rule.onAllNodesWithText(chooseModel, substring = true, useUnmergedTree = true).assertCountEquals(1) // the sheet closed; the chip stays
        com.ouor.localchat.ui.Shots.save("fb4-u4-approval-only")
        // Answered: the model sheet does not come back on its own.
        state = state.copy(runPhase = RunPhase.Streaming)
        rule.waitForIdle()
        rule.onNodeWithText("Place order").assertDoesNotExist()
        rule.onAllNodesWithText(chooseModel, substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun errorReplyShowsErrorCard() {
        show(ChatUiState(loading = false, turns = listOf(turn("hi", reply("", ReplyStatus.ERROR, ChatError(ChatErrorKind.AUTH, 401))))))
        rule.onNodeWithText(ctx.getString(R.string.ui_err_auth_title)).assertIsDisplayed()
    }

    @Test fun noKeyErrorSaysTheKeyIsMissing() { // FB2 QA O3
        show(ChatUiState(loading = false, turns = listOf(turn("hi", reply("", ReplyStatus.ERROR, ChatError(ChatErrorKind.NO_KEY, 401))))))
        rule.onNodeWithText(ctx.getString(R.string.fb2_q_err_no_key_title)).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.ui_err_auth_body), substring = true).assertDoesNotExist()
    }

    /** FB4 U-6 caption: "<kind>: n" per failure kind, joined by " · ". */
    private fun fails(vararg kinds: Pair<Int, Int>) = kinds.joinToString(" · ") { (label, n) -> ctx.getString(R.string.attach_fail_count, ctx.getString(label), n) }

    @Test fun unsupportedAttachmentNamesTheFileAndOffersNoRetry() { // FB2 QA Minor-2
        fun file(id: String, name: String, reason: String) = com.ouor.localchat.core.model.Attachment(
            id, name, "application/octet-stream", 10, com.ouor.localchat.core.model.AttachmentKind.DOCUMENT,
            com.ouor.localchat.core.model.PrepState.Failed(reason), hasOriginal = true, createdAt = 0,
        )
        show(ChatUiState(loading = false), ComposerUiState(attachments = listOf(
            file("z", "archive.zip", com.ouor.localchat.attach.PrepFailure.UNSUPPORTED_TYPE),
            file("r", "notes.pdf", com.ouor.localchat.attach.PrepFailure.READ_FAILED),
        )))
        // FB4 U-6: two kinds of failure are both counted (the file is named when it fails alone, see u6_*).
        rule.onNodeWithText(fails(R.string.fb2_q_tile_unsupported to 1, R.string.attach_fail_retry to 1)).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(R.string.fb2_q_tile_unsupported), useUnmergedTree = true).assertExists()
        // Only the readable-later file keeps its retry.
        rule.onAllNodesWithText(ctx.getString(R.string.ui_retry), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun oldOfficeAndTooLargeFilesSayWhyAndOfferNoRetry() { // FB3 D
        fun file(id: String, name: String, reason: String) = com.ouor.localchat.core.model.Attachment(
            id, name, "application/octet-stream", 10, com.ouor.localchat.core.model.AttachmentKind.DOCUMENT,
            com.ouor.localchat.core.model.PrepState.Failed(reason), hasOriginal = true, createdAt = 0,
        )
        show(ChatUiState(loading = false), ComposerUiState(attachments = listOf(
            file("b", "huge.pdf", com.ouor.localchat.attach.PrepFailure.TOO_LARGE),
            file("d", "old.doc", com.ouor.localchat.attach.PrepFailure.LEGACY_OFFICE),
        )))
        rule.onNodeWithText(fails(R.string.fb3_u_tile_too_large to 1, R.string.fb2_q_tile_unsupported to 1)).assertIsDisplayed()
        rule.onAllNodesWithText(ctx.getString(R.string.ui_retry), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun u6_failureKindsAreCountedWithoutMovingTheInput() { // FB4 U-6
        fun file(id: String, name: String, reason: String) = com.ouor.localchat.core.model.Attachment(
            id, name, "application/octet-stream", 10, com.ouor.localchat.core.model.AttachmentKind.DOCUMENT,
            com.ouor.localchat.core.model.PrepState.Failed(reason), hasOriginal = true, createdAt = 0,
        )
        val big = file("b", "huge.pdf", com.ouor.localchat.attach.PrepFailure.TOO_LARGE)
        var cs by mutableStateOf(ComposerUiState(attachments = listOf(big)))
        rule.setContent { LocalchatTheme(false) { ChatScreen(ChatUiState(loading = false), cs, BrowserUiState(), chat, composer, browser, nav) } }
        rule.onNodeWithText(ctx.getString(R.string.fb3_d_file_too_large, "huge.pdf", "64MB")).assertIsDisplayed() // alone: named
        val input = rule.onNode(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        cs = cs.copy(attachments = listOf(
            big, file("z", "a.zip", com.ouor.localchat.attach.PrepFailure.UNSUPPORTED_TYPE),
            file("d", "old.doc", com.ouor.localchat.attach.PrepFailure.LEGACY_OFFICE), file("r", "n.pdf", com.ouor.localchat.attach.PrepFailure.READ_FAILED),
        ))
        rule.waitForIdle()
        rule.onNodeWithText(fails(R.string.fb3_u_tile_too_large to 1, R.string.fb2_q_tile_unsupported to 2, R.string.attach_fail_retry to 1)).assertIsDisplayed()
        assertEquals(input, rule.onNode(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNode().boundsInRoot)
        com.ouor.localchat.ui.Shots.save("fb4-u6-failure-kinds")
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
        val call = com.ouor.localchat.core.model.ToolCallBlock("c1", "read", "{}", 0, com.ouor.localchat.core.model.ToolCallState.FAILED, 1)
        val body = MessageBody.Assistant(listOf(call, TextBlock("Done", 1)), ReplyStatus.DONE, model = null, options = GenOptions())
        show(ChatUiState(loading = false, turns = listOf(turn("read it", body))))
        rule.onNodeWithText(ctx.getString(R.string.ui_steps_failed, 1, 1)).performClick()
        rule.onNodeWithText(ctx.getString(R.string.chat_step_fail_sensitive), substring = true).assertIsDisplayed()
    }

    @Test fun stepLimitWrapUpEndsTheToolSteps() { // BUG-18, FB4: the stop button is "Wrap up"
        show(ChatUiState(loading = false, turns = listOf(turn("go", reply(""), live = true)), runPhase = RunPhase.Paused(Pause.StepLimit(5))))
        rule.onNodeWithText(ctx.getString(R.string.run_wrap_up)).performClick()
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
        val search = ctx.getString(R.string.ui_search)
        listOf("fb1-e1-empty-expanded", "fb1-e1-progress-collapsed", "fb1-e1-strip-last-line", "fb1-e1-strip-200-dark", "fb1-r2a-progress-expanded").forEachIndexed { i, name ->
            scene = if (i == 4) 1 else i
            rule.waitForIdle()
            if (i == 4) rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_show)).performClick()
            rule.waitForIdle()
            if (i >= 1) rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("TAIL-MARKER", substring = true).assertIsDisplayed() }.isSuccess }
            when (i) {
                0, 4 -> rule.onNodeWithText(search, useUnmergedTree = true).assertIsDisplayed()
                1 -> rule.onNodeWithText(search, useUnmergedTree = true).assertDoesNotExist()
                else -> assertTrue(tailBottom() <= rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e4_strip_cd, "Wiki")).fetchSemanticsNode().boundsInRoot.top)
            }
            saveBitmap(rule.onRoot().captureToImage().asAndroidBitmap(), name)
        }
    }

    // ---- R2a ----

    private fun bounds(cd: Int) = rule.onNodeWithContentDescription(ctx.getString(cd)).fetchSemanticsNode().boundsInRoot

    @Test fun optionsOpenAboveTheInputWithoutMovingIt() { // R2a user rule: controls never move because of an interaction
        show(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))))
        val input = bounds(R.string.ui_composer_placeholder)
        val send = bounds(R.string.ui_send)
        val opts = bounds(R.string.fb_e1_options_show)
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_show)).performClick()
        rule.waitForIdle()
        assertEquals(input, bounds(R.string.ui_composer_placeholder))
        assertEquals(send, bounds(R.string.ui_send))
        assertEquals(opts, bounds(R.string.fb_e1_options_hide))
        // Top to bottom: model row, toggle row, input.
        val model = rule.onNodeWithText(ctx.getString(R.string.ui_choose_model), substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chip = rule.onNodeWithText(ctx.getString(R.string.ui_search), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("model row above chips", model.bottom <= chip.top)
        assertTrue("chips above input", chip.bottom <= input.top)
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_hide)).assertIsDisplayed()
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, ctx.getString(R.string.fb_e3_group_expanded)))
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_hide)).performClick()
        rule.waitForIdle()
        assertEquals(input, bounds(R.string.ui_composer_placeholder))
        assertEquals(send, bounds(R.string.ui_send))
        assertEquals(opts, bounds(R.string.fb_e1_options_show))
    }

    @Test fun optionsChoiceSurvivesTheFirstSend() { // R2a-3: null → id keeps the user's pick
        sid = null
        var state by mutableStateOf(ChatUiState(loading = false))
        rule.setContent { LocalchatTheme(false) { ChatScreen(state, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav) } }
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_hide)).performClick()
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_show)).performClick() // user picked "open"
        rule.runOnIdle { sid = "new"; state = ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))) }
        rule.onNodeWithText(ctx.getString(R.string.ui_search), useUnmergedTree = true).assertIsDisplayed() // default would be collapsed
        rule.runOnIdle { sid = "other" } // another chat starts from its default
        rule.onNodeWithText(ctx.getString(R.string.ui_search), useUnmergedTree = true).assertDoesNotExist()
    }

    // ---- P-STABLE (audit-stable-controls.md H1-H3, H6) ----

    @Test fun topBarIconsKeepTheirSlotsAfterTheFirstSend() { // H1
        sid = null
        var state by mutableStateOf(ChatUiState(loading = false))
        rule.setContent { LocalchatTheme(false) { ChatScreen(state, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav) } }
        val web = bounds(R.string.ui_browser)
        val newChat = bounds(R.string.ui_new_chat)
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_more)).assertIsNotEnabled()
        rule.runOnIdle { sid = "new"; state = ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))) }
        assertEquals(web, bounds(R.string.ui_browser))
        assertEquals(newChat, bounds(R.string.ui_new_chat))
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_more)).assertIsEnabled()
    }

    @Test fun statusCaptionDoesNotMoveTheInput() { // H2
        var cs by mutableStateOf(ComposerUiState())
        rule.setContent { LocalchatTheme(false) { ChatScreen(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))), cs, BrowserUiState(), chat, composer, browser, nav) } }
        val input = bounds(R.string.ui_composer_placeholder)
        val send = bounds(R.string.ui_send)
        rule.runOnIdle { cs = ComposerUiState(waitingForFiles = true) }
        val caption = rule.onNodeWithText(ctx.getString(R.string.ui_files_waiting)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("caption above the input", caption.bottom <= input.top)
        assertEquals(input, bounds(R.string.ui_composer_placeholder))
        assertEquals(send, bounds(R.string.ui_send))
    }

    @Test fun expandingThinkingAtTheBottomKeepsItsHeader() { // H3: no auto-follow for a user's expand
        val long = (1..12).joinToString("\n\n") { "문단 $it. 강남구 아파트 전세 시세는 최근 3개월 기준 보합 흐름입니다." }
        val thought = (1..30).joinToString("\n") { "생각 $it" }
        val body = MessageBody.Assistant(
            listOf(com.ouor.localchat.core.model.ThinkingBlock(thought, 0, 0L, 1000L), TextBlock("Answer TAIL-MARKER", 0)),
            ReplyStatus.DONE, model = null, options = GenOptions(),
        )
        show(ChatUiState(loading = false, turns = listOf(turn("q1", reply(long)), turn("q2", body, id = "2"))))
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("TAIL-MARKER", substring = true).assertIsDisplayed() }.isSuccess }
        val header = rule.onNodeWithText(ctx.getString(R.string.ui_thought_for, ctx.getString(R.string.ui_seconds, 1)))
        val before = header.fetchSemanticsNode().boundsInRoot.top
        header.performClick()
        rule.waitForIdle()
        rule.onNodeWithText("생각 1", substring = true).assertExists()
        assertEquals(before, header.fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun firstSendKeepsTheInputFocused() { // H6: the first send swaps the nav entry; the new entry takes focus back
        sid = null
        val stack = androidx.compose.runtime.mutableStateListOf(com.ouor.localchat.ui.nav.ChatKey())
        lateinit var view: android.view.View
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            LocalchatTheme(false) {
                androidx.navigation3.ui.NavDisplay(
                    backStack = stack,
                    entryDecorators = listOf(androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator()),
                    entryProvider = { k -> androidx.navigation3.runtime.NavEntry(k) {
                        val st = if (k.sessionId == null) ChatUiState(loading = false) else ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello"))))
                        ChatScreen(st, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav)
                    } },
                )
            }
        }
        val input = rule.onNodeWithContentDescription(ctx.getString(R.string.ui_composer_placeholder))
        input.performClick()
        input.assertIsFocused()
        fun ime() = androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        val imeBefore = runCatching { rule.waitUntil(3_000) { ime() } }.isSuccess
        // What ChatRoute does on ComposerEffect.Sent from a new chat.
        rule.runOnIdle { sid = "new"; NewChatOptions.carry("new"); stack[0] = com.ouor.localchat.ui.nav.ChatKey("new") }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Hello").assertIsDisplayed() }.isSuccess }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_composer_placeholder)).assertIsFocused()
        android.util.Log.i("ChatScreenTest", "H6 ime before=$imeBefore after=${ime()}")
        if (imeBefore) assertTrue("keyboard stays up", ime())
    }

    @Test fun panelCoveringTheInputTakesItsFocus() { // FB4 QA F1: the hidden input's keyboard must not shrink the page
        var bs by mutableStateOf(BrowserUiState(PanelState.Peek, listOf(aiTab), sessionId = "s", running = true))
        rule.setContent { LocalchatTheme(false) { ChatScreen(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))), ComposerUiState(), bs, chat, composer, browser, nav) } }
        val input = rule.onNodeWithContentDescription(ctx.getString(R.string.ui_composer_placeholder))
        input.performClick()
        input.assertIsFocused()
        bs = bs.copy(panel = PanelState.Full)
        rule.waitForIdle()
        input.assertIsNotFocused()
    }

    @Test fun tapsOnThePanelNeverReachTheChatBehindIt() { // FB4 QA O5: a tap between the banner's text and buttons focused the hidden input
        val handed = aiTab.copy(control = com.ouor.localchat.browser.Control.USER)
        val bs = BrowserUiState(PanelState.Half, listOf(handed), sessionId = "s", running = true, handoff = Pause.Handoff(1, "Please sign in"))
        rule.setContent { LocalchatTheme(false) { ChatScreen(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))), ComposerUiState(), bs, chat, composer, browser, nav) } }
        val input = rule.onNodeWithContentDescription(ctx.getString(R.string.ui_composer_placeholder))
        val give = rule.onNodeWithText(ctx.getString(R.string.ui_give_back_to_ai)).fetchSemanticsNode().boundsInRoot
        val handle = rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e4_handle_cd)).fetchSemanticsNode().boundsInRoot
        // The banner's gap left of its button (over the input), beside the handle, and below the banner's body text.
        val body = rule.onNodeWithText(ctx.getString(R.string.ui_handoff_body)).fetchSemanticsNode().boundsInRoot
        listOf(Offset(give.left - 30f, give.center.y), Offset(handle.right + 60f, handle.center.y), Offset(body.center.x, body.bottom + 4f)).forEach { p ->
            rule.onRoot().performTouchInput { click(p) }
            rule.waitForIdle()
            input.assertIsNotFocused()
        }
    }

    @Test fun halfPanelLetsTheLastLineScrollAboveIt() { // FB-1c with the FB-5 Half panel
        longChat(BrowserUiState(PanelState.Half, listOf(aiTab), sessionId = "s", running = true))
        val handle = rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e4_handle_cd))
        rule.waitUntil(5_000) { runCatching { tailBottom() <= handle.fetchSemanticsNode().boundsInRoot.top }.getOrDefault(false) }
    }

    /** Empty chat (options open) at 360dp and 200% font; [touchTargets] false makes chip node bounds equal the drawn chip. */
    private fun bigFontEmptyChat(touchTargets: Boolean = true) = rule.setContent {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 2f),
            androidx.compose.material3.LocalMinimumInteractiveComponentSize provides if (touchTargets) 48.dp else androidx.compose.ui.unit.Dp.Unspecified,
        ) {
            LocalchatTheme(false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(360.dp).background(com.ouor.localchat.ui.theme.LcTheme.colors.surface)) {
                    ChatScreen(ChatUiState(loading = false), ComposerUiState(), BrowserUiState(), chat, composer, browser, nav)
                }
            }
        }
    }

    @Test fun bannerKeepsItsTextWideAt200Percent() { // FB-2 follow-up: the action drops below instead of squeezing the text
        bigFontEmptyChat()
        val text = rule.onNodeWithText(ctx.getString(R.string.ui_no_provider), useUnmergedTree = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        text.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertTrue("banner lines ${layouts[0].lineCount}", layouts[0].lineCount <= 3)
        rule.onNodeWithText(ctx.getString(R.string.ui_add_provider)).assertIsDisplayed()
        shot(rule.onRoot(), "fb1-e1b-expanded-200")
        val banner = text.fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().captureToImage().asAndroidBitmap()
        val add = rule.onNodeWithText(ctx.getString(R.string.ui_add_provider)).fetchSemanticsNode().boundsInRoot
        val top = (banner.top - 40).toInt().coerceAtLeast(0)
        saveBitmap(android.graphics.Bitmap.createBitmap(root, 0, top, root.width, (add.bottom + 40).toInt().coerceAtMost(root.height) - top), "fb1-e1b-banner-200")
    }

    @Test fun chipsGrowWithTheirTextAt200Percent() { // FB-2 follow-up: ChipSurface uses a min height
        bigFontEmptyChat(touchTargets = false)
        listOf(ctx.getString(R.string.ui_choose_model), ctx.getString(R.string.ui_search)).forEach { label ->
            // The text node is squeezed to the chip's constraint, so compare the text's full laid-out height.
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val textH = layouts[0].multiParagraph.height
            val chipH = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height
            assertTrue("$label text $textH > chip $chipH", textH <= chipH + 0.5f)
        }
    }

    // ---- FB3 track A: P-STABLE M3, M4, L1, L3, L4, L11 ----

    @Test fun tapWhileStreamingStopsFollowing() { // M3
        val long = (1..40).joinToString("\n\n") { "Paragraph $it." }
        var state by mutableStateOf(ChatUiState(loading = false, turns = listOf(turn("hi", reply("$long\n\nTAIL-MARKER", ReplyStatus.STREAMING), live = true)), runPhase = RunPhase.Streaming))
        rule.setContent { LocalchatTheme(false) { ChatScreen(state, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav) } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("TAIL-MARKER", substring = true).assertIsDisplayed() }.isSuccess }
        val target = rule.onNodeWithText("TAIL-MARKER", substring = true)
        val before = target.fetchSemanticsNode().boundsInRoot
        target.performTouchInput { down(center); up() }
        rule.runOnIdle { state = state.copy(turns = listOf(turn("hi", reply("$long\n\nTAIL-MARKER\n\n" + (1..10).joinToString("\n\n") { "More $it." }, ReplyStatus.STREAMING), live = true))) }
        rule.waitForIdle()
        assertEquals(before, target.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun recordingKeepsTheControlSlots() { // M4
        var cs by mutableStateOf(ComposerUiState())
        rule.setContent { LocalchatTheme(false) { ChatScreen(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))), cs, BrowserUiState(), chat, composer, browser, nav) } }
        val mic = bounds(R.string.ui_voice_input)
        val send = bounds(R.string.ui_send)
        val attach = bounds(R.string.ui_attach)
        rule.runOnIdle { cs = ComposerUiState(voice = com.ouor.localchat.speech.VoiceState.Listening(7_000, 3f)) }
        assertEquals(mic, bounds(R.string.ui_cancel))
        assertEquals(send, bounds(R.string.ui_done))
        assertEquals(attach, bounds(R.string.ui_attach))
    }

    @Test fun bannersFloatOverTheWelcome() { // L1, and no "no provider" before the models load
        var cs by mutableStateOf(ComposerUiState(modelsLoaded = false))
        rule.setContent { LocalchatTheme(false) { ChatScreen(ChatUiState(loading = false), cs, BrowserUiState(), chat, composer, browser, nav) } }
        rule.onNodeWithText(ctx.getString(R.string.ui_no_provider)).assertDoesNotExist()
        val title = rule.onNodeWithText(ctx.getString(R.string.ui_welcome_title)).fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { cs = ComposerUiState() }
        rule.onNodeWithText(ctx.getString(R.string.ui_no_provider)).assertIsDisplayed()
        assertEquals(title, rule.onNodeWithText(ctx.getString(R.string.ui_welcome_title)).fetchSemanticsNode().boundsInRoot)
    }

    private fun bannersBottom() = listOf(R.string.ui_save_failed, R.string.ui_retry, R.string.ui_no_provider, R.string.ui_add_provider)
        .flatMap { rule.onAllNodesWithText(ctx.getString(it)).fetchSemanticsNodes() }.maxOf { it.boundsInRoot.bottom }

    @Test fun bannersNeverCoverTheFirstMessage() { // FB3 fix ui M1
        show(ChatUiState(loading = false, saveFailing = true, turns = listOf(turn("FIRST-Q", reply("Hello")))))
        val first = rule.onNodeWithText("FIRST-Q").fetchSemanticsNode().boundsInRoot
        assertTrue("first message $first under the banners ${bannersBottom()}", first.top >= bannersBottom())
    }

    @Test fun bannersNeverCoverTheWelcomeOnAShortScreen() = welcomeOnAShortScreen(640.dp, fits = true) // FB3 fix ui M1

    @Test fun welcomeTooTallForTheSpaceIsLeftOut() = welcomeOnAShortScreen(420.dp, fits = false) // FB3 review 7

    /**
     * [height] below the status bar: the top bar pads itself by that inset, which differs per device (devphone01's is
     * taller than devphone03's), so a fixed 420dp left room for the welcome on one phone and not the other.
     */
    private fun welcomeOnAShortScreen(height: androidx.compose.ui.unit.Dp, fits: Boolean) {
        rule.setContent {
            LocalchatTheme(false) {
                val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(height + status)) {
                    ChatScreen(ChatUiState(loading = false, saveFailing = true), ComposerUiState(), BrowserUiState(), chat, composer, browser, nav)
                }
            }
        }
        val titles = rule.onAllNodesWithText(ctx.getString(R.string.ui_welcome_title)).fetchSemanticsNodes().filter { n -> generateSequence(n.layoutInfo) { it.parentInfo }.all { it.isPlaced } }.map { it.boundsInRoot }.filter { it.height > 0f }
        assertEquals(titles.toString(), if (fits) 1 else 0, titles.size)
        // Shown: below the banners and above the composer (its model chip), never under either.
        titles.forEach { title ->
            assertTrue("welcome $title under the banners ${bannersBottom()}", title.top >= bannersBottom())
            val composerTop = rule.onNodeWithText(ctx.getString(R.string.ui_choose_model), substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            assertTrue("welcome $title over the composer at $composerTop", title.bottom <= composerTop)
        }
    }

    @Test fun actionBarShowsTheSameButtonsRunningOrNot() { // FB3 fix ui m1: a reply with no text keeps read-aloud (disabled)
        val empty = MessageBody.Assistant(emptyList(), ReplyStatus.STREAMING, model = null, options = GenOptions())
        var state by mutableStateOf(ChatUiState(loading = false, turns = listOf(turn("hi", empty, live = true)), runPhase = RunPhase.Streaming))
        rule.setContent { LocalchatTheme(false) { ChatScreen(state, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav) } }
        val speaker = rule.onNodeWithContentDescription(ctx.getString(R.string.ui_read_aloud))
        val regen = rule.onNodeWithContentDescription(ctx.getString(R.string.ui_regenerate))
        val before = speaker.fetchSemanticsNode().boundsInRoot to regen.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { state = ChatUiState(loading = false, turns = listOf(turn("hi", empty.copy(status = ReplyStatus.DONE)))) }
        speaker.assertIsNotEnabled()
        regen.assertIsEnabled()
        assertEquals(before, speaker.fetchSemanticsNode().boundsInRoot to regen.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun snackbarStaysClearOfTheScrollButton() { // L3
        val snackbar = androidx.compose.material3.SnackbarHostState()
        val long = (1..40).joinToString("\n\n") { "Paragraph $it." }
        rule.setContent { LocalchatTheme(false) { ChatScreen(ChatUiState(loading = false, turns = listOf(turn("hi", reply(long)))), ComposerUiState(), BrowserUiState(), chat, composer, browser, nav, snackbar) } }
        rule.waitForIdle()
        rule.onNodeWithText("Paragraph 39.", substring = true).performTouchInput { swipeDown() }
        val fab = rule.onNodeWithContentDescription(ctx.getString(R.string.ui_scroll_bottom))
        rule.waitUntil(5_000) { runCatching { fab.assertIsDisplayed() }.isSuccess }
        val before = fab.fetchSemanticsNode().boundsInRoot
        val scope = kotlinx.coroutines.MainScope()
        rule.runOnIdle { scope.launch { snackbar.showSnackbar("SNACK") } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("SNACK").assertIsDisplayed() }.isSuccess }
        val snack = rule.onNodeWithText("SNACK").fetchSemanticsNode().boundsInRoot
        assertEquals(before, fab.fetchSemanticsNode().boundsInRoot)
        assertTrue("snackbar $snack over the button $before", snack.bottom <= before.top)
        scope.cancel()
    }

    @Test fun actionBarStaysWhileTheReplyRuns() { // L4
        var state by mutableStateOf(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello", ReplyStatus.STREAMING), live = true)), runPhase = RunPhase.Streaming))
        rule.setContent { LocalchatTheme(false) { ChatScreen(state, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav) } }
        val regen = rule.onNodeWithContentDescription(ctx.getString(R.string.ui_regenerate))
        regen.assertIsNotEnabled()
        val before = regen.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { state = ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))) }
        regen.assertIsEnabled()
        assertEquals(before, regen.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun newChatOptionsStayOpenAfterTheFirstSend() { // L11
        sid = null
        var state by mutableStateOf(ChatUiState(loading = false))
        rule.setContent { LocalchatTheme(false) { ChatScreen(state, ComposerUiState(), BrowserUiState(), chat, composer, browser, nav) } }
        val chip = rule.onNodeWithText(ctx.getString(R.string.ui_choose_model), substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { sid = "new"; state = ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello")))) }
        rule.onNodeWithText(ctx.getString(R.string.ui_search), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(chip, rule.onNodeWithText(ctx.getString(R.string.ui_choose_model), substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
    }

    private fun shot(node: androidx.compose.ui.test.SemanticsNodeInteraction, name: String) = saveBitmap(node.captureToImage().asAndroidBitmap(), name)

    @Test fun u3_editorStartsFocusedAndSavesOnlyAChange() { // FB3 QA m3
        show(ChatUiState(loading = false, turns = listOf(turn("hi there", reply("Hello")))))
        rule.onNodeWithText("hi there").performTouchInput { longClick() }
        val field = rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("hi there"))
        field.assertIsFocused()
        // Review 5: the cursor starts at the end.
        assertEquals(androidx.compose.ui.text.TextRange(8), field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange])
        val save = rule.onNodeWithText(ctx.getString(R.string.ui_save_send))
        save.assertIsNotEnabled()
        Thread.sleep(1000) // the keyboard the edit opens moves the editor (keyboard exception): measure once it is up
        rule.waitForIdle()
        val slot = save.fetchSemanticsNode().boundsInRoot
        field.performTextInput("!")
        save.assertIsEnabled()
        assertEquals(slot, save.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun u5_tooLargeTileSaysTooLarge() { // FB3 QA m5
        val big = com.ouor.localchat.core.model.Attachment(
            "b", "huge.pdf", "application/pdf", 10, com.ouor.localchat.core.model.AttachmentKind.DOCUMENT,
            com.ouor.localchat.core.model.PrepState.Failed(com.ouor.localchat.attach.PrepFailure.TOO_LARGE), hasOriginal = true, createdAt = 0,
        )
        show(ChatUiState(loading = false), ComposerUiState(attachments = listOf(big)))
        rule.onNodeWithText(ctx.getString(R.string.fb3_u_tile_too_large), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.fb2_q_tile_unsupported), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun u7_deniedMicSaysWhy() { // FB3 QA m7
        var started = 0
        val voice = object : ComposerActions by composer { override fun startVoice() { started++ } }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                    dispatchResult(requestCode, false) // the user said no
                }
            }
        }
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                LocalchatTheme(false) { ChatScreen(ChatUiState(loading = false), ComposerUiState(), BrowserUiState(), chat, voice, browser, nav) }
            }
        }
        rule.onNodeWithContentDescription(ctx.getString(R.string.ui_voice_input)).performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(ctx.getString(R.string.fb3_u_mic_denied)).assertIsDisplayed() }.isSuccess }
        rule.onNodeWithText(ctx.getString(R.string.fb3_u_open_settings)).assertIsDisplayed()
        assertEquals(0, started)
    }

    @Test fun u8_chipsAreSwitchesAndTheRunningBarIsDisabled() { // FB3 QA a11y
        show(ChatUiState(loading = false, turns = listOf(turn("hi", reply("Hello", ReplyStatus.STREAMING), live = true)), runPhase = RunPhase.Streaming))
        listOf(R.string.ui_copy, R.string.ui_read_aloud, R.string.ui_regenerate).forEach {
            rule.onNodeWithContentDescription(ctx.getString(it)).assertIsNotEnabled()
        }
        rule.onNodeWithContentDescription(ctx.getString(R.string.fb_e1_options_show)).performClick()
        listOf(R.string.ui_reasoning, R.string.ui_search, R.string.ui_browser).forEach {
            rule.onNode(androidx.compose.ui.test.hasText(ctx.getString(it)) and androidx.compose.ui.test.isToggleable()).assertIsOff()
        }
    }

    private fun saveBitmap(bmp: android.graphics.Bitmap, name: String) {
        val dir = ctx.getExternalFilesDir("shots") ?: return
        java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
