package com.ouor.localchat.browser

import com.ouor.localchat.browser.SearchEngines.Next
import com.ouor.localchat.browser.SearchEngines.Outcome
import com.ouor.localchat.browser.SearchEngine
import com.ouor.localchat.browser.SearchEngine.BING
import com.ouor.localchat.browser.SearchEngine.DUCKDUCKGO
import com.ouor.localchat.browser.SearchEngine.GOOGLE
import com.ouor.localchat.browser.SearchEngine.NAVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** tools-redesign 6: result page URLs, engine order and the switch table. */
class SearchEnginesTest {

    @Test
    fun `result URLs carry the app language and region where the engine takes them`() {
        assertEquals("https://www.google.com/search?q=a+b&hl=ko", SearchEngines.url(GOOGLE, "a b", "ko", "KR"))
        assertEquals("https://www.bing.com/search?q=a&setlang=ko&cc=kr", SearchEngines.url(BING, "a", "ko", "KR"))
        assertEquals("https://www.bing.com/search?q=a", SearchEngines.url(BING, "a"))
        assertEquals("https://html.duckduckgo.com/html/?q=a", SearchEngines.url(DUCKDUCKGO, "a", "ko", "KR"))
        assertEquals("https://search.naver.com/search.naver?query=%ED%95%9C", SearchEngines.url(NAVER, "한", "ko"))
    }

    @Test
    fun `the configured engine goes first and a Korean query tries Naver second`() {
        assertEquals(listOf(DUCKDUCKGO, BING, GOOGLE, NAVER), SearchEngines.order(DUCKDUCKGO, "kotlin"))
        assertEquals(listOf(GOOGLE, DUCKDUCKGO, BING, NAVER), SearchEngines.order(GOOGLE, "kotlin"))
        assertEquals(listOf(DUCKDUCKGO, NAVER, BING, GOOGLE), SearchEngines.order(DUCKDUCKGO, "날씨 서울"))
        assertEquals(listOf(NAVER, DUCKDUCKGO, BING, GOOGLE), SearchEngines.order(NAVER, "날씨"))
    }

    private val plan = listOf(DUCKDUCKGO, BING, GOOGLE, NAVER)
    private fun next(vararg tries: Pair<SearchEngine, Outcome>) = SearchEngines.next(plan, tries.toList(), elapsedMs = 0)

    @Test
    fun `switch table`() {
        assertEquals(Next.DONE, next(DUCKDUCKGO to Outcome.RESULTS))
        assertEquals(Next.NEXT_ENGINE, next(DUCKDUCKGO to Outcome.BLOCKED))
        assertEquals(Next.NEXT_ENGINE, next(DUCKDUCKGO to Outcome.EMPTY)) // one empty page may be a parse miss
        assertEquals(Next.NO_RESULTS, next(DUCKDUCKGO to Outcome.EMPTY, BING to Outcome.EMPTY))
        assertEquals(Next.RETRY, next(DUCKDUCKGO to Outcome.NET_ERROR))
        assertEquals(Next.NEXT_ENGINE, next(DUCKDUCKGO to Outcome.NET_ERROR, DUCKDUCKGO to Outcome.NET_ERROR))
        assertEquals(Next.RETRY, next(DUCKDUCKGO to Outcome.NET_ERROR, DUCKDUCKGO to Outcome.NET_ERROR, BING to Outcome.NET_ERROR))
        assertEquals(
            Next.NET_FAILED,
            next(DUCKDUCKGO to Outcome.NET_ERROR, DUCKDUCKGO to Outcome.NET_ERROR, BING to Outcome.NET_ERROR, BING to Outcome.NET_ERROR),
        )
        assertEquals(Next.ALL_BLOCKED, next(*plan.map { it to Outcome.BLOCKED }.toTypedArray()))
        assertEquals(Next.NO_RESULTS, next(DUCKDUCKGO to Outcome.BLOCKED, BING to Outcome.EMPTY, GOOGLE to Outcome.BLOCKED, NAVER to Outcome.BLOCKED))
    }

    @Test
    fun `the budget is checked before every new load, retries included`() { // B-m10
        val late = SearchEngines.BUDGET_MS
        assertEquals(Next.TIMED_OUT, SearchEngines.next(plan, listOf(DUCKDUCKGO to Outcome.BLOCKED), late))
        assertEquals(Next.TIMED_OUT, SearchEngines.next(plan, listOf(DUCKDUCKGO to Outcome.NET_ERROR), late))
        assertEquals(Next.NEXT_ENGINE, SearchEngines.next(plan, listOf(DUCKDUCKGO to Outcome.BLOCKED), late - 1))
        // An ending answer stays what it is.
        assertEquals(Next.DONE, SearchEngines.next(plan, listOf(DUCKDUCKGO to Outcome.RESULTS), late * 2))
        assertEquals(Next.NO_RESULTS, SearchEngines.next(plan, listOf(DUCKDUCKGO to Outcome.EMPTY, BING to Outcome.EMPTY), late * 2))
    }

    @Test
    fun `result links lose tracking parameters and engine links are told apart`() {
        assertEquals("https://a.test/p?id=3#top", SearchEngines.clean("https://a.test/p?utm_source=x&id=3&gclid=1#top"))
        assertEquals("https://a.test/p", SearchEngines.clean("https://a.test/p?fbclid=1"))
        assertEquals("https://a.test/p", SearchEngines.clean("https://a.test/p"))
        assertTrue(SearchEngines.isEngineLink("https://www.google.co.kr/search?q=a"))
        assertFalse(SearchEngines.isEngineLink("https://www.google.com/goto?url=CAES1")) // an opaque result redirect
        assertTrue(SearchEngines.isEngineLink("https://m.search.naver.com/x"))
        assertTrue(SearchEngines.isEngineLink("https://duckduckgo.com/?q=a"))
        assertFalse(SearchEngines.isEngineLink("https://blog.naver.com/x"))
        assertFalse(SearchEngines.isEngineLink("https://notgoogle.example/"))
    }

    @Test
    fun `bot checks are recognized by wording and URL`() {
        val bot = Regex(SearchEngines.BOT_PATTERN, RegexOption.IGNORE_CASE)
        assertTrue(bot.containsMatchIn("Our systems have detected UNUSUAL TRAFFIC from your computer"))
        assertTrue(bot.containsMatchIn("자동입력 방지를 위해"))
        assertFalse(bot.containsMatchIn("Kotlin coroutines guide"))
        assertTrue(SearchEngines.BOT_URL.containsMatchIn("https://www.google.com/sorry/index?continue="))
        assertFalse(SearchEngines.BOT_URL.containsMatchIn("https://www.google.com/search?q=a"))
    }
}
