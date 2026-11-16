package com.ouor.localchat.backup

import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.backup.format.ProvidersFile
import com.ouor.localchat.backup.format.SessionFile
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ThemeMode
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.SecretEntriesSerializer
import com.ouor.localchat.storage.SecretStore
import com.ouor.localchat.storage.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

/** Backup round trips on Room (RoomBackupStore), SecretStore and real files (Phase 2 lead checklist). */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "backup-roundtrip").apply { deleteRecursively(); mkdirs() }
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val secrets = SecretStore(DataStoreFactory.create(SecretEntriesSerializer, scope = scope) { File(root, "secrets.json") }) { aes }
    private val store = RoomBackupStore(db, db.backupDao(), db.chatDao(), secrets)
    private val filesDir = File(root, "attachments")
    private val files = AttachmentFiles(filesDir)
    private val jobs = BackupJobs(store, files, Clock { 1_700_000_000_000 }, "test")
    private val settings = AppSettings(theme = ThemeMode.DARK, maxSteps = 40)
    private val key = ApiKey("sk-test-not-a-real-key")
    private val s1 = BackupFixtures.session("s1", "f1")
    private val s2 = BackupFixtures.session("s2", "f2")

    @After
    fun close() {
        db.close()
        scope.cancel()
        root.deleteRecursively()
    }

    private fun seed() = runBlocking {
        val atts = listOf(BackupFixtures.attachment("f1"), BackupFixtures.attachment("f2"))
        atts.forEach { files.original(it.id).writeBytes(BackupFixtures.bytes(it.id)) }
        store.replaceAll(BackupFixtures.providers, atts) { insert -> insert(s1); insert(s2) }
        store.replaceSecrets(mapOf("ep1" to key))
    }

    private data class Snapshot(
        val sessions: List<SessionFile>,
        val attachments: List<com.ouor.localchat.core.model.Attachment>,
        val providers: ProvidersFile,
        val keys: Map<String, ApiKey>,
        val originals: Map<String, String>,
    )

    private suspend fun snapshot(): Snapshot {
        val ids = store.sessionIds().sorted()
        val atts = store.attachmentsFor(ids).sortedBy { it.id }
        return Snapshot(
            ids.map { store.sessionFile(it)!! }, atts, store.providers(), store.secrets(),
            atts.associate { it.id to files.original(it.id).readText() },
        )
    }

    private suspend fun export(scope: BackupScope = BackupScope.Full, password: String? = null): ByteArray =
        ByteArrayOutputStream().also { jobs.export(scope, it, password?.toCharArray(), settings) {} }.toByteArray()

    private suspend fun restore(zip: ByteArray, choice: RestoreChoice, password: String? = null, restoreZip: ByteArray = zip): AppSettings? {
        val inspection = jobs.inspect({ ByteArrayInputStream(zip) }, password?.toCharArray())
        return jobs.restore({ ByteArrayInputStream(restoreZip) }, inspection, choice, " (copy)")
    }

    /** Delete everything: sessions, endpoints, attachment rows and files, keys. */
    private suspend fun wipe() {
        store.replaceAll(ProvidersFile(emptyList(), emptyList()), emptyList()) {}
        db.attachmentDao().delete(db.attachmentDao().allIds())
        store.replaceSecrets(emptyMap())
        filesDir.deleteRecursively()
    }

    @Test
    fun fullBackupDeleteAllRestoreIsIdentical() = runBlocking {
        seed()
        val before = snapshot()
        // Room mapping keeps every field of the fixtures.
        assertEquals(listOf(s1, s2), before.sessions)
        assertEquals(BackupFixtures.providers, before.providers)
        val zip = export()
        wipe()
        assertTrue(store.sessionIds().isEmpty())

        assertEquals(settings, restore(zip, RestoreChoice.REPLACE_ALL))
        // No password: keys are not in the backup, and hasKey follows.
        assertEquals(before.copy(keys = emptyMap(), providers = before.providers.copy(endpoints = before.providers.endpoints.map { it.copy(hasKey = false) })), snapshot())
    }

    @Test
    fun encryptedBackupRestoresKeysAndRejectsWrongPassword() = runBlocking {
        seed()
        val before = snapshot()
        val zip = export(password = "password1")
        wipe()
        try {
            jobs.inspect({ ByteArrayInputStream(zip) }, "wrong-password".toCharArray())
            fail("wrong password accepted")
        } catch (e: BackupException) {
            assertEquals(BackupError.WRONG_PASSWORD, e.error)
        }
        assertTrue("failed inspect touched no table", store.sessionIds().isEmpty())

        restore(zip, RestoreChoice.REPLACE_ALL, password = "password1")
        assertEquals(before, snapshot())
        assertEquals(key, secrets.get("ep1"))
    }

    @Test
    fun sessionBackupOverwritesOrCopies() = runBlocking {
        seed()
        val zip = export(BackupScope.Session("s1"))
        store.addSessions(emptyList()) { it(BackupFixtures.session("s1", "f1", title = "changed")) }

        assertEquals(listOf("s1"), jobs.inspect({ ByteArrayInputStream(zip) }, null).conflicts)
        restore(zip, RestoreChoice.OVERWRITE)
        assertEquals(s1, store.sessionFile("s1"))
        assertEquals(2, store.sessionIds().size)

        restore(zip, RestoreChoice.COPY)
        val ids = store.sessionIds()
        assertEquals(3, ids.size)
        val copy = store.sessionFile((ids - setOf("s1", "s2")).single())!!
        assertEquals(s1.session.title + " (copy)", copy.session.title)
        assertEquals(s1, store.sessionFile("s1"))
        assertEquals(s1.messages.size, copy.messages.size)
        assertTrue(copy.messages.none { m -> s1.messages.any { it.id == m.id } })
        assertEquals(listOf("f1"), store.attachmentsFor(listOf(copy.session.id)).map { it.id })
    }

    @Test
    fun failureDuringRestoreKeepsExistingData() = runBlocking {
        seed()
        val zip = export(password = "password1")
        // Different existing data: one session, other title, other key.
        store.replaceAll(BackupFixtures.providers, listOf(BackupFixtures.attachment("f1"))) { it(BackupFixtures.session("s1", "f1", title = "mine")) }
        store.replaceSecrets(mapOf("ep1" to ApiKey("sk-test-other")))
        val before = snapshot()

        // f2 is the last entry: every delete and insert has run when the second pass fails.
        val tampered = rewrite(zip) { (path, data) -> if (path.startsWith("attachments/f2/")) "tampered".encodeToByteArray() else data }
        try {
            restore(zip, RestoreChoice.REPLACE_ALL, password = "password1", restoreZip = tampered)
            fail("tampered restore succeeded")
        } catch (e: BackupException) {
            assertEquals(BackupError.BROKEN_ATTACHMENT, e.error)
        }
        assertEquals(before, snapshot())
    }

    @Test
    fun restoreSurvivesGcOfInspectCopies() = runBlocking {
        seed()
        val zip = export()
        wipe()
        val inspection = jobs.inspect({ ByteArrayInputStream(zip) }, null)
        assertTrue(files.original("f1").isFile)
        // B's GC between inspect and restore: the pass-1 copies have no row yet.
        files.deleteWithoutRow(db.attachmentDao().allIds().toSet(), Long.MAX_VALUE)
        assertTrue(!files.original("f1").exists())

        jobs.restore({ ByteArrayInputStream(zip) }, inspection, RestoreChoice.REPLACE_ALL, "")
        assertEquals(BackupFixtures.bytes("f1").decodeToString(), files.original("f1").readText())
        assertEquals(BackupFixtures.bytes("f2").decodeToString(), files.original("f2").readText())
    }

    @Test
    fun deepSeekImportAddsSessions() = runBlocking {
        seed()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
        val session = """
            {"id":"abc","title":"Trip","pinned":false,"current_message_id":2,
             "inserted_at":"2026-11-11T02:33:23Z","updated_at":"2026-11-11T02:40:00Z",
             "messages":[
              {"id":1,"parent_id":null,"role":"USER","status":"FINISHED","thinking_enabled":false,"search_enabled":false,
               "accumulated_token_usage":0,"inserted_at":"2026-11-11T02:33:23Z",
               "fragments":[{"type":"REQUEST","content":"plan a trip"},
                 {"type":"FILE","files":[{"id":"img1","file_name":"map.png","file_size":11,"token_usage":null,"inserted_at":"2026-11-11T02:33:23Z","export_path":"chat_sessions/abc_files/img1_map.png","is_image":true,"width":4,"height":3}]}]},
              {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","thinking_enabled":false,"search_enabled":false,
               "accumulated_token_usage":10,"inserted_at":"2026-11-11T02:33:30Z","fragments":[{"type":"RESPONSE","content":"Go to Paris"}]}
             ]}
        """.trimIndent()
        val zip = zip(
            "manifest.json" to """{"export_format_version":3}""",
            "user.json" to "{}",
            "chat_sessions/abc.json" to session,
            "chat_session_index.json" to """[{"id":"abc","title":"Trip","pinned":false,"message_count":2,"inserted_at":"2026-11-11T02:33:23Z","updated_at":"2026-11-11T02:40:00Z"}]""",
            "chat_sessions/abc_files/img1_map.png" to png,
        )
        val inspection = jobs.inspect({ ByteArrayInputStream(zip) }, null)
        assertEquals(RestorePlan.Kind.DEEPSEEK, inspection.contents.kind)
        jobs.restore({ ByteArrayInputStream(zip) }, inspection, RestoreChoice.ADD, "")

        val ids = store.sessionIds()
        assertEquals(3, ids.size)
        val imported = store.sessionFile((ids - setOf("s1", "s2")).single())!!
        assertEquals("Trip", imported.session.title)
        val user = imported.messages.first { it.body is MessageBody.User }.body as MessageBody.User
        assertEquals("plan a trip", user.text)
        val image = store.attachmentsFor(listOf(imported.session.id)).single()
        assertTrue(files.original(image.id).readBytes().contentEquals(png))
        assertEquals(s1, store.sessionFile("s1"))
        assertNull(inspection.keys)
    }

    private fun zip(vararg entries: Pair<String, Any>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            entries.forEach { (p, d) ->
                z.putNextEntry(ZipEntry(p))
                z.write(if (d is ByteArray) d else d.toString().encodeToByteArray())
                z.closeEntry()
            }
        }
    }.toByteArray()

    private fun rewrite(zip: ByteArray, edit: (Pair<String, ByteArray>) -> ByteArray): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.forEach { entries += it.name to z.readBytes() } }
        return zip(*entries.map { it.first to edit(it) as Any }.toTypedArray())
    }
}
