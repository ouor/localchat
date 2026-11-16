package com.ouor.localchat.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.io.path.createTempDirectory

class AttachmentFilesGcTest {

    @Test
    fun `files without a row are removed once older than the cutoff`() {
        val dir = createTempDirectory("att").toFile()
        val files = AttachmentFiles(dir)
        listOf(files.original("keep"), files.text("keep"), files.original("gone"), files.requestImage("gone"), files.original("young"))
            .forEach { it.writeText("x") }
        files.original("young").setLastModified(5_000)
        listOf("keep", "keep.txt", "gone", "gone.req.jpg").forEach { java.io.File(dir, it).setLastModified(1_000) }

        files.deleteWithoutRow(rowIds = setOf("keep"), cutoff = 2_000)

        assertEquals(setOf("keep", "keep.txt", "young"), dir.list()!!.toSet())
    }
}
