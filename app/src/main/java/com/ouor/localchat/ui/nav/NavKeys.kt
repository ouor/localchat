package com.ouor.localchat.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Screens (architecture 10.3). Keys are saved with the back stack, so they hold ids only. */
@Serializable
data class ChatKey(val sessionId: String? = null) : NavKey

@Serializable
data class EndpointKey(val endpointId: String? = null, val preset: com.ouor.localchat.core.model.PresetId? = null, val custom: Boolean = false) : NavKey

/** SC-01. */
@Serializable
data object OnboardingKey : NavKey

@Serializable
data class ModelKey(val endpointId: String, val modelId: String) : NavKey

@Serializable
data object ProvidersKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object DataKey : NavKey
