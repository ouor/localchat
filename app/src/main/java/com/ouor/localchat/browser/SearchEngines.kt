package com.ouor.localchat.browser

import com.ouor.localchat.core.model.SearchEngine
import java.net.URLEncoder

/** S-05 result page per engine; the extractors are in assets/browser/search.js. */
object SearchEngines {
    fun url(engine: SearchEngine, query: String): String {
        val q = URLEncoder.encode(query, "UTF-8")
        return when (engine) {
            SearchEngine.DUCKDUCKGO -> "https://html.duckduckgo.com/html/?q=$q"
            SearchEngine.GOOGLE -> "https://www.google.com/search?q=$q"
            SearchEngine.BING -> "https://www.bing.com/search?q=$q"
            SearchEngine.NAVER -> "https://search.naver.com/search.naver?query=$q"
        }
    }

    fun displayName(engine: SearchEngine): String = when (engine) {
        SearchEngine.DUCKDUCKGO -> "DuckDuckGo"
        SearchEngine.GOOGLE -> "Google"
        SearchEngine.BING -> "Bing"
        SearchEngine.NAVER -> "Naver"
    }
}
