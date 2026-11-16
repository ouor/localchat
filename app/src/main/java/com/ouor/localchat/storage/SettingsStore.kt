package com.ouor.localchat.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** D8 app settings (public surface, architecture 10.2). */
@Singleton
class SettingsStore @Inject constructor(private val store: DataStore<AppSettings>) {

    val settings: Flow<AppSettings> = store.data

    suspend fun update(transform: AppSettings.() -> AppSettings) {
        store.updateData { it.transform() }
    }
}

/** settings.json in DataStore, encoded with the shared AppJson so backups can copy it as-is. */
object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings =
        try {
            AppJson.decodeFromString(AppSettings.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("settings.json unreadable", e)
        }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(AppJson.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}
