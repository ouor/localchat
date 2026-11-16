package com.ouor.localchat.core.model

import kotlinx.serialization.Serializable

/**
 * D8 app settings, one DataStore JSON file. Every new field needs a default so old files still
 * decode (architecture 8.2).
 */
@Serializable
data class AppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** G-03 ISO 3166 country code, null = none. Used only in the A-08 system context. */
    val region: String? = null,
    /** G-04 TTS voice name, null = engine default. */
    val ttsVoice: String? = null,
    val ttsRate: Float = 1.0f,
    /** P-07 default model for new chats. */
    val defaultModel: ModelRef? = null,
    /** G-05 last composer toggle state. */
    val lastOptions: GenOptions = GenOptions(),
    /** A-02: 5..100. */
    val maxSteps: Int = 25,
    /** A-09 custom instructions. */
    val userInstructions: String = "",
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    /** B-S3 user additions; the built-in list cannot be removed. */
    val userSensitiveDomains: List<String> = emptyList(),
    /** C-06 wide-screen session panel. */
    val sessionPanelCollapsed: Boolean = false,
    /** SC-01: the onboarding sheet was finished or skipped once; the UI sets it. */
    val onboarded: Boolean = false,
)

@Serializable
enum class AppLanguage { SYSTEM, KO, EN }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class SearchEngine { DUCKDUCKGO, GOOGLE, BING, NAVER }
