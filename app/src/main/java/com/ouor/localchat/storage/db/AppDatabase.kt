package com.ouor.localchat.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ouor.localchat.storage.db.dao.AttachmentDao
import com.ouor.localchat.storage.db.dao.ChatDao
import com.ouor.localchat.storage.db.dao.ProviderDao
import com.ouor.localchat.storage.db.entities.AttachmentEntity
import com.ouor.localchat.storage.db.entities.AttachmentRefEntity
import com.ouor.localchat.storage.db.entities.DraftEntity
import com.ouor.localchat.storage.db.entities.EndpointEntity
import com.ouor.localchat.storage.db.entities.MessageEntity
import com.ouor.localchat.storage.db.entities.ModelEntity
import com.ouor.localchat.storage.db.entities.SessionEntity
import com.ouor.localchat.storage.db.entities.SummaryEntity

/** Architecture 8.1. Migration policy 8.4: never destructive; schema JSON is committed. */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        SessionEntity::class, MessageEntity::class, AttachmentEntity::class, AttachmentRefEntity::class,
        DraftEntity::class, SummaryEntity::class, EndpointEntity::class, ModelEntity::class,
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun providerDao(): ProviderDao

    companion object {
        const val NAME = "localchat.db"
    }
}
