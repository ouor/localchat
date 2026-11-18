package com.ouor.localchat.policy

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * B-S3: sites the AI may not read or operate. [builtIn] comes from assets/browser/sensitive_domains.txt and
 * can be switched off as a whole (FB2-2); [user] is AppSettings.userSensitiveDomains. A domain matches itself and
 * every subdomain.
 */
class SensitiveUrls(builtIn: Collection<String>, user: Collection<String>) {

    private val domains: Set<String> = (builtIn + user).mapNotNull(::normalize).toSet()

    /** Fail-closed (SE 20): an http(s) URL whose host cannot be parsed counts as sensitive. */
    fun isSensitive(url: String): Boolean {
        val host = hostOf(url) ?: return isWeb(url)
        return generateSequence(host) { h -> h.substringAfter('.', "").takeIf { it.isNotEmpty() } }
            .any { it in domains }
    }

    companion object {
        /** In every refusal for a sensitive site; chat's step reason looks for it (BUG-10). */
        const val REFUSAL = "is a sensitive site"

        /** Lines of sensitive_domains.txt; `#` starts a comment. */
        fun parseList(text: String): List<String> =
            text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()

        /** OkHttp's parser first: it accepts IDN and `_` hosts that java.net.URI rejects. */
        fun hostOf(url: String): String? =
            (url.trim().toHttpUrlOrNull()?.host ?: runCatching { URI(url.trim()).host }.getOrNull())
                ?.lowercase()?.trimEnd('.')?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

        private fun isWeb(url: String) = url.trim().substringBefore(':').lowercase().let { it == "http" || it == "https" }

        /** Accepts `bank.com`, `*.bank.com`, `https://bank.com/x`. */
        private fun normalize(entry: String): String? {
            val e = entry.trim().lowercase().removePrefix("*.").removePrefix(".")
            if (e.isEmpty()) return null
            return if ("://" in e) hostOf(e) else e.substringBefore('/').trimEnd('.').removePrefix("www.")
        }
    }
}
