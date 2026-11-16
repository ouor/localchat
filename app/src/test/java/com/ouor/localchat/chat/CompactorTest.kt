package com.ouor.localchat.chat

import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.llm.LlmTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactorTest {
    private val store = FakeChatStore()
    private val llm = FakeLlm()
    private val attachments = FakeAttachments()
    private val compactor = Compactor(store, llm, attachments, TickClock())

    private fun model(window: Int? = 64_000) = ResolvedModel(TEST_MODEL, LlmTarget("http://fake/v1", null, 0, true), caps(window))

    private fun path(turns: Int, firstAttachment: String? = null, answer: String = "ok") = (1..turns).flatMap { i ->
        val att = if (i == 1 && firstAttachment != null) listOf(firstAttachment) else emptyList()
        listOf(
            MessageNode("u$i", "s", if (i == 1) null else "a${i - 1}", null, Role.USER, i * 10L, MessageBody.User("q$i", att)),
            MessageNode("a$i", "s", "u$i", null, Role.ASSISTANT, i * 10L + 1,
                MessageBody.Assistant(listOf(TextBlock(answer, 0)), ReplyStatus.DONE, model = TEST_MODEL, options = GenOptions())),
        )
    }

    private fun sentTranscript() = llm.completeRequests.last().messages.last().content!!.jsonPrimitive.content

    @Test
    fun `an attached document's text goes into the summary request`() = runTest {
        // User review: after compaction the attached paper's content was gone from the context.
        attachments.saved["p"] = AttachmentPayload(
            Attachment("p", "paper.pdf", "application/pdf", 10, AttachmentKind.DOCUMENT, PrepState.Ready, true, createdAt = 0),
            text = "PELICAN-7 is the answer",
        )
        assertNotNull(compactor.compact("s", path(3, firstAttachment = "p"), model()))
        val sent = sentTranscript()
        assertTrue(sent, sent.contains("[attached file: paper.pdf]\nPELICAN-7 is the answer"))
    }

    @Test
    fun `fewer than three user turns has nothing to summarize`() = runTest {
        assertNull(compactor.compact("s", path(2), model()))
        assertTrue(llm.completeRequests.isEmpty())
    }

    @Test
    fun `a transcript larger than the window is cut in the middle to fit`() = runTest {
        val window = 1_000
        assertNotNull(compactor.compact("s", path(3, answer = "x".repeat(10_000)), model(window)))
        val sent = sentTranscript()
        assertTrue(sent.length < window * 3.5)
        assertTrue(sent.startsWith("User: q1"))
        assertTrue(sent.contains("[middle omitted"))
    }
}
