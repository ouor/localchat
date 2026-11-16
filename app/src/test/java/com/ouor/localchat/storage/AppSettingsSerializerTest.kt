package com.ouor.localchat.storage

import androidx.datastore.core.CorruptionException
import com.ouor.localchat.core.model.AppSettings
import com.ouor.localchat.core.model.ThemeMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppSettingsSerializerTest {

    @Test
    fun `round-trips`() = runTest {
        val settings = AppSettings(theme = ThemeMode.DARK, maxSteps = 50)
        val out = ByteArrayOutputStream()
        AppSettingsSerializer.writeTo(settings, out)
        assertEquals(settings, AppSettingsSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }

    @Test(expected = CorruptionException::class)
    fun `garbage is reported as corruption`() = runTest {
        AppSettingsSerializer.readFrom(ByteArrayInputStream("{not json".toByteArray()))
    }
}
