package com.ouor.localchat.chat

import com.ouor.localchat.core.Selection
import com.ouor.localchat.core.model.GenOptions
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Summary
import com.ouor.localchat.core.model.TitleSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rules every [ChatStore] keeps (AR F-08). The JVM runs them on FakeChatStore, androidTest on
 * SessionRepository over in-memory Room, so the fake the agent tests rely on cannot drift from Room.
 */
abstract class ChatStoreContract {

    abstract fun store(): ChatStore

    private val store by lazy { store() }

    private fun session(id: String) = Session(id, id, TitleSource.NONE, 0, 0, null, null)

    private fun user(id: String, parent: String? = null, at: Long = 0) =
        MessageNode(id, "s", parent, null, Role.USER, at, MessageBody.User("q $id", emptyList()))

    private fun reply(id: String, parent: String, status: ReplyStatus, at: Long = 0) =
        MessageNode(id, "s", parent, null, Role.ASSISTANT, at, MessageBody.Assistant(emptyList(), status, model = TEST_MODEL, options = GenOptions()))

    private fun start() = runBlocking {
        store.createSession(session("s"))
        store.insertNodes("s", listOf(user("u1"), reply("a1", "u1", ReplyStatus.DONE, 1)), Selection(null, "u1"))
    }

    @Test
    fun insertNodesStoresNodesAndAppliesRootAndChildSelection() = runBlocking {
        start()
        assertEquals("u1", store.session("s")!!.rootSelectedId)
        store.insertNodes("s", listOf(user("u2", at = 2)), Selection(null, "u2"))
        assertEquals("u2", store.session("s")!!.rootSelectedId)
        store.insertNodes("s", listOf(user("u3", "a1", 3)), Selection("a1", "u3"))
        assertEquals("u3", store.node("a1")!!.selectedChildId)
        assertEquals(setOf("u1", "a1", "u2", "u3"), store.nodes("s").map { it.id }.toSet())
    }

    @Test
    fun saveNodeUpdatesBodyButKeepsTheStoredSelection() = runBlocking {
        start()
        store.insertNodes("s", listOf(user("u3", "a1", 3)), Selection("a1", "u3"))
        // The run's copy of a1 still has the selection it started with.
        store.saveNode(reply("a1", "u1", ReplyStatus.STOPPED, 1))
        val saved = store.node("a1")!!
        assertEquals(ReplyStatus.STOPPED, (saved.body as MessageBody.Assistant).status)
        assertEquals("u3", saved.selectedChildId)
    }

    @Test
    fun updateSessionTransformsAndIgnoresMissing() = runBlocking {
        start()
        store.updateSession("s") { it.copy(title = "renamed") }
        store.updateSession("gone") { it.copy(title = "x") }
        assertEquals("renamed", store.session("s")!!.title)
        assertNull(store.session("gone"))
    }

    @Test
    fun unfinishedAssistantsArePendingAndStreamingOnly() = runBlocking {
        start()
        store.insertNodes(
            "s",
            listOf(reply("p", "u1", ReplyStatus.PENDING, 2), reply("t", "u1", ReplyStatus.STREAMING, 3), reply("e", "u1", ReplyStatus.ERROR, 4)),
            Selection("u1", "t"),
        )
        assertEquals(setOf("p", "t"), store.unfinishedAssistants().map { it.id }.toSet())
    }

    @Test
    fun summariesAreKeptPerSession() = runBlocking {
        start()
        store.createSession(session("other"))
        store.addSummary(Summary("sum", "s", "a1", "text", 5))
        assertEquals(listOf("sum"), store.summaries("s").map { it.id })
        assertEquals(emptyList<Summary>(), store.summaries("other"))
    }
}
