package com.ouor.localchat.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class SecretsCipherTest {
    private val secret = """{"ep1":"sk-not-real"}""".encodeToByteArray()

    @Test
    fun `round trip with the SEC-4 parameters`() {
        val e = SecretsCipher.encrypt(secret, "correct horse".toCharArray())
        assertEquals(600_000, e.iterations)
        assertEquals("PBKDF2WithHmacSHA256", e.kdf)
        assertEquals("AES/GCM/NoPadding", e.cipher)
        assertEquals(16, java.util.Base64.getDecoder().decode(e.salt).size)
        assertEquals(12, java.util.Base64.getDecoder().decode(e.iv).size)
        assertArrayEquals(secret, SecretsCipher.decrypt(e, "correct horse".toCharArray()))
    }

    @Test
    fun `fresh salt and iv every time`() {
        val a = SecretsCipher.encrypt(secret, "pw".toCharArray(), iterations = 1000)
        val b = SecretsCipher.encrypt(secret, "pw".toCharArray(), iterations = 1000)
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.iv, b.iv)
    }

    @Test
    fun `wrong password and tampering are WRONG_PASSWORD`() {
        val e = SecretsCipher.encrypt(secret, "pw".toCharArray(), iterations = 1000)
        expectWrong { SecretsCipher.decrypt(e, "other".toCharArray()) }
        expectWrong { SecretsCipher.decrypt(e.copy(aad = "other-aad"), "pw".toCharArray()) }
    }

    private fun expectWrong(block: () -> Unit) {
        try {
            block()
            fail()
        } catch (x: BackupException) {
            assertEquals(BackupError.WRONG_PASSWORD, x.error)
        }
    }
}
