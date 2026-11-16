package com.ouor.localchat.chat

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.core.Clock
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.db.AppDatabase
import org.junit.After
import org.junit.runner.RunWith

/** ChatStoreContract on the real store over in-memory Room (AR F-08). */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryContractTest : ChatStoreContract() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    override fun store(): ChatStore =
        SessionRepository(db, db.chatDao(), db.attachmentDao(), AttachmentFiles(context.cacheDir), Clock.System, FakeBrowserPort())

    @After
    fun close() = db.close()
}
