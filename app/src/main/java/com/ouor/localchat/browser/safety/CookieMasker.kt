package com.ouor.localchat.browser.safety

import java.net.URLDecoder

/** B-S4: hide the page's current cookie values in text the AI will read. */
object CookieMasker {
    const val MASK = "[cookie]"
    private const val MIN_LENGTH = 6

    /** [cookieHeader] is `CookieManager.getCookie(url)`: `a=1; b=2`. */
    fun mask(text: String, cookieHeader: String?): String {
        if (cookieHeader.isNullOrBlank()) return text
        val values = cookieHeader.split(';')
            .mapNotNull { it.substringAfter('=', "").trim().takeIf { v -> v.length >= MIN_LENGTH } }
            .flatMap { listOf(it, runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)) }
            .filter { it.length >= MIN_LENGTH }
            .distinct()
            .sortedByDescending { it.length } // longer first so a value containing another is masked whole
        return values.fold(text) { acc, v -> acc.replace(v, MASK) }
    }
}
