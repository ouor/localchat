package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.BackupFormat
import com.ouor.localchat.backup.format.FormatMigration
import com.ouor.localchat.backup.format.migrate
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.ApiKey
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.storage.AttachmentFiles
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Architecture 9.2 guards. If the key snapshot fails, a backed-up model changed: a pure addition
 * (with a default) only needs the snapshot updated; a rename/removal/meaning change needs
 * BackupFormat.CURRENT + 1, a migration and a new golden zip. Regenerate with env UPDATE_GOLDEN=1.
 */
class BackupFormatGoldenTest {
    private val resources = File("src/test/resources/backup")
    private val update = System.getenv("UPDATE_GOLDEN") == "1"

    private suspend fun currentExport(): ByteArray {
        val files = AttachmentFiles(tempDir())
        val store = FakeStore(files).apply {
            put(BackupFixtures.session("s1", "f1"), BackupFixtures.attachment("f1"))
            providers = BackupFixtures.providers
            keys = mapOf("ep1" to ApiKey("sk-golden-fake"))
        }
        val out = ByteArrayOutputStream()
        BackupJobs(store, files, Clock { 1_763_287_200_000 }, "1.0 (1)").export(BackupScope.Full, out, "golden-pass".toCharArray(), AppSettings(region = "KR", ttsVoice = "v", defaultModel = BackupFixtures.model)) {}
        return out.toByteArray()
    }

    @Test
    fun `every golden zip restores with the current app`() = runTest {
        if (update) File(resources, "v${BackupFormat.CURRENT}/full.zip").apply { parentFile!!.mkdirs() }.writeBytes(currentExport())
        val goldens = resources.listFiles { f -> f.isDirectory && f.name.startsWith("v") }.orEmpty().flatMap { it.listFiles { f -> f.extension == "zip" }.orEmpty().toList() }
        assert(goldens.size >= BackupFormat.CURRENT) { "one golden zip per format version, found $goldens" }
        for (golden in goldens) {
            val files = AttachmentFiles(tempDir())
            val store = FakeStore(files)
            val jobs = BackupJobs(store, files, Clock.System, "test")
            val bytes = golden.readBytes()
            val inspection = jobs.inspect({ ByteArrayInputStream(bytes) }, "golden-pass".toCharArray())
            jobs.restore({ ByteArrayInputStream(bytes) }, inspection, RestoreChoice.REPLACE_ALL, "")
            assertEquals(golden.path, 1, store.sessions.size)
            assertEquals(golden.path, "sk-golden-fake", store.keys.getValue("ep1").value)
            Validator.checkTree(store.sessions.values.single())
        }
    }

    @Test
    fun `exported JSON keys match the snapshot`() = runTest {
        val keys = sortedSetOf<String>()
        ZipInputStream(ByteArrayInputStream(currentExport())).use { z ->
            generateSequence { z.nextEntry }.filter { it.name.endsWith(".json") || it.name.endsWith(".enc") }.forEach { e ->
                val name = BackupFormat.sessionIdOf(e.name)?.let { "sessions/*.json" } ?: e.name
                collect(AppJson.parseToJsonElement(z.readBytes().decodeToString()), name, keys)
            }
        }
        val snapshot = File(resources, "v${BackupFormat.CURRENT}/keys.txt")
        if (update) snapshot.writeText(keys.joinToString("\n", postfix = "\n"))
        assertEquals(snapshot.readLines().filter { it.isNotBlank() }.joinToString("\n"), keys.joinToString("\n"))
    }

    @Test
    fun `migrations run in order from the file version`() {
        val m1 = object : FormatMigration {
            override val from = 1
            override fun session(o: JsonObject) = JsonObject(o + ("v2" to JsonPrimitive(true)))
        }
        val m2 = object : FormatMigration {
            override val from = 2
            override fun session(o: JsonObject) = JsonObject(o + ("v3" to JsonPrimitive(o.containsKey("v2"))))
        }
        val fromV1 = migrate(1, JsonObject(emptyMap()), listOf(m2, m1)) { session(it) }
        assertEquals(JsonPrimitive(true), fromV1["v3"])
        val fromV2 = migrate(2, JsonObject(emptyMap()), listOf(m2, m1)) { session(it) }
        assertEquals(JsonPrimitive(false), fromV2["v3"])
    }

    private fun collect(e: JsonElement, path: String, out: MutableSet<String>) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) ->
                // Type tags of sealed classes are part of the format too.
                val key = if (k == "type" && v is JsonPrimitive) "$path.type=${v.content}" else "$path.$k"
                out += key
                // ModelRecord.server is the server's own /models JSON, opaque to the format.
                if (k != "server") collect(v, if (k == "type") path else "$path.$k", out)
            }
            is JsonArray -> e.forEach { collect(it, "$path[]", out) }
            else -> Unit
        }
    }
}
