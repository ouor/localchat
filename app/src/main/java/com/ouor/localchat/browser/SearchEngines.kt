package com.ouor.localchat.browser

import com.ouor.localchat.core.model.SearchEngine
import java.net.URI
import java.net.URLEncoder

/** S-05 result pages and the automatic engine switch (tools-redesign 6); the extractors are in assets/browser/search.js. */
object SearchEngines {
    /** [lang]: app language code (Google hl, Bing setlang); [country]: the user's region (Bing cc). DuckDuckGo and Naver get none. */
    fun url(engine: SearchEngine, query: String, lang: String? = null, country: String? = null): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val hl = lang?.takeIf { it.isNotBlank() }
        return when (engine) {
            SearchEngine.DUCKDUCKGO -> "https://html.duckduckgo.com/html/?q=$q"
            SearchEngine.GOOGLE -> "https://www.google.com/search?q=$q" + (hl?.let { "&hl=$it" } ?: "")
            SearchEngine.BING -> "https://www.bing.com/search?q=$q" + (hl?.let { "&setlang=$it" } ?: "") +
                (country?.takeIf { it.isNotBlank() }?.let { "&cc=${it.lowercase()}" } ?: "")
            SearchEngine.NAVER -> "https://search.naver.com/search.naver?query=$q"
        }
    }

    fun displayName(engine: SearchEngine): String = when (engine) {
        SearchEngine.DUCKDUCKGO -> "DuckDuckGo"
        SearchEngine.GOOGLE -> "Google"
        SearchEngine.BING -> "Bing"
        SearchEngine.NAVER -> "Naver"
    }

    private val FALLBACK = listOf(SearchEngine.DUCKDUCKGO, SearchEngine.BING, SearchEngine.GOOGLE, SearchEngine.NAVER)
    private val HANGUL = Regex("[\\uAC00-\\uD7A3\\u3131-\\u318E]")

    /** The configured engine first, then the others; a Korean query moves Naver to second. */
    fun order(configured: SearchEngine, query: String): List<SearchEngine> {
        val rest = FALLBACK - configured
        val others = if (HANGUL.containsMatchIn(query) && SearchEngine.NAVER in rest) listOf(SearchEngine.NAVER) + (rest - SearchEngine.NAVER) else rest
        return listOf(configured) + others
    }

    /** How one load of a result page went. */
    enum class Outcome { RESULTS, BLOCKED, EMPTY, NET_ERROR }

    enum class Next { DONE, RETRY, NEXT_ENGINE, NO_RESULTS, ALL_BLOCKED, NET_FAILED, TIMED_OUT }

    const val RETRY_MS = 1_500L
    const val BUDGET_MS = 30_000L

    /**
     * What to do after the latest attempt in [tries] (engine, outcome; oldest first) with [plan] the engine order.
     * Blocked pages, consent screens, HTTP errors and slow empty pages move on; a real empty result moves on once, the
     * second ends the search; a network error retries the same engine once, and two engines failing that way end it.
     * No new page is loaded once [elapsedMs] since the search started reaches [BUDGET_MS] (B-m10).
     */
    fun next(plan: List<SearchEngine>, tries: List<Pair<SearchEngine, Outcome>>, elapsedMs: Long): Next {
        val n = next(plan, tries)
        return if ((n == Next.RETRY || n == Next.NEXT_ENGINE) && elapsedMs >= BUDGET_MS) Next.TIMED_OUT else n
    }

    private fun next(plan: List<SearchEngine>, tries: List<Pair<SearchEngine, Outcome>>): Next {
        val (engine, outcome) = tries.last()
        if (outcome == Outcome.RESULTS) return Next.DONE
        if (outcome == Outcome.EMPTY && tries.count { it.second == Outcome.EMPTY } >= 2) return Next.NO_RESULTS
        if (outcome == Outcome.NET_ERROR) {
            if (tries.count { it.first == engine } < 2) return Next.RETRY
            // The engine before this one also ended on a network error (after its own retry).
            if (tries.lastOrNull { it.first != engine }?.second == Outcome.NET_ERROR) return Next.NET_FAILED
        }
        val tried = tries.map { it.first }.toSet()
        if (plan.any { it !in tried }) return Next.NEXT_ENGINE
        return when {
            tries.any { it.second == Outcome.EMPTY } -> Next.NO_RESULTS
            tries.all { it.second == Outcome.BLOCKED } -> Next.ALL_BLOCKED
            outcome == Outcome.NET_ERROR -> Next.NET_FAILED
            else -> Next.ALL_BLOCKED
        }
    }

    /**
     * Bot check / consent wording, matched case-insensitively against the first 3,000 characters of a page.
     * Also passed to search.js and agent.js.
     */
    const val BOT_PATTERN = "captcha|unusual traffic|before you continue|verify (that )?you are (a )?human|are you a robot|" +
        "not a robot|자동입력 방지|로봇이 아닙니다|비정상적인 트래픽"

    /** Bot check or consent page URLs. */
    val BOT_URL = Regex("google\\.[^/]+/sorry/|consent\\.google\\.|consent\\.yahoo\\.|bing\\.com/(turing|challenge)|duckduckgo\\.com/.*anomaly", RegexOption.IGNORE_CASE)

    private val TRACKING = Regex("^(utm_.*|gclid|fbclid|msclkid|yclid|mc_eid)$", RegexOption.IGNORE_CASE)

    /** [url] without tracking parameters; other parameters and their order stay. */
    fun clean(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val kept = url.substring(q + 1, hash).split('&').filter { it.isNotEmpty() && !TRACKING.matches(it.substringBefore('=')) }
        return url.substring(0, q) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + url.substring(hash)
    }

    /** A link back into a search engine itself (not a result). Google's /goto and /url redirects are results. */
    fun isEngineLink(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val google = Regex("(^|\\.)google\\.[a-z.]+$").containsMatchIn(host)
        if (google && (uri.path == "/goto" || uri.path == "/url")) return false
        return google || host == "bing.com" || host.endsWith(".bing.com") ||
            host == "duckduckgo.com" || host.endsWith(".duckduckgo.com") || host == "search.naver.com" || host == "m.search.naver.com"
    }
}
