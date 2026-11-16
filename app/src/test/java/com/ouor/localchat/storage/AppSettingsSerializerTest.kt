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

    @Test
    fun `a file from before onboarding existed decodes as not onboarded`() = runTest {
        val old = AppSettingsSerializer.readFrom(ByteArrayInputStream("{\"maxSteps\":30}".toByteArray()))
        assertEquals(false, old.onboarded)
        assertEquals(30, old.maxSteps)
    }

    @Test
    fun `built-in sensitive list defaults on and an off value round-trips (FB2-2)`() = runTest {
        assertEquals(true, AppSettingsSerializer.readFrom(ByteArrayInputStream("{}".toByteArray())).builtInSensitiveEnabled)
        val out = ByteArrayOutputStream()
        AppSettingsSerializer.writeTo(AppSettings(builtInSensitiveEnabled = false), out)
        assertEquals(false, AppSettingsSerializer.readFrom(ByteArrayInputStream(out.toByteArray())).builtInSensitiveEnabled)
    }

    @Test(expected = CorruptionException::class)
    fun `garbage is reported as corruption`() = runTest {
        AppSettingsSerializer.readFrom(ByteArrayInputStream("{not json".toByteArray()))
    }
}
