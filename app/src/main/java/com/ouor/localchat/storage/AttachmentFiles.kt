package com.ouor.localchat.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only owner of filesDir/attachments paths: {id} original, {id}.txt extracted text,
 * {id}.req.jpg request image (architecture 3.3). Also runs attachment GC.
 */
@Singleton
class AttachmentFiles(private val dir: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "attachments"))

    fun original(id: String): File = file(id)

    fun text(id: String): File = file("$id.txt")

    fun requestImage(id: String): File = file("$id.req.jpg")

    /** Removes every file of [id]. */
    fun delete(id: String) {
        listOf(original(id), text(id), requestImage(id)).forEach { it.delete() }
    }

    /**
     * GC of files with no attachment row, last modified before [cutoff]: a crash between copy and
     * upsert, or originals a restore inspect copied but never restored. Rows themselves are GC'd by chat.
     */
    fun deleteWithoutRow(rowIds: Set<String>, cutoff: Long) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".txt").removeSuffix(".req.jpg")
            if (id !in rowIds && f.lastModified() < cutoff) f.delete()
        }
    }

    private fun file(name: String): File {
        // Ids are UUIDs or backup ids; anything that could leave the directory is a bug or an attack.
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && !name.startsWith(".")) { "bad attachment id" }
        dir.mkdirs()
        return File(dir, name)
    }
}
