package com.ouor.localchat.attach

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.db.AppDatabase
import com.ouor.localchat.storage.db.toModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** App-made attachments and request payloads live in attach (AR F-04). */
@RunWith(AndroidJUnit4::class)
class StoredAttachmentsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val dao = db.attachmentDao()
    private val files = AttachmentFiles(File(context.cacheDir, "stored-test").apply { deleteRecursively() })
    private val stored = StoredAttachments(dao, files, AttachmentIngest(context, files, dao, Clock.System), Clock.System)

    @After
    fun close() = db.close()

    @Test
    fun savesRowsAndFilesAndRebuildsPayloads() = runBlocking {
        val textId = stored.saveText("page.txt", "page body")
        val row = dao.get(textId)!!.toModel()
        assertEquals(AttachmentKind.DOCUMENT to PrepState.Ready, row.kind to row.state)
        assertEquals("page body", files.original(textId).readText())
        assertEquals("page body", stored.payload(textId)!!.text)

        // After a restore only the original exists: the payload rebuilds {id}.txt.
        files.text(textId).delete()
        assertEquals("page body", stored.payload(textId)!!.text)
        assertTrue(files.text(textId).isFile)

        val artId = stored.saveArtifact("shot.bin", "application/octet-stream", byteArrayOf(1, 2, 3))
        assertEquals(AttachmentKind.ARTIFACT, dao.get(artId)!!.toModel().kind)
        assertTrue(files.original(artId).readBytes().contentEquals(byteArrayOf(1, 2, 3)))

        assertNull(stored.payload("missing"))
    }
}
