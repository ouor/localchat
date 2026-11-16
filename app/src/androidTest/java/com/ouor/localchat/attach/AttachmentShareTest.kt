package com.ouor.localchat.attach

import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** BUG-17: originals are viewable/shareable through the provider, and nothing else is. */
@RunWith(AndroidJUnit4::class)
class AttachmentShareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    // The real directory: the provider only maps filesDir/attachments.
    private val files = AttachmentFiles(context)
    private val ingest = AttachmentIngest(context, files, db.attachmentDao(), Clock.System)
    private val a = Attachment("share-test-1", "report final.pdf", "application/pdf", 5, AttachmentKind.DOCUMENT, PrepState.Ready, hasOriginal = true, createdAt = 0)
    private val out = File(context.cacheDir, "share-copy.bin")

    @After
    fun close() {
        files.delete(a.id)
        out.delete()
        db.close()
    }

    @Test
    fun contentUriServesTheOriginalUnderItsName() = runBlocking {
        assertNull(ingest.contentUri(a))
        files.original(a.id).writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val uri = ingest.contentUri(a)!!
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".files", uri.authority)
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { c ->
            c.moveToFirst()
            assertEquals("report final.pdf", c.getString(0))
        }
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.toList())

        ingest.copyOriginal(a, Uri.fromFile(out))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), out.readBytes().toList())
    }

    @Test
    fun nothingOutsideAttachmentsGetsAUri() {
        val authority = context.packageName + ".files"
        File(context.filesDir, "attachments").mkdirs()
        val outside = listOf(
            File(context.filesDir, "secrets_probe.json"),
            context.getDatabasePath("probe.db"),
            File(context.filesDir, "datastore/probe.preferences_pb"),
            File(context.cacheDir, "probe.txt"),
            File(context.filesDir, "attachments/../probe.txt"),
        )
        outside.forEach { f ->
            f.parentFile?.mkdirs()
            f.writeText("x")
            try {
                FileProvider.getUriForFile(context, authority, f)
                fail("exposed ${f.path}")
            } catch (_: IllegalArgumentException) {
            } finally {
                f.delete()
            }
        }
    }

    @Test
    fun copyFailureSurfaces() = runBlocking {
        try {
            ingest.copyOriginal(a, Uri.fromFile(out))
            fail("copied a missing original")
        } catch (_: java.io.IOException) {
        }
    }
}
