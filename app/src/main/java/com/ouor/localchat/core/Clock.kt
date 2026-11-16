package com.ouor.localchat.core

/** Epoch milliseconds. Injected so tests can control time. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
