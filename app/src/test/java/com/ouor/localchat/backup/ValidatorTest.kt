package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ValidatorTest {
    private val good = BackupFixtures.session("s", "f")

    @Test
    fun `fixture is valid`() = Validator.checkTree(good)

    @Test
    fun `broken trees`() {
        val (u, a1, a2) = good.messages
        broken(good.copy(messages = listOf(u.copy(parentId = a2.id), a1, a2))) // cycle u -> a2 -> u
        broken(good.copy(messages = listOf(u.copy(selectedChildId = "x"), a1, a2)))
        broken(good.copy(messages = listOf(u, a1, a2.copy(parentId = null)))) // root reply
        broken(good.copy(messages = listOf(u, a1, a2.copy(role = Role.USER, body = MessageBody.User("x"))))) // USER under USER
        broken(good.copy(messages = listOf(u, a1, a1)))
        broken(good.copy(session = good.session.copy(rootSelectedId = a1.id)))
        broken(good.copy(summaries = listOf(good.summaries[0].copy(coversUpToId = "gone"))))
    }

    @Test
    fun `attachment ids include tool artifacts and the draft`() {
        assertEquals(setOf("f"), Validator.attachmentIds(good))
    }

    private fun broken(f: SessionFile) {
        try {
            Validator.checkTree(f)
            fail("expected BROKEN_TREE")
        } catch (e: BackupException) {
            assertEquals(BackupError.BROKEN_TREE, e.error)
        }
    }
}
