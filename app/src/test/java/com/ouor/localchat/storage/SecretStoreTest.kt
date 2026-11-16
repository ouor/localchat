package com.ouor.localchat.storage

import androidx.datastore.core.DataStore
import com.ouor.localchat.core.model.ApiKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.KeyGenerator

class SecretStoreTest {

    private class MemoryStore<T>(initial: T) : DataStore<T> {
        val state = MutableStateFlow(initial)
        override val data = state
        override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(state.value).also { state.value = it }
    }

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val file = MemoryStore(SecretEntries())
    private val store = SecretStore(file) { aes }

    @Test
    fun `stores ciphertext and returns the key`() = runTest {
        store.set("ep1", ApiKey("sk-test-123"))
        assertEquals("sk-test-123", store.get("ep1")?.value)
        assertFalse(file.state.value.byEndpoint.getValue("ep1").contains("sk-test"))
        assertEquals(setOf("ep1"), store.endpointIds.first())
    }

    @Test
    fun `ciphertext is bound to its endpoint`() = runTest {
        store.set("ep1", ApiKey("sk-test-123"))
        file.state.value = SecretEntries(mapOf("ep2" to file.state.value.byEndpoint.getValue("ep1")))
        assertNull(store.get("ep2"))
    }

    @Test
    fun `lost key or garbage yields null, null removes`() = runTest {
        store.set("ep1", ApiKey("sk-test-123"))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(SecretStore(file) { other }.get("ep1"))
        file.state.value = SecretEntries(mapOf("ep1" to "%%%"))
        assertNull(store.get("ep1"))
        store.set("ep1", null)
        assertEquals(emptySet<String>(), store.endpointIds.first())
    }
}
