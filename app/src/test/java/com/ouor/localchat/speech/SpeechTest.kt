package com.ouor.localchat.speech

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {

    @Test
    fun `markdown is read without symbols, code or citations`() {
        val md = """
            # Title
            Some **bold** and _italic_ with `inline` code [1] and a [link](https://x.y).

            ```kotlin
            val secret = 1
            ```

            - item one
            1. item two
            > quoted

            | a | b |
            |---|---|
            | 1 | 2 |
            See https://example.com now.
        """.trimIndent()
        assertEquals(
            "Title\nSome bold and italic with inline code and a link.\n\nitem one\nitem two\nquoted\n\na b\n\n1 2\nSee now.",
            SpeechText.plain(md),
        )
        assertEquals("before", SpeechText.plain("before\n```\nunclosed code"))
    }

    @Test
    fun `chunks respect the engine limit at natural boundaries`() {
        val text = "First sentence here. Second one is longer than the first. Third."
        val parts = SpeechText.chunks(text, 30)
        assertTrue(parts.all { it.length <= 30 })
        assertEquals("First sentence here.", parts[0])
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
        assertEquals(listOf("a".repeat(10), "a".repeat(5)), SpeechText.chunks("a".repeat(15), 10))
    }

    @Test
    fun `pauses continue, real errors end`() {
        assertEquals(VoiceRules.Outcome.Continue, VoiceRules.onError(SpeechRecognizer.ERROR_NO_MATCH, stopping = false))
        assertEquals(VoiceRules.Outcome.End(VoiceError.NO_SPEECH), VoiceRules.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, stopping = true))
        assertEquals(VoiceRules.Outcome.End(VoiceError.NO_SERVICE), VoiceRules.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, stopping = false))
        assertEquals(VoiceRules.Outcome.End(VoiceError.FAILED), VoiceRules.onError(SpeechRecognizer.ERROR_NETWORK, stopping = false))
        assertEquals("hello world", VoiceRules.join(listOf(" hello ", "", "world")))
    }
}
