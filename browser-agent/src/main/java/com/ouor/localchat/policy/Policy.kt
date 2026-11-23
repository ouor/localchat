package com.ouor.localchat.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What any policy may contribute besides its decisions (browser-layers 4-1). The prompt, the tool list, the drawer
 * and the settings screen take only these generic shapes; none of them knows a policy by name.
 */
interface Policy {
    /** Lines added at the end of the system prompt. */
    fun promptLines(): List<String> = emptyList()

    /** Tool names left out of what the model can call. */
    fun hiddenTools(): Set<String> = emptySet()

    /** Badges and banners for the drawer. */
    val notices: StateFlow<List<Notice>> get() = NO_NOTICES

    /** Rows for the settings screen's browser section. */
    fun settings(): List<SettingItem> = emptyList()
}

val NO_NOTICES: StateFlow<List<Notice>> = MutableStateFlow(emptyList())

/** [tabId] null = about the browser as a whole. A BADGE goes on the tab's card, a BANNER over the drawer when the tab shows. */
data class Notice(val key: String, val text: String, val tabId: Int?, val style: Style) {
    enum class Style { BADGE, BANNER }
}

sealed interface SettingItem {
    val key: String
    val title: String
    val description: StateFlow<String>

    /** [offConfirm]: asked before the switch goes off. */
    data class Switch(
        override val key: String,
        override val title: String,
        override val description: StateFlow<String>,
        val value: StateFlow<Boolean>,
        val set: (Boolean) -> Unit,
        val offConfirm: Confirm? = null,
    ) : SettingItem

    /** A list the user adds to ([addLabel], a field with [placeholder]; [normalize] null = not a valid entry) and removes from. */
    data class TextList(
        override val key: String,
        override val title: String,
        override val description: StateFlow<String>,
        val values: StateFlow<List<String>>,
        val set: (List<String>) -> Unit,
        val addLabel: String,
        val placeholder: String,
        val normalize: (String) -> String?,
    ) : SettingItem

    data class Confirm(val title: String, val body: String, val action: String)
}
