package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.BackupFormat
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ThemeMode
import com.ouor.localchat.storage.AttachmentFiles
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupJobsTest {
    private val sourceFiles = AttachmentFiles(tempDir())
    private val source = FakeStore(sourceFiles).apply {
        put(BackupFixtures.session("s1", "f1"), BackupFixtures.attachment("f1"))
        put(BackupFixtures.session("s2", "f2"), BackupFixtures.attachment("f2"))
        providers = BackupFixtures.providers
        keys = mapOf("ep1" to ApiKey("sk-test-not-a-real-key"))
    }
    private val settings = AppSettings(theme = ThemeMode.DARK, maxSteps = 40, builtInSensitiveEnabled = false)

    private fun jobs(store: BackupStore, files: AttachmentFiles) = BackupJobs(store, files, Clock { 1_700_000_000_000 }, "1.0 (1)")

    private suspend fun export(scope: BackupScope = BackupScope.Full, password: CharArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        jobs(source, sourceFiles).export(scope, out, password, settings) {}
        return out.toByteArray()
    }

    @Test
    fun `full backup restores into an empty store`() = runTest {
        val zip = export(password = "password1".toCharArray())
        val files = AttachmentFiles(tempDir())
        val target = FakeStore(files)
        val jobs = jobs(target, files)

        val inspection = jobs.inspect({ ByteArrayInputStream(zip) }, "password1".toCharArray())
        assertEquals(RestorePlan.Kind.FULL, inspection.contents.kind)
        assertEquals(listOf("s1", "s2"), inspection.contents.sessionIds)
        assertEquals("sk-test-not-a-real-key", inspection.keys!!.getValue("ep1").value)
        assertTrue("originals copied in pass 1", files.original("f1").isFile)
        assertTrue("tables untouched in pass 1", target.sessions.isEmpty())

        val restored = jobs.restore({ ByteArrayInputStream(zip) }, inspection, RestoreChoice.REPLACE_ALL, " (copy)")
        assertEquals(settings, restored)
        assertEquals(source.sessions, target.sessions)
        assertEquals(source.attachments, target.attachments)
        assertEquals(source.providers, target.providers)
        assertEquals(source.keys, target.keys)
    }

    @Test
    fun `restore without password drops keys and clears hasKey`() = runTest {
        val zip = export(password = "password1".toCharArray())
        val files = AttachmentFiles(tempDir())
        val target = FakeStore(files).apply { keys = mapOf("old" to ApiKey("x")) }
        val inspection = jobs(target, files).inspect({ ByteArrayInputStream(zip) }, null)
        assertNull(inspection.keys)
        assertTrue(inspection.contents.secrets != null)
        jobs(target, files).restore({ ByteArrayInputStream(zip) }, inspection, RestoreChoice.REPLACE_ALL, "")
        assertTrue(target.keys.isEmpty())
        assertFalse(target.providers.endpoints.single().hasKey)
    }

    @Test
    fun `wrong password is reported`() = runTest {
        val zip = export(password = "password1".toCharArray())
        val files = AttachmentFiles(tempDir())
        expectError(BackupError.WRONG_PASSWORD) { jobs(FakeStore(files), files).inspect({ ByteArrayInputStream(zip) }, "nope".toCharArray()) }
    }

    @Test
    fun `session backup reports conflicts, overwrites or copies`() = runTest {
        val zip = export(BackupScope.Session("s1"))
        val files = AttachmentFiles(tempDir())
        val target = FakeStore(files).apply { put(BackupFixtures.session("s1", "f1", title = "local"), BackupFixtures.attachment("f1")) }
        val jobs = jobs(target, files)
        val inspection = jobs.inspect({ ByteArrayInputStream(zip) }, null)
        assertEquals(RestorePlan.Kind.SESSION, inspection.contents.kind)
        assertEquals(listOf("s1"), inspection.conflicts)

        jobs.restore({ ByteArrayInputStream(zip) }, inspection, RestoreChoice.COPY, " (copy)")
        assertEquals(2, target.sessions.size)
        val copy = target.sessions.values.single { it.session.id != "s1" }
        assertEquals("Chat s1 (copy)", copy.session.title)
        Validator.checkTree(copy)
        assertTrue(copy.messages.none { it.id.startsWith("s1-") })

        jobs.restore({ ByteArrayInputStream(zip) }, inspection, RestoreChoice.OVERWRITE, "")
        assertEquals("Chat s1", target.sessions.getValue("s1").session.title)
    }

    @Test
    fun `export snapshot never references attachments outside the backup`() = runTest {
        val s3 = BackupFixtures.session("s3", "missing")
        source.sessions["s3"] = s3 // references an attachment row that does not exist
        val zip = export()
        val files = AttachmentFiles(tempDir())
        val inspection = jobs(FakeStore(files), files).inspect({ ByteArrayInputStream(zip) }, null)
        assertEquals(3, inspection.contents.sessionIds.size)
    }

    @Test
    fun `validation errors`() = runTest {
        val good = export()
        expectError(BackupError.UNRECOGNIZED) { inspect("not a zip".encodeToByteArray()) }
        expectError(BackupError.UNRECOGNIZED) { inspect(rewrite(good) { entries -> listOf(entries.last()) + entries.dropLast(1) }) }
        expectError(BackupError.NEWER_VERSION) { inspect(rewriteManifest(good) { it + ("formatVersion" to JsonPrimitive(BackupFormat.CURRENT + 1)) }) }
        expectError(BackupError.BAD_VERSION) { inspect(rewriteManifest(good) { it + ("formatVersion" to JsonPrimitive("one")) }) }
        expectError(BackupError.BAD_VERSION) { inspect(rewriteManifest(good) { it + ("formatVersion" to JsonPrimitive(0)) }) }
        expectError(BackupError.INCOMPLETE) { inspect(rewrite(good) { entries -> entries.filterNot { it.first == "sessions/s2.json" } }) }
        expectError(BackupError.INCOMPLETE) { inspect(rewrite(good) { entries -> entries.filterNot { it.first.startsWith("attachments/f1/") } }) }
        expectError(BackupError.INCOMPLETE) { inspect(rewrite(good) { entries -> entries.map { if (it.first == "sessions/s1.json") it.first to "{".encodeToByteArray() else it } }) }
        expectError(BackupError.BROKEN_ATTACHMENT) { inspect(rewrite(good) { entries -> entries.map { if (it.first.startsWith("attachments/f1/")) it.first to "tampered".encodeToByteArray() else it } }) }
        expectError(BackupError.BROKEN_TREE) {
            inspect(rewrite(good) { entries ->
                entries.map { (path, data) ->
                    if (path != "sessions/s1.json") path to data
                    else path to data.decodeToString().replace("\"parentId\":\"s1-u1\"", "\"parentId\":\"nowhere\"").encodeToByteArray()
                }
            })
        }
    }

    @Test
    fun `failed inspect removes the originals it copied`() = runTest {
        val bad = rewrite(export()) { entries -> entries.filterNot { it.first == "sessions/s2.json" } }
        val files = AttachmentFiles(tempDir())
        expectError(BackupError.INCOMPLETE) { jobs(FakeStore(files), files).inspect({ ByteArrayInputStream(bad) }, null) }
        assertFalse(files.original("f1").exists())
    }

    @Test
    fun `restore fails atomically when the file changed between passes`() = runTest {
        val zip = export()
        val files = AttachmentFiles(tempDir())
        val target = FakeStore(files).apply { put(BackupFixtures.session("keep", "f9"), BackupFixtures.attachment("f9")) }
        val inspection = jobs(target, files).inspect({ ByteArrayInputStream(zip) }, null)
        val broken = rewrite(zip) { entries -> entries.filterNot { it.first == "sessions/s2.json" } }
        expectError(BackupError.INCOMPLETE) { jobs(target, files).restore({ ByteArrayInputStream(broken) }, inspection, RestoreChoice.REPLACE_ALL, "") }
        assertEquals(listOf("keep"), target.sessions.keys.toList())
    }

    @Test
    fun `restore copies originals again when GC removed the pass-1 copies`() = runTest {
        val zip = export()
        val files = AttachmentFiles(tempDir())
        val target = FakeStore(files)
        val inspection = jobs(target, files).inspect({ ByteArrayInputStream(zip) }, null)
        files.delete("f1") // an attachment GC ran: the copy had no row yet
        jobs(target, files).restore({ ByteArrayInputStream(zip) }, inspection, RestoreChoice.REPLACE_ALL, "")
        assertTrue(files.original("f1").readBytes().contentEquals(BackupFixtures.bytes("f1")))
    }

    @Test
    fun `failed restore leaves no file it wrote`() = runTest {
        val zip = export()
        val files = AttachmentFiles(tempDir())
        val target = FakeStore(files)
        val inspection = jobs(target, files).inspect({ ByteArrayInputStream(zip) }, null)
        files.delete("f1")
        val tampered = rewrite(zip) { entries -> entries.map { if (it.first.startsWith("attachments/f1/")) it.first to "tampered".encodeToByteArray() else it } }
        expectError(BackupError.BROKEN_ATTACHMENT) { jobs(target, files).restore({ ByteArrayInputStream(tampered) }, inspection, RestoreChoice.REPLACE_ALL, "") }
        assertFalse(files.original("f1").exists())
        assertTrue(target.sessions.isEmpty())
    }

    @Test
    fun `summary counts sessions and attachment bytes`() = runTest {
        val s = jobs(source, sourceFiles).summary(BackupScope.Full)
        assertEquals(BackupSummary(2, 2, BackupFixtures.bytes("f1").size + BackupFixtures.bytes("f2").size.toLong()), s)
    }

    @Test
    fun `copy remap keeps references consistent`() {
        val f = BackupFixtures.session("s1", "f1")
        var n = 0
        val copy = SessionRemap.asCopy(f, " (copy)") { "n${n++}" }
        Validator.checkTree(copy)
        assertNotEquals(f.session.id, copy.session.id)
        assertEquals(copy.session.id, copy.draft!!.key)
        assertEquals((copy.messages.first().body as MessageBody.User).attachmentIds, listOf("f1"))
    }

    private suspend fun inspect(zip: ByteArray) {
        val files = AttachmentFiles(tempDir())
        jobs(FakeStore(files), files).inspect({ ByteArrayInputStream(zip) }, null)
    }

    private suspend fun expectError(error: BackupError, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $error")
        } catch (e: BackupException) {
            assertEquals(error, e.error)
        }
    }

    private fun rewriteManifest(zip: ByteArray, edit: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>) =
        rewrite(zip) { entries ->
            entries.map { (path, data) ->
                if (path != BackupFormat.MANIFEST) path to data
                else path to JsonObject(edit(AppJson.parseToJsonElement(data.decodeToString()).jsonObject)).toString().encodeToByteArray()
            }
        }

    private fun rewrite(zip: ByteArray, edit: (List<Pair<String, ByteArray>>) -> List<Pair<String, ByteArray>>): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> generateSequence { z.nextEntry }.forEach { entries += it.name to z.readBytes() } }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> edit(entries).forEach { (p, d) -> z.putNextEntry(ZipEntry(p)); z.write(d); z.closeEntry() } }
        return out.toByteArray()
    }
}
