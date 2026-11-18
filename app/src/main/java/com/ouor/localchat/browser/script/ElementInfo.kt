package com.ouor.localchat.browser.script

import kotlinx.serialization.Serializable

/**
 * What assets/browser/agent.js reports about a target element ([BrowserTab.describe]); attribute values are as
 * written in the page (lowercased by JS where noted). Page-reported: only as trustworthy as the page.
 */
@Serializable
data class ElementInfo(
    /** Lowercase tag name. */
    val tag: String,
    /** Explicit or implicit ARIA role, null for generic elements. */
    val role: String? = null,
    /** Lowercase `type` attribute of input/button. */
    val type: String? = null,
    /** Accessible name (aria-label, label, text ...), at most 100 chars. */
    val name: String? = null,
    /** `name` attribute. */
    val nameAttr: String? = null,
    val id: String? = null,
    /** Lowercase `autocomplete` attribute. */
    val autocomplete: String? = null,
    val placeholder: String? = null,
    /** Lowercase effective method of the owning form (`formmethod` wins), null outside a form. */
    val formMethod: String? = null,
    val formAction: String? = null,
    val href: String? = null,
    /** A one-line search field (agent.js isSearchBox): type presses Enter after it by itself. */
    val search: Boolean = false,
)
