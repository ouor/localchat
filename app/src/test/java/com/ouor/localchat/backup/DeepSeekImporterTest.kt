package com.ouor.localchat.backup

import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.MessageBody
import com.ouor.localchat.core.model.ReplyStatus
import com.ouor.localchat.core.model.TextBlock
import com.ouor.localchat.core.model.ThinkingBlock
import com.ouor.localchat.core.model.ToolCallBlock
import com.ouor.localchat.core.model.ToolResultBlock
import com.ouor.localchat.storage.AttachmentFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class DeepSeekImporterTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)

    private val session = """
        {"id":"abc","title":"Trip","pinned":false,"current_message_id":4,
         "inserted_at":"2026-11-11T02:33:23.270Z","updated_at":"2026-11-11T02:40:00Z",
         "messages":[
          {"id":1,"parent_id":null,"role":"USER","status":"FINISHED","thinking_enabled":true,"search_enabled":true,
           "accumulated_token_usage":0,"inserted_at":"2026-11-11T02:33:23.270Z",
           "fragments":[{"type":"REQUEST","content":"plan a trip"},
             {"type":"FILE","files":[
               {"id":"img1","file_name":"map.png","file_size":11,"token_usage":null,"inserted_at":"2026-11-11T02:33:23Z","export_path":"chat_sessions/abc_files/img1_map.png","is_image":true,"width":4,"height":3},
               {"id":"doc1","file_name":"notes.pdf","file_size":99,"token_usage":null,"inserted_at":"2026-11-11T02:33:23Z","export_path":null,"is_image":false,"width":null,"height":null}]}]},
          {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","thinking_enabled":true,"search_enabled":true,
           "accumulated_token_usage":10,"inserted_at":"2026-11-11T02:33:30Z",
           "fragments":[{"type":"THINK","content":"hmm","elapsed_secs":2.5},
             {"type":"TOOL_SEARCH","queries":["paris"],"results":[{"url":"https://p.fr","title":"Paris","snippet":"s","published_at":null,"site_name":"P"},{"url":"javascript:x","title":"bad","snippet":"","published_at":null,"site_name":null}],"status":"FINISHED"},
             {"type":"TIP","content":"tip","style":"INFO"},
             {"type":"UNKNOWN_FUTURE"},
             {"type":"RESPONSE","content":"Go to Paris [1]"}]},
          {"id":3,"parent_id":1,"role":"ASSISTANT","status":"CONTENT_FILTER","thinking_enabled":false,"search_enabled":false,
           "accumulated_token_usage":10,"inserted_at":"2026-11-11T02:34:00Z",
           "fragments":[{"type":"THINK","content":"x","elapsed_secs":null},{"type":"RESPONSE","content":"hidden"},{"type":"TEMPLATE_RESPONSE","content":"Sorry"}]},
          {"id":4,"parent_id":2,"role":"USER","status":"FINISHED","thinking_enabled":false,"search_enabled":false,
           "accumulated_token_usage":0,"inserted_at":"2026-11-11T02:35:00Z","fragments":[{"type":"REQUEST","content":"thanks"}]}
         ]}
    """.trimIndent()

    private fun archive(
        manifest: String = """{"export_format_version":3}""",
        sessionJson: String = session,
        index: String = """[{"id":"abc","title":"Trip","pinned":false,"message_count":4,"inserted_at":"2026-11-11T02:33:23Z","updated_at":"2026-11-11T02:40:00Z"}]""",
    ) = storedWithDescriptors(
        listOf(
            "manifest.json" to manifest.encodeToByteArray(),
            "user.json" to "{}".encodeToByteArray(),
            "chat_sessions/abc.json" to sessionJson.encodeToByteArray(),
            "chat_session_index.json" to index.encodeToByteArray(),
            "chat_sessions/abc_files/img1_map.png" to png,
        ),
    )

    @Test
    fun `imports sessions, tree selection, blocks and images`() = runTest {
        val files = AttachmentFiles(tempDir())
        val store = FakeStore(files)
        val jobs = BackupJobs(store, files, Clock.System, "test")
        val zip = archive()
        val inspection = jobs.inspect({ ByteArrayInputStream(zip) }, null)
        assertEquals(RestorePlan.Kind.DEEPSEEK, inspection.contents.kind)
        jobs.restore({ ByteArrayInputStream(zip) }, inspection, RestoreChoice.ADD, "")

        val f = store.sessions.values.single()
        Validator.checkTree(f)
        assertEquals("Trip", f.session.title)
        val (q1, a2, a3, q4) = f.messages
        // current_message_id=4: path 1 -> 2 -> 4; 4 itself selects nothing.
        assertEquals(q1.id, f.session.rootSelectedId)
        assertEquals(a2.id, q1.selectedChildId)
        assertEquals(q4.id, a2.selectedChildId)

        val user = q1.body as MessageBody.User
        assertEquals("plan a trip", user.text)
        val image = store.attachments.getValue(user.attachmentIds[0])
        val doc = store.attachments.getValue(user.attachmentIds[1])
        assertEquals(AttachmentKind.IMAGE to true, image.kind to image.hasOriginal)
        assertEquals("image/png", image.mime)
        assertTrue(files.original(image.id).readBytes().contentEquals(png))
        assertEquals(AttachmentKind.DOCUMENT to false, doc.kind to doc.hasOriginal)

        val reply = a2.body as MessageBody.Assistant
        assertTrue(reply.imported)
        assertEquals(ReplyStatus.DONE, reply.status)
        val think = reply.blocks[0] as ThinkingBlock
        assertEquals(2500L, think.endedAt!! - think.startedAt!!)
        assertEquals("web_search", (reply.blocks[1] as ToolCallBlock).name)
        assertEquals(listOf("https://p.fr"), (reply.blocks[2] as ToolResultBlock).sources.map { it.url })
        assertEquals("Go to Paris [1]", (reply.blocks[3] as TextBlock).text)
        assertEquals(4, reply.blocks.size)

        val refused = a3.body as MessageBody.Assistant
        assertTrue(refused.refusal)
        assertEquals(listOf("Sorry"), refused.blocks.map { (it as TextBlock).text })
    }

    @Test
    fun `rejects other versions, broken trees and index mismatches`() = runTest {
        expect(BackupError.BAD_VERSION) { archive(manifest = """{"export_format_version":4}""") }
        expect(BackupError.BROKEN_TREE) { archive(sessionJson = session.replace(""""id":2,"parent_id":1""", """"id":2,"parent_id":3""")) }
        expect(BackupError.BROKEN_TREE) { archive(sessionJson = session.replace(""""id":4,"parent_id":2,"role":"USER"""", """"id":4,"parent_id":2,"role":"ASSISTANT"""")) }
        expect(BackupError.INCOMPLETE) { archive(index = "[]") }
    }

    @Test
    fun `missing image keeps name only`() = runTest {
        val files = AttachmentFiles(tempDir())
        val store = FakeStore(files)
        val zip = storedWithDescriptors(
            listOf(
                "manifest.json" to """{"export_format_version":3}""".encodeToByteArray(),
                "chat_sessions/abc.json" to session.encodeToByteArray(),
                "chat_session_index.json" to """[{"id":"abc","title":null,"pinned":false,"message_count":4,"inserted_at":"2026-11-11T02:33:23Z","updated_at":"2026-11-11T02:40:00Z"}]""".encodeToByteArray(),
            ),
        )
        val jobs = BackupJobs(store, files, Clock.System, "test")
        jobs.restore({ ByteArrayInputStream(zip) }, jobs.inspect({ ByteArrayInputStream(zip) }, null), RestoreChoice.ADD, "")
        assertFalse(store.attachments.values.any { it.hasOriginal })
    }

    private suspend fun expect(error: BackupError, zip: () -> ByteArray) {
        val files = AttachmentFiles(tempDir())
        try {
            BackupJobs(FakeStore(files), files, Clock.System, "test").inspect({ ByteArrayInputStream(zip()) }, null)
            fail("expected $error")
        } catch (e: BackupException) {
            assertEquals(error, e.error)
        }
    }

    /** STORE entries with data descriptors (flag bit 3), as chat-demo's fflate streaming writer emits. */
    private fun storedWithDescriptors(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        fun ByteArrayOutputStream.u16(v: Int) = apply { write(v and 0xff); write(v ushr 8 and 0xff) }
        fun ByteArrayOutputStream.u32(v: Long) = apply { for (i in 0 until 4) write((v ushr (8 * i)).toInt() and 0xff) }
        for ((name, data) in entries) {
            val offset = out.size().toLong()
            val n = name.encodeToByteArray()
            val crc = CRC32().apply { update(data) }.value
            out.u32(0x04034b50).u16(20).u16(0x0808).u16(0).u16(0).u16(0x21).u32(0).u32(0).u32(0).u16(n.size).u16(0)
            out.write(n)
            out.write(data)
            out.u32(0x08074b50).u32(crc).u32(data.size.toLong()).u32(data.size.toLong())
            central.u32(0x02014b50).u16(20).u16(20).u16(0x0808).u16(0).u16(0).u16(0x21).u32(crc)
                .u32(data.size.toLong()).u32(data.size.toLong()).u16(n.size).u16(0).u16(0).u16(0).u16(0).u32(0).u32(offset)
            central.write(n)
        }
        val cdOffset = out.size().toLong()
        out.write(central.toByteArray())
        out.u32(0x06054b50).u16(0).u16(0).u16(entries.size).u16(entries.size).u32(central.size().toLong()).u32(cdOffset).u16(0)
        return out.toByteArray()
    }
}
