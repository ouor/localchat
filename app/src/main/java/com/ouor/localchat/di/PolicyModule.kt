package com.ouor.localchat.di

import android.app.ActivityManager
import android.content.Context
import com.ouor.localchat.browser.BrowserEngine
import com.ouor.localchat.core.ApplicationScope
import com.ouor.localchat.policy.BrowserPolicy
import com.ouor.localchat.policy.CookieMaskPolicy
import com.ouor.localchat.policy.MoneyConfirmPolicy
import com.ouor.localchat.policy.RepeatFailurePolicy
import com.ouor.localchat.policy.SecretValuePolicy
import com.ouor.localchat.policy.SensitiveUrls
import com.ouor.localchat.policy.SiteBlockPolicy
import com.ouor.localchat.policy.TabMemoryPolicy
import com.ouor.localchat.policy.Texts
import com.ouor.localchat.policy.ToolPolicy
import com.ouor.localchat.storage.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * The two policy lists (browser-layers 4-1). The order is the order of before, after and onEvent; a new policy is one
 * line in the right place, and removing one is deleting its line. No other module knows a policy.
 */
@Module
@InstallIn(SingletonComponent::class)
object PolicyModule {

    @Provides
    @Singleton
    fun toolPolicies(
        @ApplicationContext context: Context,
        settings: SettingsStore,
        @ApplicationScope scope: CoroutineScope,
        engine: BrowserEngine,
        texts: Texts,
    ): List<@JvmSuppressWildcards ToolPolicy> = listOf(
        SiteBlockPolicy(builtInDomains(context), settings, scope, engine, texts), // before: refuse / after: withhold
        MoneyConfirmPolicy(texts), // before: money and account deletion
        SecretValuePolicy(), // after: [secret] values
        CookieMaskPolicy(), // after: cookie values
        RepeatFailurePolicy(), // after: the same failure twice (after the masking)
    )

    @Provides
    @Singleton
    fun browserPolicies(@ApplicationContext context: Context): List<@JvmSuppressWildcards BrowserPolicy> {
        val prefs = context.getSharedPreferences("policy_tab_memory", Context.MODE_PRIVATE)
        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return listOf(
            TabMemoryPolicy(TabMemoryPolicy.startK(mem.totalMem), prefs.getInt("k", 0).takeIf { it > 0 }) { prefs.edit().putInt("k", it).apply() },
        )
    }

    @Provides
    fun texts(@ApplicationContext context: Context): Texts = object : Texts {
        override fun get(id: Int, vararg args: Any) = context.getString(id, *args)
        override fun plural(id: Int, count: Int, vararg args: Any) = context.resources.getQuantityString(id, count, *args)
    }

    private fun builtInDomains(context: Context) =
        SensitiveUrls.parseList(context.assets.open("browser/sensitive_domains.txt").bufferedReader().use { it.readText() })
}
