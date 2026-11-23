package com.ouor.localchat.di

import android.content.Context
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.core.ApplicationScope
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.model.AppLanguage
import com.ouor.localchat.storage.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Singleton

/** The one shared browser (browser-layers 2-2), fed the search settings it reads. */
@Module
@InstallIn(SingletonComponent::class)
object BrowserModule {

    @Provides
    @Singleton
    fun engine(
        @ApplicationContext context: Context,
        settings: SettingsStore,
        @ApplicationScope scope: CoroutineScope,
        clock: Clock,
    ): BrowserEngine = BrowserEngine(context, scope, clock::now).also { engine ->
        scope.launch {
            settings.settings.collect { s ->
                engine.searchEngine = s.searchEngine
                engine.searchLanguage = when (s.language) {
                    AppLanguage.KO -> "ko"
                    AppLanguage.EN -> "en"
                    AppLanguage.SYSTEM -> Locale.getDefault().language
                }
                engine.region = s.region
            }
        }
    }
}
