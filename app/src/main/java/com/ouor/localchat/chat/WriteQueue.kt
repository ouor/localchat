package com.ouor.localchat.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * E-05 (architecture 8.3): a write is tried 3 times; if it still fails it is kept in memory,
 * keyed so only the newest write per key survives, and [health] turns FAILING.
 */
class WriteQueue {
    private val mutex = Mutex()
    private val pending = LinkedHashMap<String, suspend () -> Unit>()
    private val _health = MutableStateFlow(SaveHealth.OK)
    val health: StateFlow<SaveHealth> = _health.asStateFlow()

    suspend fun write(key: String, block: suspend () -> Unit) {
        mutex.withLock {
            // A newer write replaces a queued older one for the same key.
            pending.remove(key)
            if (!attempt(block)) pending[key] = block
            _health.value = if (pending.isEmpty()) SaveHealth.OK else SaveHealth.FAILING
        }
    }

    /** E-05 banner "retry". */
    suspend fun retry() {
        mutex.withLock {
            val it = pending.entries.iterator()
            while (it.hasNext()) if (attempt(it.next().value)) it.remove()
            _health.value = if (pending.isEmpty()) SaveHealth.OK else SaveHealth.FAILING
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean {
        repeat(3) {
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return false
    }
}
