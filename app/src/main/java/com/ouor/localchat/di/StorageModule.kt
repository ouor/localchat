package com.ouor.localchat.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.ouor.localchat.core.model.AppSettings
import androidx.room.Room
import com.ouor.localchat.storage.AndroidKeyStoreKeySource
import com.ouor.localchat.storage.AppSettingsSerializer
import com.ouor.localchat.storage.SecretEntries
import com.ouor.localchat.storage.SecretEntriesSerializer
import com.ouor.localchat.storage.SecretKeySource
import com.ouor.localchat.storage.db.AppDatabase
import com.ouor.localchat.storage.db.dao.AttachmentDao
import com.ouor.localchat.storage.db.dao.ChatDao
import com.ouor.localchat.storage.db.dao.EndpointDao
import com.ouor.localchat.storage.db.dao.ModelDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    // No fallbackToDestructiveMigration (architecture 8.4).
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun chatDao(db: AppDatabase): ChatDao = db.chatDao()

    @Provides
    fun attachmentDao(db: AppDatabase): AttachmentDao = db.attachmentDao()

    @Provides
    fun endpointDao(db: AppDatabase): EndpointDao = db.endpointDao()

    @Provides
    fun modelDao(db: AppDatabase): ModelDao = db.modelDao()

    @Provides
    @Singleton
    fun secretsDataStore(@ApplicationContext context: Context): DataStore<SecretEntries> =
        // Unreadable secrets cannot be recovered anyway; start empty so the app still opens (keys re-entered).
        DataStoreFactory.create(
            serializer = SecretEntriesSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { SecretEntries() },
            produceFile = { context.dataStoreFile("secrets.json") },
        )

    @Provides
    fun secretKeySource(source: AndroidKeyStoreKeySource): SecretKeySource = source

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<AppSettings> =
        DataStoreFactory.create(
            serializer = AppSettingsSerializer,
            // An unreadable file falls back to defaults instead of crashing on every start.
            corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
            produceFile = { context.dataStoreFile("settings.json") },
        )
}
