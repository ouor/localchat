package com.ouor.localchat.storage.db

import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.backup.RoomBackupStore
import com.ouor.localchat.chat.SessionRepository
import com.ouor.localchat.core.Clock
import com.ouor.localchat.backup.format.ProvidersFile
import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.Draft
import com.ouor.localchat.core.model.Endpoint
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.MessageNode
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.Role
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.core.model.Summary
import com.ouor.localchat.core.model.TitleSource
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.SecretEntries
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.storage.db.entities.DraftEntity
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.KeyGenerator

/** Real SQLite (foreign keys, CASCADE, transactions) for the DAOs and RoomBackupStore. In-memory: no app data touched. */
@RunWith(AndroidJUnit4::class)
class RoomDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
    ).build()
    private val chat = db.chatDao()
    private val attachments = db.attachmentDao()
    private val providers = db.providerDao()

    private class MemoryStore<T>(initial: T) : DataStore<T> {
        val state = MutableStateFlow(initial)
        override val data = state
        override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(state.value).also { state.value = it }
    }

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val backup = RoomBackupStore(db, db.backupDao(), chat, SecretStore(MemoryStore(SecretEntries())) { aes })

    @After
    fun close() = db.close()

    private fun session(id: String, title: String = id, updatedAt: Long = 0) =
        Session(id, title, TitleSource.NONE, 0, updatedAt, null, null)

    private fun user(id: String, sessionId: String, vararg att: String, at: Long = 0) =
        MessageNode(id, sessionId, null, null, Role.USER, at, MessageBody.User("hi", att.toList()))

    private fun attachment(id: String, createdAt: Long = 0) =
        Attachment(id, "$id.txt", "text/plain", 1, AttachmentKind.DOCUMENT, PrepState.Ready, true, createdAt = createdAt)

    private suspend fun saveSession(s: Session, vararg messages: MessageNode) {
        chat.upsertSession(s.toEntity())
        chat.upsertMessages(messages.map { it.toEntity() }, messages.associate { it.id to it.attachmentIds() })
    }

    @Test
    fun sessionSearchEscapesWildcardsAndSortsByUpdate() = runBlocking {
        saveSession(session("a", "100% done", updatedAt = 1))
        saveSession(session("b", "100 items", updatedAt = 2))
        saveSession(session("c", "another 100% day", updatedAt = 3))
        assertEquals(listOf("c", "b", "a"), chat.observeSessions("100").first().map { it.id })
        assertEquals(listOf("c", "a"), chat.observeSessions(likeEscape("100%")).first().map { it.id })
    }

    @Test
    fun messagesAndUnfinished() = runBlocking {
        val done = MessageNode("m2", "s", "m1", null, Role.ASSISTANT, 2, MessageBody.Assistant(emptyList(), ReplyStatus.DONE, model = null, options = com.ouor.localchat.core.model.GenOptions()))
        val live = done.copy(id = "m3", createdAt = 3, body = MessageBody.Assistant(emptyList(), ReplyStatus.STREAMING, model = null, options = com.ouor.localchat.core.model.GenOptions()))
        saveSession(session("s"), user("m1", "s", at = 1), done, live)
        assertEquals(listOf("m1", "m2", "m3"), chat.messages("s").map { it.id })
        assertEquals(listOf("m3"), chat.unfinished().map { it.id })
        chat.setSelectedChild("m1", "m2")
        assertEquals("m2", chat.message("m1")!!.selectedChildId)
    }

    @Test
    fun refsFollowMessagesDraftsAndDeletes() = runBlocking {
        listOf("x", "y", "z").forEach { attachments.upsert(attachment(it).toEntity()) }
        saveSession(session("s"), user("m1", "s", "x", "x"))
        chat.upsertDraft(Draft("s", "draft", listOf("y"), 0).toEntity(), listOf("y"))
        // z is unreferenced; the cutoff spares rows created at or after it.
        assertEquals(listOf("z"), attachments.orphans(cutoff = 1))
        assertEquals(emptyList<String>(), attachments.orphans(cutoff = 0))

        // Replacing a message's refs drops the old one.
        chat.upsertMessages(listOf(user("m1", "s", "z").toEntity()), mapOf("m1" to listOf("z")))
        assertEquals(listOf("x"), attachments.orphans(1))

        chat.deleteSession("s")
        assertNull(chat.session("s"))
        assertNull(chat.draft("s"))
        assertEquals(emptyList<Any>(), chat.messages("s"))
        assertEquals(setOf("x", "y", "z"), attachments.orphans(1).toSet())

        attachments.delete(attachments.orphans(1))
        assertEquals(emptyList<String>(), attachments.allIds())
    }

    @Test
    fun deleteEverythingClearsChatButNotProviders() = runBlocking {
        providers.upsertEndpoint(endpoint("e"))
        attachments.upsert(attachment("x").toEntity())
        saveSession(session("s"), user("m1", "s", "x"))
        chat.upsertSummary(Summary("sum", "s", "m1", "t", 0).toEntity())
        chat.upsertDraft(DraftEntity(Draft.NEW_CHAT, "d", "[\"x\"]", 0), listOf("x"))
        chat.deleteEverything()
        assertEquals(emptyList<Any>(), chat.observeSessions("").first())
        assertEquals(emptyList<Any>(), chat.summaries("s"))
        assertNull(chat.draft(Draft.NEW_CHAT))
        assertEquals(listOf("x"), attachments.orphans(1))
        assertNotNull(providers.endpoint("e"))
    }

    @Test
    fun observeAttachmentsKeepsIdOrderAndSkipsMissingRows() = runBlocking {
        val repo = SessionRepository(db, chat, attachments, AttachmentFiles(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir), Clock.System)
        attachments.upsert(attachment("b").toEntity())
        attachments.upsert(attachment("a").toEntity())
        assertEquals(listOf("b", "a"), repo.observeAttachments(listOf("b", "gone", "a", "b")).first().map { it.id })
        assertEquals(emptyList<Any>(), repo.observeAttachments(emptyList()).first())
        // Rows that arrive later (ingest finishing) show up in the same Flow.
        val later = repo.observeAttachments(listOf("c"))
        attachments.upsert(attachment("c").toEntity())
        assertEquals(listOf("c"), later.first { it.isNotEmpty() }.map { it.id })
    }

    private fun endpoint(id: String, name: String = id) = EndpointEntity(id, name, "https://h/v1", null, true, 5, false, 0, 0)

    private fun model(endpointId: String, modelId: String) =
        ModelEntity(endpointId, modelId, null, true, false, false, "{\"displayName\":\"mine\"}", 7)

    @Test
    fun endpointUpsertKeepsModelsAndDeleteCascades() = runBlocking {
        providers.upsertEndpoint(endpoint("e"))
        providers.upsertModels(listOf(model("e", "m1"), model("e", "m2")))
        // @Upsert updates in place; a REPLACE would delete the row and CASCADE the models away.
        providers.upsertEndpoint(endpoint("e", name = "renamed"))
        assertEquals("renamed", providers.endpoint("e")!!.name)
        assertEquals(listOf("m1", "m2"), providers.observeModels("e").first().map { it.modelId })
        // Model upsert keeps user overrides it is given and does not touch siblings.
        providers.upsertModels(listOf(model("e", "m1").copy(hidden = true)))
        assertTrue(providers.model("e", "m1")!!.hidden)
        assertEquals("{\"displayName\":\"mine\"}", providers.model("e", "m2")!!.capsUserJson)

        providers.deleteEndpoint("e")
        assertEquals(emptyList<Any>(), providers.observeAllModels().first())
    }

    private fun file(id: String, vararg att: String) = SessionFile(
        session = session(id),
        messages = listOf(user("$id-m", id, *att)),
        summaries = listOf(Summary("$id-sum", id, "$id-m", "t", 0)),
        draft = Draft(id, "d", att.toList(), 0),
    )

    private val providersFile = ProvidersFile(
        endpoints = listOf(Endpoint("e2", "E2", "https://h/v1", null, enabled = true, allowHttp = false, hasKey = false)),
        models = emptyList(),
    )

    @Test
    fun backupReplaceAllRestoresEverythingInOrder() = runBlocking {
        providers.upsertEndpoint(endpoint("old"))
        providers.upsertModels(listOf(model("old", "m")))
        saveSession(session("gone"), user("gone-m", "gone"))

        backup.replaceAll(providersFile, listOf(attachment("x")), sessions = { insert -> insert(file("s1", "x")); insert(file("s2")) })

        assertEquals(setOf("s1", "s2"), backup.sessionIds().toSet())
        assertNull(chat.session("gone"))
        assertEquals(listOf("e2"), backup.providers().endpoints.map { it.id })
        assertEquals(emptyList<Any>(), backup.providers().models)
        val restored = backup.sessionFile("s1")!!
        assertEquals(file("s1", "x"), restored)
        assertEquals(listOf("x"), backup.attachmentsFor(listOf("s1")).map { it.id })
        assertEquals(emptyList<String>(), attachments.orphans(Long.MAX_VALUE))
    }

    @Test
    fun backupReplaceAllRollsBackOnError() = runBlocking {
        providers.upsertEndpoint(endpoint("old"))
        saveSession(session("keep"), user("keep-m", "keep"))
        try {
            backup.replaceAll(providersFile, emptyList(), sessions = { insert -> insert(file("s1")); error("zip broke") })
            fail()
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf("keep"), backup.sessionIds())
        assertNotNull(providers.endpoint("old"))
        assertNull(providers.endpoint("e2"))
    }

    @Test
    fun backupAddSessionsOverwritesSameIdOnly() = runBlocking {
        attachments.upsert(attachment("old").toEntity())
        saveSession(session("s1", "before"), user("s1-old", "s1", "old"))
        saveSession(session("other"), user("other-m", "other"))

        backup.addSessions(listOf(attachment("x")), sessions = { insert -> insert(file("s1", "x").let { it.copy(session = it.session.copy(title = "after")) }) })

        assertEquals("after", chat.session("s1")!!.title)
        assertEquals(listOf("s1-m"), chat.messages("s1").map { it.id })
        assertNotNull(chat.session("other"))
        assertEquals(setOf("s1", "other"), backup.existingSessionIds(listOf("s1", "other", "nope")))
        assertEquals(listOf("old"), attachments.orphans(Long.MAX_VALUE))
        assertEquals(listOf("x"), backup.attachmentsFor(listOf("s1")).map { it.id })
    }
}
