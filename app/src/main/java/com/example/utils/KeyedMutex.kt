package com.example.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes one delivery identity without making unrelated incoming OTPs wait. */
internal class KeyedMutex<K> {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val gate = Any()
    private val entries = mutableMapOf<K, Entry>()

    suspend fun <T> withLock(key: K, operation: suspend () -> T): T {
        val entry = synchronized(gate) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        return try {
            entry.mutex.withLock { operation() }
        } finally {
            synchronized(gate) {
                entry.users--
                if (entry.users == 0) entries.remove(key)
            }
        }
    }
}
