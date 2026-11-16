package com.ouor.localchat.backup

import com.ouor.localchat.backup.format.SecretsEnvelope
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SEC-4: PBKDF2WithHmacSHA256 (600,000 rounds, 16 B salt, 256 bit) -> AES-256-GCM (12 B IV, fixed AAD).
 * Slow on purpose (1-3 s on a phone): call off the main thread. The password is zeroed by the caller.
 */
object SecretsCipher {
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val AAD = "localchat-secrets-v1"
    const val ITERATIONS = 600_000

    /** Accepted on decrypt: a file cannot pick a weak KDF or tie up the CPU for minutes (SE 26). */
    val ACCEPTED_ITERATIONS = 100_000..2_000_000

    fun encrypt(plaintext: ByteArray, password: CharArray, iterations: Int = ITERATIONS): SecretsEnvelope {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.encodeToByteArray())
        return SecretsEnvelope(
            kdf = KDF, iterations = iterations, salt = b64(salt), cipher = CIPHER, iv = b64(iv), aad = AAD,
            ciphertext = b64(cipher.doFinal(plaintext)),
        )
    }

    /** Throws [BackupException] WRONG_PASSWORD on a bad tag, UNRECOGNIZED for an unknown algorithm, AAD or round count. */
    fun decrypt(e: SecretsEnvelope, password: CharArray): ByteArray {
        if (e.kdf != KDF || e.cipher != CIPHER || e.aad != AAD || e.iterations !in ACCEPTED_ITERATIONS) throw BackupException(BackupError.UNRECOGNIZED)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key(password, unb64(e.salt), e.iterations), GCMParameterSpec(128, unb64(e.iv)))
            cipher.updateAAD(AAD.encodeToByteArray())
            cipher.doFinal(unb64(e.ciphertext))
        } catch (x: AEADBadTagException) {
            throw BackupException(BackupError.WRONG_PASSWORD, x)
        } catch (x: IllegalArgumentException) {
            throw BackupException(BackupError.INCOMPLETE, x)
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            return try {
                SecretKeySpec(raw, "AES")
            } finally {
                raw.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}
