package com.ouor.localchat.ui.chat

import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.ui.sessions.SessionGroup
import com.ouor.localchat.ui.sessions.SessionListViewModel
import com.ouor.localchat.ui.sessions.SessionRowUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ChatMappingTest {

    private fun user(id: String, parent: String?, t: Long) = MessageNode(id, "s", parent, null, Role.USER, t, MessageBody.User(id))
    private fun reply(id: String, parent: String, t: Long) =
        MessageNode(id, "s", parent, null, Role.ASSISTANT, t, MessageBody.Assistant(emptyList(), ReplyStatus.DONE, model = null, options = GenOptions()))

    @Test fun `turns pair user and reply with version counts`() {
        val u1 = user("u1", null, 1); val a1 = reply("a1", "u1", 2); val a1b = reply("a1b", "u1", 3)
        val u2 = user("u2", "a1b", 4)
        val turns = ChatViewModel.toTurns(listOf(u1, a1b, u2), listOf(u1, a1, a1b, u2), liveId = null)
        assertEquals(2, turns.size)
        assertEquals(2, turns[0].reply!!.version)
        assertEquals(listOf("a1", "a1b"), turns[0].reply!!.versions)
        assertNull(turns[1].reply)
    }

    @Test fun `sessions group by period and older by month`() {
        val today = LocalDate.of(2026, 11, 16)
        fun row(day: LocalDate) = SessionRowUi(Session(day.toString(), "t", TitleSource.USER, 0, day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), null, null), false)
        val groups = SessionListViewModel.group(
            listOf(row(today), row(today.minusDays(1)), row(today.minusDays(3)), row(today.minusDays(20)), row(LocalDate.of(2026, 9, 2))),
            today, ZoneOffset.UTC,
        ).map { it.first }
        assertEquals(
            listOf(
                SessionGroup(SessionGroup.Period.TODAY), SessionGroup(SessionGroup.Period.YESTERDAY), SessionGroup(SessionGroup.Period.WEEK),
                SessionGroup(SessionGroup.Period.MONTH), SessionGroup(SessionGroup.Period.OLDER, "2026-09"),
            ),
            groups,
        )
    }

    @Test fun `token counts are compact`() {
        assertEquals("851.2k", compact(851_234))
        assertEquals("1M", compact(1_000_000))
        assertEquals("999", compact(999))
    }

    @Test fun `text from different steps becomes separate paragraphs`() {
        val t = { text: String, step: Int -> com.ouor.localchat.core.model.TextBlock(text, step) }
        assertEquals("Let me check first.\n\nHere are", replyText(listOf(t("Let me check first.", 0), t("Here are", 1))))
        assertEquals("Hello world", replyText(listOf(t("Hello ", 0), t("world", 0))))
        assertEquals("a\n\nb", replyText(listOf(t("a\n\n", 0), t("b", 1))))
        assertEquals("b", replyText(listOf(t("", 0), t("b", 1))))
    }

    @Test fun `only failures a retry can fix keep the retry - old Office and over 64 MB do not`() {
        fun failed(reason: String) = com.ouor.localchat.core.model.Attachment(
            "a", "f", "application/octet-stream", 1, com.ouor.localchat.core.model.AttachmentKind.DOCUMENT,
            com.ouor.localchat.core.model.PrepState.Failed(reason), hasOriginal = true, createdAt = 0,
        )
        val p = com.ouor.localchat.attach.PrepFailure
        listOf(p.UNSUPPORTED_TYPE, p.LEGACY_OFFICE, p.TOO_LARGE).forEach { org.junit.Assert.assertTrue(it, isUnsupported(failed(it))) }
        org.junit.Assert.assertFalse(isUnsupported(failed(p.READ_FAILED)))
    }
}
