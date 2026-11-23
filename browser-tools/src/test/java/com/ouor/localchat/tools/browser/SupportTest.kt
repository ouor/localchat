package com.ouor.localchat.tools.browser

import com.ouor.localchat.browser.NavError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTest {

    // --- navigation errors (tools-redesign 2-2)

    private fun net(desc: String) = NavError(0, "https://a.test/", false, -2, desc)
    private fun http(code: Int) = NavError(0, "https://a.test/", true, code, "")

    @Test
    fun `only passing failures are retried, after their own delay`() {
        assertEquals(1_500L, netRetry(net("net::ERR_NAME_NOT_RESOLVED")))
        assertEquals(1_500L, netRetry(net("net::ERR_CONNECTION_RESET")))
        assertNull(netRetry(net("net::ERR_CERT_DATE_INVALID")))
        assertEquals(2_000L, netRetry(http(503)))
        assertEquals(2_000L, netRetry(http(429)))
        assertNull(netRetry(http(404)))
    }

    @Test
    fun `error texts say what happened and whether to retry`() {
        assertEquals("https://a.test/ answered HTTP 404 (not found). Do not retry this URL.", navErrorText(http(404), false))
        assertTrue(navErrorText(http(403), false).contains("sign-in"))
        assertTrue(navErrorText(http(503), true).endsWith("already retried once."))
        assertEquals(
            "Could not open https://a.test/: the site's address was not found (net::ERR_NAME_NOT_RESOLVED). Already retried once; try again later or use another source.",
            navErrorText(net("net::ERR_NAME_NOT_RESOLVED"), true),
        )
        assertTrue(navErrorText(net("net::ERR_CERT_AUTHORITY_INVALID"), false).contains("certificate"))
    }

    // --- read sizes (tools-redesign 3-2, 5-3)

    @Test
    fun `read part and budget follow the context window`() {
        assertEquals(12_000, readChunk(null))
        assertEquals(4_000, readChunk(8_000))
        assertEquals(8_000, readChunk(32_000))
        assertEquals(20_000, readChunk(200_000))
        assertEquals(40_000, readKeep(null))
        assertEquals(4_000, readKeep(4_000)) // never below one part
        assertEquals(19_200, readKeep(32_000))
        assertEquals(40_000, readKeep(1_000_000))
    }

    @Test
    fun `parts are cut after a blank line when there is one`() {
        assertEquals(listOf(""), parts("", 10))
        assertEquals(listOf("aaaa", "bbbb"), parts("aaaa\n\nbbbb", 8))
        assertEquals(listOf("abcde", "fgh"), parts("abcdefgh", 5)) // no blank line: a hard cut
        val text = (1..50).joinToString("\n\n") { "para $it" }
        assertEquals(text.replace("\n", ""), parts(text, 37).joinToString("") { it.replace("\n", "") })
        assertTrue(parts(text, 37).all { it.length <= 37 })
    }
}
