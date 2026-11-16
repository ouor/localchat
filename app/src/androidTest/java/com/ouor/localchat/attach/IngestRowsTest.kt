package com.ouor.localchat.attach

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.db.AppDatabase
import com.ouor.localchat.storage.db.toModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Every state AttachmentIngest emits is already a row; retry reads the row (Phase 2 task 1). */
@RunWith(AndroidJUnit4::class)
class IngestRowsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val dao = db.attachmentDao()
    private val files = AttachmentFiles(File(context.cacheDir, "ingest-test").apply { deleteRecursively() })

    @After
    fun close() = db.close()

    @Test
    fun rowsBeforeEveryEmissionAndRetryFromRow() = runBlocking {
        val ingest = AttachmentIngest(context, files, dao, Clock.System)
        val src = File(context.cacheDir, "notes.txt").apply { writeText("hello") }

        val emitted = mutableListOf<com.ouor.localchat.core.model.Attachment>()
        // flowOn buffers, so the row may already be ahead of this emission; it must exist, though.
        ingest.ingest(Uri.fromFile(src)).collect { emitted += it; assertNotNull(dao.get(it.id)) }
        assertEquals(listOf(PrepState.Preparing, PrepState.Ready), emitted.map { it.state })
        assertEquals(PrepState.Ready, dao.get(emitted.last().id)!!.toModel().state)

        val id = emitted.last().id
        files.text(id).delete()
        val again = ingest.retry(id).toList()
        assertEquals(listOf(PrepState.Preparing, PrepState.Ready), again.map { it.state })
        assertEquals("hello", files.text(id).readText())

        // Original gone and no Uri known (a restart): READ_FAILED, stored.
        val fresh = AttachmentIngest(context, files, dao, Clock.System)
        files.original(id).delete()
        assertEquals(PrepState.Failed(PrepFailure.READ_FAILED), fresh.retry(id).toList().last().state)
        assertEquals(PrepState.Failed(PrepFailure.READ_FAILED), dao.get(id)!!.toModel().state)
    }

    @Test
    fun parserCrashesEndAsFailed() {
        val ingest = AttachmentIngest(context, files, dao, Clock.System)
        val doc = com.ouor.localchat.core.model.Attachment(
            "crash1", "broken.pdf", "application/pdf", 1, com.ouor.localchat.core.model.AttachmentKind.DOCUMENT,
            PrepState.Preparing, hasOriginal = true, createdAt = 0,
        )
        files.text(doc.id).writeText("partial")
        assertEquals(PrepState.Failed(PrepFailure.EXTRACT_FAILED), ingest.guarded(doc) { throw NullPointerException() }.state)
        assertEquals(false, files.text(doc.id).exists())
        val image = doc.copy(id = "crash2", kind = com.ouor.localchat.core.model.AttachmentKind.IMAGE)
        assertEquals(PrepState.Failed(PrepFailure.IMAGE_DECODE), ingest.guarded(image) { throw OutOfMemoryError() }.state)
    }

    @Test
    fun imageModelPrefersRequestImage() {
        val ingest = AttachmentIngest(context, files, dao, Clock.System)
        val image = com.ouor.localchat.core.model.Attachment(
            "img1", "a.png", "image/png", 1, com.ouor.localchat.core.model.AttachmentKind.IMAGE,
            PrepState.Ready, hasOriginal = true, createdAt = 0,
        )
        assertEquals(null, ingest.imageModel(image))
        files.original(image.id).writeText("o")
        assertEquals(files.original(image.id), ingest.imageModel(image))
        files.requestImage(image.id).writeText("r")
        assertEquals(files.requestImage(image.id), ingest.imageModel(image))
        assertEquals(null, ingest.imageModel(image.copy(kind = com.ouor.localchat.core.model.AttachmentKind.DOCUMENT)))
    }
}
