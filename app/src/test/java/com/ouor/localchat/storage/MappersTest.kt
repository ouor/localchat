package com.ouor.localchat.storage

import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ModelRef
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.storage.db.attachmentIds
import com.ouor.localchat.storage.db.likeEscape
import com.ouor.localchat.storage.db.toEntity
import com.ouor.localchat.storage.db.toModel
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test
    fun `session and message round-trip`() {
        val s = Session("s", "t", TitleSource.AUTO, 1, 2, "m1", ModelRef("e", "m", "M"), listOf("https://a"))
        assertEquals(s, s.toEntity().toModel())
        val m = MessageNode(
            "m1", "s", null, null, Role.ASSISTANT, 3,
            MessageBody.Assistant(emptyList(), ReplyStatus.STREAMING, model = null, options = GenOptions()),
        )
        assertEquals("STREAMING", m.toEntity().status)
        assertEquals(m, m.toEntity().toModel())
    }

    @Test
    fun `attachment ids include tool artifacts`() {
        val m = MessageNode(
            "m1", "s", null, null, Role.ASSISTANT, 3,
            MessageBody.Assistant(
                listOf(ToolResultBlock("c", 0, "ok", false, artifactIds = listOf("pdf"), imageForModelId = "shot")),
                ReplyStatus.DONE, model = null, options = GenOptions(),
            ),
        )
        assertEquals(listOf("pdf", "shot"), m.attachmentIds())
    }

    @Test
    fun `like wildcards are escaped`() {
        assertEquals("50\\% a\\_b \\\\", likeEscape("50% a_b \\"))
    }
}
