package com.ouor.localchat.browser.safety

import kotlinx.serialization.Serializable

/**
 * What assets/browser/agent.js reports about a target element. Inputs of the pure safety rules
 * ([SensitiveFields], [IrreversibleAction]); attribute values are as written in the page (lowercased by JS
 * where noted).
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
    /** Inside a `[role=search]` landmark (type's automatic Enter). */
    val search: Boolean = false,
)
