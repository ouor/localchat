package com.ouor.localchat.policy

import com.ouor.localchat.tools.ToolOutcome
import java.net.URLDecoder

/**
 * B-S4 (browser-layers 4-2): the cookie values the call's pages can see are hidden in the page text the model reads,
 * for every main-frame URL the target tab requested during the call (redirects included).
 */
class CookieMaskPolicy : ToolPolicy {
    override fun after(call: PolicyCall, facts: CallFacts, result: ToolOutcome): ToolOutcome {
        if (result.page.isEmpty()) return result
        val urls = (facts.mainRequests + listOfNotNull(facts.tabAfter?.url)).distinct()
        val page = urls.fold(result.page) { text, url -> mask(text, facts.cookieHeader(url)) }
        return if (page == result.page) result else result.copy(page = page)
    }

    companion object {
        const val MASK = "[cookie hidden by the app]"
        private const val MIN_LENGTH = 6

        /** [cookieHeader] is `a=1; b=2`. */
        fun mask(text: String, cookieHeader: String?): String {
            if (cookieHeader.isNullOrBlank()) return text
            val values = cookieHeader.split(';')
                .mapNotNull { it.substringAfter('=', "").trim().takeIf { v -> v.length >= MIN_LENGTH } }
                .flatMap { listOf(it, runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)) }
                .flatMap { listOf(it, jsonEscaped(it)) } // SE 19: values echoed inside JSON strings
                .filter { it.length >= MIN_LENGTH }
                .distinct()
                .sortedByDescending { it.length } // longer first so a value containing another is masked whole
            return values.fold(text) { acc, v -> acc.replace(v, MASK) }
        }

        private fun jsonEscaped(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
