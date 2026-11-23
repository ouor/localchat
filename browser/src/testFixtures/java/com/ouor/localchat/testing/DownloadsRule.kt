package com.ouor.localchat.testing

import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.rules.ExternalResource

/**
 * Deletes the Downloads entries this app added during the test, so test runs leave no files behind. Only this
 * package's own entries added since the test started; files already on the device stay.
 *
 * ```
 * @get:Rule val downloads = DownloadsRule()
 * ```
 */
class DownloadsRule : ExternalResource() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var startSec = 0L

    override fun before() {
        startSec = System.currentTimeMillis() / 1000 - 1
    }

    override fun after() {
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?",
            arrayOf(context.packageName, startSec.toString()),
        )
    }
}
