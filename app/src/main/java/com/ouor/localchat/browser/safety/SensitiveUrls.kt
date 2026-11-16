package com.ouor.localchat.browser.safety

import java.net.URI

/**
 * B-S3: sites the AI may not read or operate. [builtIn] comes from assets/browser/sensitive_domains.txt and
 * cannot be removed; [user] is AppSettings.userSensitiveDomains (additions only). A domain matches itself and
 * every subdomain.
 */
class SensitiveUrls(builtIn: Collection<String>, user: Collection<String> = emptyList()) {

    private val domains: Set<String> = (builtIn + user).mapNotNull(::normalize).toSet()

    fun isSensitive(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return generateSequence(host) { h -> h.substringAfter('.', "").takeIf { it.isNotEmpty() } }
            .any { it in domains }
    }

    fun withUser(user: Collection<String>): SensitiveUrls = SensitiveUrls(domains, user)

    companion object {
        /** Lines of sensitive_domains.txt; `#` starts a comment. */
        fun parseList(text: String): List<String> =
            text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()

        fun hostOf(url: String): String? =
            runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.')?.removePrefix("www.")
                ?.takeIf { it.isNotEmpty() }

        /** Accepts `bank.com`, `*.bank.com`, `https://bank.com/x`. */
        private fun normalize(entry: String): String? {
            val e = entry.trim().lowercase().removePrefix("*.").removePrefix(".")
            if (e.isEmpty()) return null
            return if ("://" in e) hostOf(e) else e.substringBefore('/').trimEnd('.').removePrefix("www.")
        }
    }
}
