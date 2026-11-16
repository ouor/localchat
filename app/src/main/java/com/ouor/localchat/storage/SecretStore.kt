package com.ouor.localchat.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.ouor.localchat.core.AppJson
import com.ouor.localchat.core.model.ApiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D7 API keys (architecture 8.2): DataStore secrets.json holds base64(iv ‖ AES-256-GCM ciphertext)
 * per endpoint, with the endpoint id as AAD. Plain text only leaves through [get] as [ApiKey].
 */
@Singleton
class SecretStore @Inject constructor(
    private val store: DataStore<SecretEntries>,
    private val keys: SecretKeySource,
) {

    /** Endpoint ids that have a key (Endpoint.hasKey). */
    val endpointIds: Flow<Set<String>> = store.data.map { it.byEndpoint.keys }

    /** null when absent or undecryptable (keystore key lost, e.g. after reinstall). */
    suspend fun get(endpointId: String): ApiKey? {
        val sealed = store.data.first().byEndpoint[endpointId] ?: return null
        return try {
            ApiKey(open(endpointId, sealed))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Backup (SEC-4): every key that still decrypts. */
    suspend fun all(): Map<String, ApiKey> =
        store.data.first().byEndpoint.keys.mapNotNull { id -> get(id)?.let { id to it } }.toMap()

    /** Restore (K-04 step 3): the stored keys become exactly [keys]. */
    suspend fun replaceAll(keys: Map<String, ApiKey>) {
        store.updateData { SecretEntries(keys.mapValues { (id, key) -> seal(id, key.value) }) }
    }

    /** null removes the key. */
    suspend fun set(endpointId: String, key: ApiKey?) {
        store.updateData { entries ->
            val map = entries.byEndpoint.toMutableMap()
            if (key == null) map.remove(endpointId) else map[endpointId] = seal(endpointId, key.value)
            SecretEntries(map)
        }
    }

    private fun seal(endpointId: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        cipher.updateAAD(endpointId.encodeToByteArray())
        val iv = cipher.iv
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.encodeToByteArray()))
    }

    private fun open(endpointId: String, sealed: String): String {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        cipher.updateAAD(endpointId.encodeToByteArray())
        return cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/** The AES key. Production: [AndroidKeyStoreKeySource]; JVM tests pass a plain key. */
fun interface SecretKeySource {
    fun key(): SecretKey
}

/** Alias localchat.secrets, AES-256-GCM, no user authentication (architecture 8.2). */
class AndroidKeyStoreKeySource @Inject constructor() : SecretKeySource {
    override fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "localchat.secrets"
    }
}

@Serializable
data class SecretEntries(val byEndpoint: Map<String, String> = emptyMap())

object SecretEntriesSerializer : Serializer<SecretEntries> {
    override val defaultValue = SecretEntries()

    override suspend fun readFrom(input: InputStream): SecretEntries =
        try {
            AppJson.decodeFromString(SecretEntries.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("secrets.json unreadable", e)
        }

    override suspend fun writeTo(t: SecretEntries, output: OutputStream) {
        output.write(AppJson.encodeToString(SecretEntries.serializer(), t).encodeToByteArray())
    }
}
