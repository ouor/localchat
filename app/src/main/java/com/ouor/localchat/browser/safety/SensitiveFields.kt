package com.ouor.localchat.browser.safety

/**
 * B-S1: fields the AI may never type into or read. agent.js applies the same rule to mask snapshot
 * values; it receives [AUTOCOMPLETE], [AUTOCOMPLETE_PREFIX] and [NAME_PATTERN] from PageScript so the rule
 * lives only here.
 */
object SensitiveFields {
    val AUTOCOMPLETE = setOf("current-password", "new-password", "one-time-code")
    const val AUTOCOMPLETE_PREFIX = "cc-"

    /** Matched case-insensitively against name, id, accessible name and placeholder. JS-compatible syntax. */
    const val NAME_PATTERN =
        "(^|[^a-z])(otp|cvc|cvv|cvn|csc|pin|passcode|passwd|password|pwd)([^a-z]|$)" +
            "|card.?num|cc.?num|security.?code|비밀번호|인증번호|보안코드|카드번호"

    private val nameRegex = Regex(NAME_PATTERN, RegexOption.IGNORE_CASE)

    private val FRAMES = setOf("iframe", "frame")

    /**
     * Every input tool (type, keyboard) asks this one rule. [scriptSensitive] is agent.js's verdict on the live
     * element. A focused frame is refused too: its (cross-origin) content, e.g. a payment widget, cannot be checked.
     */
    fun refusesInput(target: ElementInfo?, scriptSensitive: Boolean): Boolean =
        target != null && (scriptSensitive || target.tag in FRAMES || isSensitive(target))

    fun isFrame(e: ElementInfo): Boolean = e.tag in FRAMES

    fun isSensitive(e: ElementInfo): Boolean {
        if (e.type == "password") return true
        val tokens = e.autocomplete.orEmpty().lowercase().split(' ').filter { it.isNotBlank() }
        if (tokens.any { it in AUTOCOMPLETE || it.startsWith(AUTOCOMPLETE_PREFIX) }) return true
        return listOfNotNull(e.nameAttr, e.id, e.name, e.placeholder).any { nameRegex.containsMatchIn(it) }
    }
}
