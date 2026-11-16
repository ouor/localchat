package com.ouor.localchat.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.storage.AppSettingsSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    // TODO(track B): AppDatabase (Room, exportSchema, no destructive fallback).

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
